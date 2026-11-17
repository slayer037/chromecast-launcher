package com.majendra.tvlauncher.providers

import android.content.Context
import com.majendra.tvlauncher.data.*
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

class LiveTvProvider(private val context: Context) : FeedAdapter {
    override val id = "live"
    override val displayName = "Live TV"
    private val mutex = Mutex()
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Deferred<Unit>? = null
    private val client by lazy { LauncherHttp.client.newBuilder().callTimeout(18, TimeUnit.SECONDS).connectTimeout(6, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build() }
    private val cache = File(context.filesDir, "melbourne.xml")
    // The parsed guide is shared with Sports Centre and limited to what can be shown; this provider owns the download.
    private val guides = GuideStore.melbourne(context)
    private var lastAttempt = 0L
    @Volatile private var sourceError: String? = null
    private val logoLookup = ChannelLogoLookup(context) { client }
    private var logoJob: Deferred<Unit>? = null
    private var lastLogoAttempt = 0L
    private var logoWaited = false
    override suspend fun isAvailable() = true

    override suspend fun load(forceRefresh: Boolean): FeedResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val requestedAt = System.currentTimeMillis()
            var data = guides.load(requestedAt)
            if (data == null && cache.isFile) sourceError = "Saved guide is unreadable or has no current schedules"
            val due = forceRefresh || data == null || !cache.exists() || requestedAt - cache.lastModified() >= GUIDE_INTERVAL_MILLIS
            // A slow network must never hide a usable saved schedule or lock every broadcaster.
            // Only the initial cold load waits; subsequent calls evaluate saved schedules immediately.
            if (due && refreshJob?.isActive != true && requestedAt - lastAttempt >= 60_000L) {
                lastAttempt = requestedAt
                refreshJob = refreshScope.async { downloadGuide() }
            }
            val channels = TvChannelSource.channels(context)
            if (data == null && channels.isEmpty()) { refreshJob?.await(); data = guides.load(System.currentTimeMillis()) }
            // TCL Channel logos come from a cached Wikipedia lookup: wait briefly the first time, then refresh behind.
            val tclNames = channels.filter { it.tclChannel && LiveChannelCategories.visible(LiveChannelCategories.of(it.name, it.number, true)) }.map { it.name }
            if (logoLookup.needsRefresh(tclNames) && logoJob?.isActive != true && requestedAt - lastLogoAttempt >= 60_000L) {
                lastLogoAttempt = requestedAt
                logoJob = refreshScope.async { logoLookup.refresh(tclNames) }
            }
            // Wait once per launch at most: offline, every load would otherwise stall antenna channels too.
            if (logoLookup.cached().isEmpty() && !logoWaited) { logoWaited = true; withTimeoutOrNull(8_000L) { logoJob?.await() } }
            val logos = logoLookup.cached()
            // Fetch/parse can take seconds; test programme boundaries against the current clock.
            val now = System.currentTimeMillis()
            val tiles = SimulcastPolicy.distinct(if (channels.isNotEmpty()) channels.map { tile(it, data?.let { g -> match(g, it) }, data, now, logos[it.name]) }
                else data?.let { g -> GuideChannelPolicy.television(g.channels).map { tile(null, it, g, now) } }.orEmpty())
            if (tiles.isEmpty()) return@withLock FeedResult(status = FeedStatus.SOURCE_UNAVAILABLE, message = "No channels found. Scan for antenna channels in the TV's own settings, or open native TV.",
                error = FeedError("guide_unavailable", sourceError ?: "No channels or cached guide"))
            // Antenna channels first (by number) in each category, then TCL Channel by name.
            // Shopping, community and radio are hidden categories, so they never get a row; HDMI inputs are not shown here.
            val rows = tiles.groupBy { it.metadata["category"] }.let { groups ->
                LiveChannelCategories.order.mapNotNull { name -> groups[name]?.let { ContentRow("live-${name.lowercase().replace(Regex("[^a-z]+"), "-")}", name, it) } }
            }
            if (rows.isEmpty()) return@withLock FeedResult(status = FeedStatus.SOURCE_UNAVAILABLE, message = "No TV channels found. Scan for antenna channels in the TV's own settings, or open native TV.",
                error = FeedError("guide_unavailable", sourceError ?: "No television channels"))
            val guideState = if (data == null) " Programme guide unavailable" + (sourceError?.let { ": $it" } ?: "") + "." else ""
            FeedResult(rows, FeedStatus.FRESH, "Antenna and TCL Channel, sorted by type.$guideState", now)
        }
    }

    /**
     * The guide channel for a tuned channel: same channel number, else the same normalised name. Match against every
     * guide channel: the collapsed simulcast list drops SD numbers (SBS ONE on 3 only exists as "SBS Melbourne" 3).
     */
    private fun match(guide: Guide, channel: TvChannel): GuideChannel? = guide.channels.let { list ->
        list.firstOrNull { it.number != null && it.number == channel.number } ?: list.firstOrNull { normalise(it.name) == normalise(channel.name) }
    }

    /** One logo tile per channel: the programme on now as its title, with what's next in the preview. */
    private fun tile(channel: TvChannel?, guideChannel: GuideChannel?, guide: Guide?, now: Long, lookedUpLogo: String? = null): ContentItem {
        val name = channel?.name ?: guideChannel?.name.orEmpty()
        val number = channel?.number ?: guideChannel?.number.orEmpty()
        val current = guideChannel?.let { guide?.current(it.id, now) }
        val next = guideChannel?.let { guide?.next(it.id, now) }
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        val description = listOfNotNull(
            current?.let { p -> "Now: ${p.title}" + (if (p.start != null && p.end != null) " (${time.format(Date(p.start))} – ${time.format(Date(p.end))})" else "") },
            next?.let { p -> "Next: ${p.title}" + (p.start?.let { " at ${time.format(Date(it))}" } ?: "") },
            current?.description?.takeIf { it.isNotBlank() }).joinToString("\n")
        // TCL Channel stores logos with the TV; the antenna tuner does not, so those use the guide's logo.
        val guideLogo = guideChannel?.image?.takeIf { it.startsWith("https://") } ?: lookedUpLogo?.takeIf { it.isNotBlank() } ?: channel?.iconUrl
        val logo = if (channel?.tclChannel == true) channel.logoUri else guideLogo
        val source = if (channel?.tclChannel == true) "TCL Channel" else name
        return ContentItem(channel?.let { "tv:${it.id}" } ?: "guide:${guideChannel?.id}", id, current?.title ?: name,
            listOf(number, source).filter(String::isNotBlank).joinToString(" · "),
            description.ifBlank { null }, logo, logo,
            current?.start, current?.end, false, current?.progress(now), channel?.playbackUri,
            mapOf("channelName" to name, "channelId" to (channel?.let { "tv:${it.id}" } ?: guideChannel?.id.orEmpty()),
                "channelNumber" to number, "layout" to "channel", "logo" to "true",
                "fallbackArtwork" to guideLogo.orEmpty(), "category" to LiveChannelCategories.of(name, number, channel?.tclChannel == true),
                "playbackPackage" to "com.tcl.tv", "playbackFallback" to "nativeTV"))
    }
    private fun downloadGuide() {
        val temp = File(context.filesDir, "melbourne-download.xml")
        try {
            client.newCall(Request.Builder().url(SOURCE).header("User-Agent", "ChromecastLauncher/1.0 personal EPG (12-hour cache)").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Guide HTTP ${response.code}")
                val body = response.body ?: throw IOException("Guide body missing")
                body.byteStream().use { input -> temp.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024); var total = 0
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count; if (total > 12 * 1024 * 1024) throw IOException("Guide exceeds size limit")
                        output.write(buffer, 0, count)
                    }
                } }
            }
            guides.install(temp, System.currentTimeMillis())
            sourceError = null
        } catch (error: IOException) {
            sourceError = error.javaClass.simpleName + ": " + (error.message ?: "Guide unavailable")
        } catch (error: org.xml.sax.SAXException) {
            sourceError = "Guide could not be parsed"
        } catch (error: Exception) {
            sourceError = "Guide refresh failed: ${error.javaClass.simpleName}"
        } finally { temp.delete() }
    }
    private fun normalise(value: String) = value.lowercase().replace(REGION, "").replace(NON_ALPHANUMERIC, "")
    override suspend fun resolvePlayback(item: ContentItem): PlaybackResult = item.playbackUri?.let { PlaybackResult.Ready(it, "com.tcl.tv") } ?: PlaybackResult.App("com.tcl.tv", "No verified tuned-channel match; opening native TV")
    companion object {
        const val SOURCE = "https://xmltv.net/xml_files/Melbourne.xml"
        private const val GUIDE_INTERVAL_MILLIS = 12 * 60 * 60 * 1000L
        // Compiled once: matching a tuned channel to the guide normalises names for every channel pair on every refresh.
        private val REGION = Regex("\\b(melbourne|victoria|vic|hd)\\b")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]")
    }
}
