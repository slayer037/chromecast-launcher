package com.majendra.tvlauncher.sync

import com.majendra.tvlauncher.auth.StremioLibraryEntry
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.providers.StremioEpisode
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.Inflater

internal data class StremioProgressImport(val key: String, val season: Int, val episode: Int, val historyUpdatedAt: Long)

/** Read-only Stremio interpretation. Resume pointers are never proof of a completed series. */
internal object StremioHistoryPolicy {
    const val MOVIES_ROW = "movies-continue-watching"
    const val MAX_VIDEOS = 8192
    private val imdb = Regex("tt[0-9]{1,12}")
    private val episodeOrder = compareBy<StremioEpisode>({ it.season ?: Int.MIN_VALUE },
        { it.episode ?: Int.MIN_VALUE }, { it.released ?: Long.MIN_VALUE }, { it.id })

    fun episodes(meta: JSONObject): List<StremioEpisode> {
        val array = meta.optJSONArray("videos") ?: return emptyList()
        if (array.length() > MAX_VIDEOS) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val video = array.optJSONObject(index) ?: return@mapNotNull null
            val id = video.optString("id").takeIf { it.isNotBlank() && it.length <= 256 } ?: return@mapNotNull null
            fun number(name: String): Int? = video.opt(name)?.takeIf { it is Number && it.toDouble() == it.toInt().toDouble() }?.let { (it as Number).toInt() }
            val released = runCatching { Instant.parse(video.optString("released")).toEpochMilli() }.getOrNull()
            StremioEpisode(id, number("season"), number("episode"), released)
        }.distinctBy { it.id }.sortedWith(episodeOrder)
    }

    fun completedMovies(entries: List<StremioLibraryEntry>): List<ContentItem> = entries.asSequence()
        .filter { it.type == "movie" && eligible(it) && it.timesWatched > 0 }.distinctBy { it.id }.take(WatchedPolicy.MAX_ENTRIES)
        .map { movie(it, false) }.toList()

    fun continueMovies(entries: List<StremioLibraryEntry>): List<ContentItem> = entries.asSequence()
        .filter { it.type == "movie" && eligible(it) && !it.flaggedWatched && it.timeOffset > 0 &&
            (it.duration <= 0 || it.timeOffset < it.duration) }
        .sortedByDescending { it.lastWatched }.distinctBy { it.id }.take(40).map { movie(it, true) }.toList()

    private fun eligible(entry: StremioLibraryEntry) = imdb.matches(entry.id) && entry.name.isNotBlank() &&
        (!entry.removed || entry.temporary)

    private fun movie(entry: StremioLibraryEntry, resume: Boolean): ContentItem {
        val progress = if (resume && entry.duration > 0) (entry.timeOffset.toDouble() / entry.duration).coerceIn(0.0, 1.0).toFloat() else null
        return ContentItem(entry.id, "stremio", entry.name.take(200),
            subtitle = if (!resume) null else progress?.let { "${(it * 100).toInt()}% watched" } ?: "Resume in Stremio",
            imageUrl = WatchedPolicy.publicPoster(entry.poster) ?: "https://images.metahub.space/poster/medium/${entry.id}/img",
            progress = progress, playbackUri = "stremio:///detail/movie/${entry.id}/${entry.id}",
            metadata = mapOf("type" to "movie", "package" to "com.stremio.one", "accountAuthorised" to "true",
                "videoId" to entry.id, "stremioResume" to resume.toString()))
    }

    fun progress(entry: StremioLibraryEntry, unsorted: List<StremioEpisode>, nowMillis: Long = System.currentTimeMillis()): StremioProgressImport? {
        if (entry.type != "series" || !eligible(entry) || entry.modified <= 0 || entry.modified > nowMillis + 300_000 || unsorted.size > MAX_VIDEOS) return null
        val videos = unsorted.distinctBy { it.id }.sortedWith(episodeOrder)
        val watched = watchedIndices(entry.watched, videos)
        val active = videos.indexOfFirst { it.id == entry.videoId }
        val unfinished = active >= 0 && entry.timeOffset > 0 && !entry.flaggedWatched &&
            (entry.duration <= 0 || entry.timeOffset < entry.duration) && (watched == null || active !in watched)
        val next = if (unfinished) videos[active] else {
            val completed = watched?.maxOrNull() ?: return null
            videos.drop(completed + 1).firstOrNull(::addressable) ?: return null
        }
        if (!addressable(next)) return null
        return StremioProgressImport("series:${entry.id}", next.season!!, next.episode!!, entry.modified)
    }

    private fun addressable(video: StremioEpisode): Boolean {
        val season = video.season ?: return false
        val episode = video.episode ?: return false
        return season in 0..99 && episode in 1..999
    }

    /** Official Stremio anchor-aligned, LSB-first zlib bitfield. Invalid data provides no completion evidence. */
    internal fun watchedIndices(value: String?, videos: List<StremioEpisode>): Set<Int>? = runCatching {
        require(value != null && value.length <= 131_072 && videos.size <= MAX_VIDEOS)
        val encodedAt = value.lastIndexOf(':'); require(encodedAt > 0)
        val lengthAt = value.lastIndexOf(':', encodedAt - 1); require(lengthAt > 0)
        val anchor = value.substring(0, lengthAt)
        val length = value.substring(lengthAt + 1, encodedAt).toInt(); require(length in 1..524288)
        val anchorAt = videos.indexOfFirst { it.id == anchor }; require(anchorAt >= 0)
        val compressed = Base64.getDecoder().decode(value.substring(encodedAt + 1)); require(compressed.size <= 65536)
        val inflater = Inflater()
        val bytes = try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                require(count > 0 && output.size() + count <= 65536)
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally { inflater.end() }
        val offset = length - anchorAt - 1
        videos.indices.filterTo(linkedSetOf()) { index ->
            val bit = index + offset
            bit >= 0 && bit < length && bit / 8 < bytes.size && ((bytes[bit / 8].toInt() ushr (bit % 8)) and 1) == 1
        }
    }.getOrNull()
}
