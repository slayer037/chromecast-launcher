package com.majendra.tvlauncher.providers

import com.majendra.tvlauncher.data.*
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.DateFormat
import java.util.Date

/** Exact Australian IDs; 507's More+ alias is independently matched to Foxtel's schedule. */
object KayoGuide {
    const val SOURCE = "https://www.open-epg.com/files/australia.xml.gz"
    const val CACHE_MILLIS = 24 * 60 * 60_000L
    const val MAX_COMPRESSED_BYTES = 4 * 1024 * 1024L
    const val MAX_XML_BYTES = 12 * 1024 * 1024L
    data class Channel(val id: String, val name: String, val guideId: String)
    val channels = listOf(
        Channel("FSN", "Fox Sports News", "FoxSportsNews.au"),
        Channel("FS1", "Fox Cricket", "FoxCricket.au"),
        Channel("SP2", "Fox League", "FoxLeague.au"),
        Channel("FS3", "Fox Sports 503", "FoxSports503.au"),
        Channel("FAF", "Fox Footy 504", "FoxFooty.au"),
        Channel("SPS", "Fox Sports 506", "FoxSports506.au"),
        Channel("FSS", "Fox Sports 507", "FoxSportsMorePlus.au"),
        Channel("ESP", "ESPN", "ESPN.au"),
        Channel("ES2", "ESPN2", "ESPN2.au")
    )

    fun result(guide: Guide?, now: Long, retrievedAt: Long, sourceError: String?): FeedResult {
        val current = channels.map { channel ->
            val programme = guide?.current(channel.guideId, now)
            if (programme == null) unavailable(channel) else item(channel, programme, guide, now, retrievedAt, true)
        }
        val next = channels.mapNotNull { channel ->
            guide?.next(channel.guideId, now)?.let { item(channel, it, guide, now, retrievedAt, false) }
        }
        val count = current.count { it.metadata["scheduleAvailable"] == "true" }
        val stale = retrievedAt <= 0L || now - retrievedAt >= CACHE_MILLIS
        val status = when {
            count == 0 -> FeedStatus.SOURCE_UNAVAILABLE
            stale || sourceError != null -> FeedStatus.CACHED
            else -> FeedStatus.FRESH
        }
        return FeedResult(
            listOf(ContentRow("kayo-now", "On now · channel guide", current)) +
                if (next.isEmpty()) emptyList() else listOf(ContentRow("kayo-next", "Next programmes", next)),
            status,
            "$count/${channels.size} current channel schedules · Open EPG. Listings describe scheduled programmes; cards open Kayo." +
                (if (stale && count > 0) " Using saved guide while updating." else "") +
                (sourceError?.let { " Refresh unavailable: $it." } ?: ""), now,
            if (count == 0) FeedError("kayo_guide_unavailable", sourceError ?: "No current matching schedule") else null
        )
    }

    private fun unavailable(channel: Channel) = ContentItem("kayo-channel-${channel.id}", "kayo", channel.name,
        subtitle = "Programme guide unavailable",
        description = "Open Kayo to see what is showing on ${channel.name}.", metadata = metadata(channel) +
            mapOf("scheduleAvailable" to "false", "scheduleOnly" to "true"))

    private fun metadata(channel: Channel) = mapOf("channelId" to channel.guideId,
        "channelName" to channel.name, "playbackPackage" to "au.com.kayosports.tv", "playbackFallback" to "app")

    private fun item(channel: Channel, p: GuideProgram, guide: Guide?, now: Long, retrievedAt: Long, current: Boolean): ContentItem {
        val format = DateFormat.getTimeInstance(DateFormat.SHORT)
        val interval = if (p.start != null && p.end != null) "${format.format(Date(p.start))} – ${format.format(Date(p.end))}" else "Time unavailable"
        val channelArt = guide?.channels?.firstOrNull { it.id == channel.guideId }?.image
        return ContentItem("kayo-${channel.id}:${p.start}", "kayo", p.title,
            "${channel.name} · $interval", p.description, p.image, p.image,
            p.start, p.end, false, if (current) p.progress(now) else null,
            metadata = metadata(channel) + mapOf("guideSource" to SOURCE, "guideRetrievedAt" to retrievedAt.toString(),
                "scheduleEvaluatedAt" to now.toString(), "guideCacheExpired" to (now - retrievedAt >= CACHE_MILLIS).toString(),
                "scheduleAvailable" to "true", "scheduleOnly" to "true", "onAir" to current.toString(),
                "eventLiveVerified" to "false", "fallbackArtwork" to channelArt.orEmpty(),
                "feedChannelName" to guide?.channels?.firstOrNull { it.id == channel.guideId }?.name.orEmpty()))
    }

    /** Bounds bytes actually read, including streams with missing or incorrect Content-Length. */
    class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = `in`.read().also { if (it >= 0) checked(1) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also {
            if (it > 0) checked(it)
        }
        private fun checked(amount: Int) { count += amount; if (count > limit) throw IOException("Guide exceeds size limit") }
    }
}
