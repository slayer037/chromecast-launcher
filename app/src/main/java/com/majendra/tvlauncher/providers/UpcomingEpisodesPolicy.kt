package com.majendra.tvlauncher.providers

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.sync.WatchingEntry
import com.majendra.tvlauncher.sync.WatchingPolicy
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class UpcomingRelease(val instant: Instant? = null, val date: LocalDate? = null) {
    fun future(now: Instant): Boolean = instant?.isAfter(now) ?: (date?.let { !it.isBefore(now.atZone(UpcomingEpisodesPolicy.SYDNEY).toLocalDate()) } ?: false)
    fun order(): Instant = instant ?: requireNotNull(date).atStartOfDay(UpcomingEpisodesPolicy.SYDNEY).toInstant()
    fun label(): String = instant?.atZone(UpcomingEpisodesPolicy.SYDNEY)?.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a z", Locale.ENGLISH))
        ?: requireNotNull(date).format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)) + " · time unknown"
}

internal data class UpcomingEpisode(val id: String, val season: Int, val episode: Int, val release: UpcomingRelease)

/** Uses only dates actually supplied by public metadata. Never derives watched progress or a release cadence. */
internal object UpcomingEpisodesPolicy {
    const val ROW_ID = "upcoming-episodes"
    val SYDNEY: ZoneId = ZoneId.of("Australia/Sydney")
    private val order = compareBy<UpcomingEpisode>({ it.release.order() }, { it.season }, { it.episode }, { it.id })

    fun release(value: String): UpcomingRelease? = try {
        if (Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) {
            LocalDate.parse(value).takeIf { it.year in 1900..2200 }?.let { UpcomingRelease(date = it) }
        } else {
            val timestamp = OffsetDateTime.parse(value)
            val instant = timestamp.toInstant()
            if (timestamp.year !in 1900..2200 || instant.atZone(SYDNEY).year !in 1900..2200) null
            else UpcomingRelease(instant = instant)
        }
    } catch (_: java.time.DateTimeException) { null }

    fun episodes(meta: JSONObject): List<UpcomingEpisode> {
        val array = meta.optJSONArray("videos") ?: return emptyList()
        if (array.length() > 8192) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val video = array.optJSONObject(index) ?: return@mapNotNull null
            fun number(key: String): Int? = (video.opt(key) as? Number)?.let {
                it.toInt().takeIf { number -> it.toDouble() == number.toDouble() }
            }
            val season = number("season")?.takeIf { it in 0..99 } ?: return@mapNotNull null
            val episode = number("episode")?.takeIf { it in 1..999 } ?: return@mapNotNull null
            val id = (video.opt("id") as? String)?.takeIf { it.length in 1..256 && it.none(Char::isISOControl) } ?: return@mapNotNull null
            val released = release((video.opt("released") as? String).orEmpty()) ?: return@mapNotNull null
            UpcomingEpisode(id, season, episode, released)
        }.distinctBy { it.id }.sortedWith(order)
    }

    fun next(episodes: List<UpcomingEpisode>, now: Instant): UpcomingEpisode? = episodes.filter { it.release.future(now) }.minWithOrNull(order)

    fun item(entry: WatchingEntry, episode: UpcomingEpisode): ContentItem = WatchingPolicy.item(entry).copy(
        subtitle = "S${episode.season} E${episode.episode} · ${episode.release.label()}",
        metadata = WatchingPolicy.item(entry).metadata.filterKeys { it != "nextSeason" && it != "nextEpisode" } + mapOf(
            "upcomingSeason" to episode.season.toString(), "upcomingEpisode" to episode.episode.toString(),
            "releasePrecision" to if (episode.release.instant != null) "datetime" else "date",
            "releaseSource" to "Cinemeta"))
}
