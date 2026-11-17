package com.majendra.tvlauncher.providers

import android.content.Context
import android.util.AtomicFile
import com.majendra.tvlauncher.data.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject

/** Public catalogue metadata only. Classification is checked again at playback. */
class KidsCornerProvider(private val context: Context) : FeedAdapter {
    override val id = "kids"
    override val displayName = "Kids Corner"
    // The folder and the client are made by the first load, which runs off the main thread; construction does no I/O.
    private val cache by lazy { File(context.filesDir, "kids-public-metadata").apply { mkdirs() } }
    private val client by lazy { LauncherHttp.client.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build() }
    private val permits = Semaphore(4)
    private val networkReads = java.util.concurrent.atomic.AtomicInteger()
    override suspend fun isAvailable() = true
    override suspend fun load(forceRefresh: Boolean) = loadProgressively(forceRefresh) {}

    override suspend fun loadProgressively(forceRefresh: Boolean,
        onPartial: suspend (FeedResult) -> Unit): FeedResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val initialNetworkReads = networkReads.get()
            val updates = kotlinx.coroutines.channels.Channel<Pair<List<ContentRow>, Boolean>>(4)
            val jobs = listOf<suspend () -> List<ContentRow>>(
                { if (context.liveTvEnabled) listOf(abcLive(forceRefresh).let { it.copy(items = it.items + tvChannels()) }) else emptyList() },
                { listOf(ContentRow("kids-movies", "🎬  Movies", movies(forceRefresh))) },
                { listOf(ContentRow("kids-series", "⭐  Shows", series(forceRefresh))) },
                { abcEpisodes(forceRefresh) }
            ).map { task -> launch {
                try { updates.send(task() to false) }
                catch (_: IOException) { updates.send(emptyList<ContentRow>() to true) }
                catch (_: org.json.JSONException) { updates.send(emptyList<ContentRow>() to true) }
            } }
            launch { jobs.joinAll(); updates.close() }
            val rows = linkedMapOf<String, ContentRow>()
            var failures = 0
            fun result() = FeedResult(
                rows = preferredRows(rows),
                status = if (rows.values.any { it.items.isNotEmpty() }) {
                    if (networkReads.get() > initialNetworkReads) FeedStatus.FRESH else FeedStatus.CACHED
                }
                    else if (failures > 0) FeedStatus.SOURCE_UNAVAILABLE else FeedStatus.NO_CONTENT,
                message = "Australian G-rated selections. Unrated titles are excluded." +
                    if (failures > 0) " Some kids catalogues are temporarily unavailable." else "",
                error = if (failures > 0) FeedError("kids_source_unavailable", "Some catalogue metadata could not be loaded.") else null)
            for ((loaded, failed) in updates) {
                loaded.forEach { rows[it.id] = it }
                if (failed) failures++
                onPartial(result())
            }
            result()
        }
    }

    private suspend fun movies(force: Boolean): List<ContentItem> = coroutineScope {
        KidsFilmSeeds.all.map { seed -> async {
            try {
                val meta = json("https://v3-cinemeta.strem.io/meta/movie/${seed.imdbId}.json", HOUR, force)
                    .optJSONObject("meta") ?: return@async null
                if (!KidsClassificationPolicy.matchesFilm(seed, meta.optString("id"), meta.optString("name"),
                        meta.optString("releaseInfo", meta.optString("year"))) || meta.optString("type") != "movie") return@async null
                val score = meta.optString("imdbRating").toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
                ContentItem(seed.imdbId, id, meta.optString("name"),
                    listOfNotNull("${seed.year} · G", score?.let { "IMDb $it" }).joinToString(" · "),
                    meta.optString("description").takeIf(String::isNotBlank),
                    safeImage(meta.optString("poster")), safeImage(meta.optString("background")),
                    playbackUri = "stremio:///detail/movie/${seed.imdbId}/${seed.imdbId}",
                    metadata = buildMap {
                        putAll(classification("https://www.classification.gov.au/titles/${seed.classificationSource}"))
                        put("type", "movie"); put("playbackPackage", STREMIO); put("package", STREMIO)
                        put("pickerEligible", "true"); put("stremioId", seed.imdbId); put("year", seed.year.toString())
                        put("kidsGroupId", "imdb:${seed.imdbId}")
                        score?.let { put("imdbRating", it.toString()) }
                    })
            } catch (_: IOException) { null } catch (_: org.json.JSONException) { null }
        } }.awaitAll().filterNotNull()
    }

    private suspend fun series(force: Boolean): List<ContentItem> = coroutineScope {
        KidsSeriesSeeds.all.map { seed -> async {
            try {
                val meta = json("https://v3-cinemeta.strem.io/meta/series/${seed.imdbId}.json", HOUR, force)
                    .optJSONObject("meta") ?: return@async null
                if (meta.optString("type") != "series" || !KidsClassificationPolicy.matchesSeries(seed,
                        meta.optString("id"), meta.optString("name"), meta.optString("releaseInfo", meta.optString("year"))))
                    return@async null
                val score = meta.optString("imdbRating").toDoubleOrNull()?.takeIf { it in 0.0..10.0 }
                ContentItem(seed.imdbId, id, meta.optString("name"),
                    listOfNotNull("Stremio · G", score?.let { "IMDb $it" }).joinToString(" · "),
                    meta.optString("description").takeIf(String::isNotBlank), safeImage(meta.optString("poster")),
                    safeImage(meta.optString("background")), playbackUri = "stremio:///detail/series/${seed.imdbId}",
                    metadata = classification("https://www.stan.com.au/watch/${seed.classificationSource}") + mapOf(
                        "type" to "series", "playbackPackage" to STREMIO, "package" to STREMIO,
                        "pickerEligible" to "false", "stremioId" to seed.imdbId, "year" to seed.year.toString(),
                        "kidsGroupId" to "imdb:${seed.imdbId}", "classificationScope" to "Australian distributor series listing") +
                        (score?.let { mapOf("imdbRating" to it.toString()) } ?: emptyMap()))
            } catch (_: IOException) { null } catch (_: org.json.JSONException) { null }
        } }.awaitAll().filterNotNull()
    }

    /** Preserve one show card across services and prefer the independently classified ABC episode. */
    private fun preferredRows(rows: Map<String, ContentRow>): List<ContentRow> {
        val abc = rows["kids-abc-episodes"]?.items.orEmpty().associateBy { it.metadata["kidsGroupId"] }
        // ABC plays the show, but keeps the series poster: its own art is a landscape still that crops badly.
        val tv = rows["kids-series"]?.let { row -> row.copy(items = row.items.map { item ->
            abc[item.metadata["kidsGroupId"]]?.let { it.copy(imageUrl = item.imageUrl ?: it.imageUrl) } ?: item
        }) }
        val shownGroups = tv?.items.orEmpty().mapNotNull { it.metadata["kidsGroupId"] }.toSet()
        val extraAbc = rows["kids-abc-episodes"]?.let { row -> row.copy(items = row.items
            .filter { it.metadata["kidsGroupId"] !in shownGroups }.distinctBy { it.metadata["kidsGroupId"] }) }
        return listOfNotNull(rows["kids-movies"], tv, rows["kids-abc-live"], extraAbc, rows["kids-abc-films"])
    }

    private suspend fun abcEpisodes(force: Boolean): List<ContentRow> = coroutineScope {
        val directory = json("$ABC/collection/1962", HOUR, force).optJSONArray("items")
        val items = (0 until (directory?.length() ?: 0)).mapNotNull { directory?.optJSONObject(it) }
        val films = items.filter { it.optString("type") == "feature" && it.optString("classification") == "G" }
            .take(8).map { feature -> async {
                val videoId = feature.optString("houseNumber", feature.optString("id"))
                    .takeIf { VIDEO.matches(it) } ?: return@async null
                try { abcItem(json("$ABC/video/$videoId", HOUR, force), "feature") }
                catch (_: IOException) { null } catch (_: org.json.JSONException) { null }
            } }.awaitAll().filterNotNull()
        val preferredSlugs = (listOf("bluey") + KidsSeriesSeeds.all.map { it.abcSlug }).toSet()
        fun slug(show: JSONObject) = show.optJSONObject("_links")?.optJSONObject("self")?.optString("href")
            ?.removePrefix("/show/").orEmpty()
        val episodes = items.filter { it.optString("type") == "series" }
            .sortedBy { when { slug(it) == "bluey" -> 0; slug(it) in preferredSlugs -> 1; else -> 2 } }
            .take(12).map { show -> async {
            val videoId = show.optJSONObject("_links")?.optJSONObject("highlightVideo")?.optString("id")
                ?.takeIf { VIDEO.matches(it) } ?: return@async null
            try { abcItem(json("$ABC/video/$videoId", HOUR, force), "episode", slug(show)) }
            catch (_: IOException) { null } catch (_: org.json.JSONException) { null }
        } }.awaitAll().filterNotNull()
        listOf(ContentRow("kids-abc-episodes", "🐨  ABC shows", episodes),
            ContentRow("kids-abc-films", "🎈  ABC specials", films))
    }

    private fun abcItem(meta: JSONObject, type: String, showSlug: String? = null): ContentItem? {
        val now = System.currentTimeMillis()
        val videoId = meta.optString("houseNumber", meta.optString("id"))
        if (!VIDEO.matches(videoId)) return null
        val expiry = parseAbcTime(meta.optString("expireDate"))
        if (!KidsClassificationPolicy.eligibleEpisode(meta.optString("classification"), expiry, now)) return null
        val title = meta.optString("displayTitle", meta.optString("showTitle", meta.optString("title")))
        if (title.isBlank()) return null
        val uri = "https://iview.abc.net.au/video/$videoId"
        val group = if (type == "episode" && !showSlug.isNullOrBlank()) KidsClassificationPolicy.groupForAbcShow(showSlug)
            else "abc-video:$videoId"
        return ContentItem("abc:$videoId", id, title,
            listOf("ABC iview · G", meta.optString("displaySubtitle")).filter(String::isNotBlank).joinToString(" · "),
            meta.optString("description").takeIf(String::isNotBlank), image(meta, "portrait"), image(meta, "thumb"),
            playbackUri = uri, metadata = classification("$ABC/video/$videoId") + mapOf(
                "type" to type, "abcVideoId" to videoId, "playbackPackage" to ABC_PACKAGE,
                "package" to ABC_PACKAGE, "pickerEligible" to "false", "expiresAt" to expiry.toString(), "kidsGroupId" to group))
    }

    private suspend fun abcLive(force: Boolean): ContentRow {
        val now = System.currentTimeMillis()
        var upNext: String? = null
        val state = try {
            var schedule = json("$ABC/nownext/$KIDS_LIVE", 60_000, force)
            var state = KidsClassificationPolicy.liveState(liveCandidates(schedule), now)
            // A cached schedule goes stale at each programme change; recheck before calling it unavailable.
            if (state !is KidsLiveState.Verified && !force) {
                schedule = json("$ABC/nownext/$KIDS_LIVE", 60_000, true)
                state = KidsClassificationPolicy.liveState(liveCandidates(schedule), now)
            }
            upNext = upNext(schedule, now)
            state
        } catch (_: IOException) {
            KidsLiveState.Unavailable("Can't reach ABC iview", "Kids Corner couldn't load the ABC Kids schedule. Press OK to try again.")
        } catch (_: org.json.JSONException) {
            KidsLiveState.Unavailable("Schedule unavailable", "The ABC Kids schedule couldn't be read. Press OK to try again.")
        }
        val base = mapOf("type" to "channel", "abcVideoId" to KIDS_LIVE, "channelName" to "ABC Kids",
            "playbackPackage" to ABC_PACKAGE, "package" to ABC_PACKAGE, "pickerEligible" to "false")
        val item = when (state) {
            is KidsLiveState.Verified -> state.programme.let { p ->
                ContentItem("abc:$KIDS_LIVE", id, p.title,
                    listOf("Live now · G", p.subtitle).filter(String::isNotBlank).joinToString(" · "),
                    p.description, LIVE_ART, LIVE_ART, startTime = p.start, endTime = p.end, isLive = true,
                    progress = ((now - p.start).toDouble() / (p.end - p.start)).toFloat().coerceIn(0f, 1f),
                    playbackUri = "https://iview.abc.net.au/video/$KIDS_LIVE",
                    metadata = classification("$ABC/nownext/$KIDS_LIVE") + base +
                        (upNext?.let { mapOf("upNext" to it) } ?: emptyMap()))
            }
            is KidsLiveState.Unavailable -> ContentItem("abc:$KIDS_LIVE", id, "ABC Kids Live",
                "${state.headline} · OK to retry", state.reason, LIVE_ART, LIVE_ART,
                metadata = base + mapOf("kidsLiveState" to "unavailable", "kidsLiveReason" to state.reason))
        }
        return ContentRow("kids-abc-live", "📺  Watch TV now", listOf(item))
    }

    /** Hand-picked TCL Channel streams beside ABC Kids, opened in the TV app like Live TV. Missing channels are skipped. */
    private fun tvChannels(): List<ContentItem> {
        val logos = runCatching { ChannelLogoLookup.saved(context) }.getOrDefault(emptyMap())
        return KidsTvChannels.pick(TvChannelSource.channels(context)).map { ch ->
            val fallback = logos[ch.name]?.takeIf { it.isNotBlank() } ?: ch.iconUrl
            ContentItem("kids-tv:${ch.id}", id, ch.name, "Live · TCL Channel", null, ch.logoUri, ch.logoUri, isLive = true,
                playbackUri = ch.playbackUri, metadata = mapOf("type" to "channel", "kidsChannel" to "tcl", "channelName" to ch.name,
                    "channelNumber" to ch.number, "logo" to "true", "fallbackArtwork" to fallback.orEmpty(),
                    "playbackPackage" to TV_PACKAGE, "package" to TV_PACKAGE, "playbackFallback" to "nativeTV", "pickerEligible" to "false"))
        }
    }

    private fun liveCandidates(schedule: JSONObject) = listOf("now", "next").mapNotNull(schedule::optJSONObject).map {
        LiveProgramme(it.optString("displayTitle"), it.optString("displaySubtitle"), it.optString("description"),
            it.optString("classification").takeIf(String::isNotBlank), it.optLong("start") * 1000, it.optLong("end") * 1000)
    }

    /** Title and start time only: the next programme's classification is never shown as verified. */
    private fun upNext(schedule: JSONObject, now: Long): String? {
        val next = schedule.optJSONObject("next") ?: return null
        val title = next.optString("displayTitle").takeIf(String::isNotBlank) ?: return null
        val start = next.optLong("start") * 1000
        if (start <= now) return null
        val time = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(start)).lowercase()
        return "Up next: $title at $time"
    }

    override suspend fun resolvePlayback(item: ContentItem): PlaybackResult {
        if (!LiveTvPolicy.itemAllowed(context.liveTvEnabled,item)) return PlaybackResult.Unavailable("Live TV is unavailable on this device.")
        if (item.source == id && item.metadata["kidsChannel"] == "tcl")
            return item.playbackUri?.takeIf { KidsTvChannels.isChannelUri(it) }?.let { PlaybackResult.Ready(it, TV_PACKAGE) }
                ?: PlaybackResult.Unavailable("This channel isn't available right now.")
        if (item.source == id && item.metadata["kidsLiveState"] == "unavailable")
            return PlaybackResult.Unavailable(item.metadata["kidsLiveReason"] ?: "ABC Kids Live isn't available right now.")
        if (item.source != id || !KidsClassificationPolicy.isAustralianG(item.metadata["classification"],
                item.metadata["classificationCountry"])) return PlaybackResult.Unavailable("Only verified Australian G-rated titles are available here.")
        if (item.metadata["playbackPackage"] == STREMIO) {
            if (item.metadata["type"] == "series") {
                val seed = KidsSeriesSeeds.all.firstOrNull { it.imdbId == item.metadata["stremioId"] }
                    ?: return PlaybackResult.Unavailable("This show has no verified Australian classification.")
                if (!KidsClassificationPolicy.matchesSeries(seed, item.id, item.title, item.metadata["year"].orEmpty()))
                    return PlaybackResult.Unavailable("This show does not match its classification record.")
                return PlaybackResult.Ready("stremio:///detail/series/${seed.imdbId}", STREMIO)
            }
            val seed = KidsFilmSeeds.all.firstOrNull { it.imdbId == item.metadata["stremioId"] }
                ?: return PlaybackResult.Unavailable("This movie has no verified Australian classification.")
            if (!KidsClassificationPolicy.matchesFilm(seed, item.id, item.title, item.metadata["year"].orEmpty()))
                return PlaybackResult.Unavailable("This movie does not match its classification record.")
            val suffix = if (item.metadata["autoPlay"] == "true") "?autoPlay=true" else ""
            return PlaybackResult.Ready("stremio:///detail/movie/${seed.imdbId}/${seed.imdbId}$suffix", STREMIO)
        }
        val videoId = item.metadata["abcVideoId"]?.takeIf { VIDEO.matches(it) }
            ?: return PlaybackResult.Unavailable("This episode is unavailable.")
        val now = System.currentTimeMillis()
        if (item.metadata["type"] == "channel") {
            if (!KidsClassificationPolicy.currentProgramme("G", item.startTime ?: 0, item.endTime ?: 0, now))
                return PlaybackResult.Unavailable("The ABC Kids programme has changed. Refresh Kids Corner first.")
        } else if (!KidsClassificationPolicy.eligibleEpisode("G", item.metadata["expiresAt"]?.toLongOrNull(), now))
            return PlaybackResult.Unavailable("This ABC iview episode is no longer available.")
        return PlaybackResult.Ready("https://iview.abc.net.au/video/$videoId", ABC_PACKAGE)
    }

    private suspend fun json(url: String, ttl: Long, force: Boolean): JSONObject = permits.withPermit {
        val key = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(cache, "$key.json")
        val age = System.currentTimeMillis() - file.lastModified()
        if (!force && file.isFile && file.length() <= MAX_BYTES && age in 0 until ttl)
            return@withPermit JSONObject(file.readText())
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Kids metadata HTTP ${response.code}")
            val body = response.body ?: throw IOException("Missing metadata")
            val source = body.source()
            if (source.request(MAX_BYTES + 1)) throw IOException("Metadata too large")
            val text = source.readUtf8(); val parsed = JSONObject(text)
            networkReads.incrementAndGet()
            val atomic = AtomicFile(file); val stream = atomic.startWrite()
            try { stream.write(text.toByteArray()); atomic.finishWrite(stream) }
            catch (e: IOException) { atomic.failWrite(stream); throw e }
            parsed
        }
    }

    private fun image(meta: JSONObject, type: String): String? {
        val images = meta.optJSONArray("images") ?: return null
        val candidates = (0 until images.length()).mapNotNull(images::optJSONObject)
        return candidates.sortedBy { if (it.optString("type") == type) 0 else 1 }
            .firstNotNullOfOrNull { safeImage(it.optString("url")) }
    }
    private fun safeImage(value: String): String? {
        val u = value.toHttpUrlOrNull() ?: return null
        val allowed = setOf("images.metahub.space", "cdn.iview.abc.net.au", "image.tmdb.org")
        return value.takeIf { u.scheme == "https" && u.host in allowed && u.username.isEmpty() && u.password.isEmpty() }
    }
    private fun parseAbcTime(value: String): Long? = runCatching {
        LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(ZoneId.of("Australia/Sydney")).toInstant().toEpochMilli()
    }.getOrNull()
    private fun classification(source: String) = mapOf("classification" to "G", "classificationCountry" to "AU",
        "classificationSourceURL" to source)
    companion object {
        private const val HOUR = 3_600_000L
        private const val MAX_BYTES = 2_000_000L
        private const val STREMIO = "com.stremio.one"
        private const val ABC_PACKAGE = "au.net.abc.iview"
        private const val TV_PACKAGE = "com.tcl.tv"
        private const val ABC = "https://api.iview.abc.net.au/v3"
        private const val KIDS_LIVE = "LS1604H001S00"
        private const val LIVE_ART = "https://cdn.iview.abc.net.au/thumbs/i/abckids-wide.png"
        private val VIDEO = Regex("[A-Z0-9]{8,24}")
    }
}

/** The TCL Channel streams Kids Corner shows next to ABC Kids, in this order, matched by name. */
internal object KidsTvChannels {
    val names = listOf("Go Wild", "Terra Mater Wild", "PBS Kids", "MythBusters")
    private fun key(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")

    /** One TCL Channel stream per name: an exact name match first, else the shortest name containing it. */
    fun pick(channels: List<TvChannel>): List<TvChannel> = channels.filter { it.tclChannel }.let { tcl ->
        names.mapNotNull { wanted -> key(wanted).let { k ->
            tcl.firstOrNull { key(it.name) == k } ?: tcl.filter { key(it.name).contains(k) }.minByOrNull { it.name.length }
        } }.distinctBy { it.id }
    }

    fun isChannelUri(uri: String) = uri.startsWith("content://android.media.tv/channel/")
}
