package com.majendra.tvlauncher.sync

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.data.FeedResult
import org.json.JSONArray
import org.json.JSONObject

data class WatchingEntry(val key: String, val watching: Boolean, val revision: Long = 0,
    val title: String? = null, val year: String? = null, val poster: String? = null,
    val progress: EpisodeProgress? = null, val progressMode: String = "stremio", val progressSource: String? = null)
data class WatchingSnapshot(val revision: Long = 0, val entries: List<WatchingEntry> = emptyList(), val updatedAt: Long = 0)

/** Watching is a distinct explicit choice; it is not inferred from watched or playback history. */
object WatchingPolicy {
    const val ROW_ID = "watching-shows"
    const val MAX_ENTRIES = 1000
    const val MAX_PENDING = 1000
    fun active(snapshot: WatchingSnapshot): List<WatchingEntry> = snapshot.entries.filter { it.watching }
    fun accept(current: WatchingSnapshot, reply: WatchingSnapshot, at: Long): WatchingSnapshot =
        if (reply.revision < current.revision) current else reply.copy(updatedAt = at)
    fun effective(snapshot: WatchingSnapshot, pending: List<WatchingOperation>): WatchingSnapshot {
        val entries = snapshot.entries.toMutableList()
        pending.forEach { patch ->
            val index = entries.indexOfFirst { it.key == patch.key }
            val old = entries.getOrNull(index)
            if (old == null && patch.watching == null) return@forEach
            val value = (old ?: WatchingEntry(patch.key, false)).copy(
                watching = patch.watching ?: old?.watching ?: false,
                progress = if (patch.progressSet) patch.progress else old?.progress,
                title = patch.title ?: old?.title, year = patch.year ?: old?.year, poster = patch.poster ?: old?.poster,
                progressMode = if (patch.progressSet) "manual" else patch.progressMode ?: old?.progressMode ?: "stremio",
                progressSource = if (patch.progressSet) patch.progress?.let { "manual" } else old?.progressSource)
            if (index >= 0) entries.removeAt(index)
            if (patch.watching == true && old?.watching != true) entries.add(0, value)
            else if (index >= 0) entries.add(index, value) else entries.add(value)
        }
        return snapshot.copy(entries = entries)
    }
    fun merge(snapshot: WatchingSnapshot, pending: List<WatchingOperation>, reply: WatchingExchange,
        sentIds: Set<String>, at: Long): WatchingMerge {
        require(reply.acknowledged.all { it in sentIds })
        return WatchingMerge(accept(snapshot, reply.snapshot, at), pending.filterNot { it.id in reply.acknowledged })
    }
    fun item(entry: WatchingEntry): ContentItem {
        require(entry.watching && entry.key.startsWith("series:") && WatchedPolicy.validKey(entry.key) && !entry.title.isNullOrBlank())
        val id = entry.key.removePrefix("series:")
        val poster = WatchedPolicy.publicPoster(entry.poster) ?: "https://images.metahub.space/poster/medium/$id/img"
        val subtitle = listOfNotNull(entry.progress?.let { "Up next ${it.label()}" }, entry.year).joinToString(" · ").takeIf(String::isNotBlank)
        return ContentItem(id, "stremio", entry.title, subtitle = subtitle, imageUrl = poster,
            playbackUri = "stremio:///detail/series/$id", metadata = buildMap {
                put("type", "series"); put("package", "com.stremio.one"); put("watching", "true")
                entry.year?.let { put("year", it) }
                entry.progress?.let { put("nextSeason", it.season.toString()); put("nextEpisode", it.episode.toString()) }
            })
    }
    /** Apply after ordinary Seen filtering so an explicitly Watching show stays reachable. */
    fun prepend(service: String, result: FeedResult, snapshot: WatchingSnapshot): FeedResult {
        if (service != "series") return result
        val rest = result.rows.filterNot { it.id == ROW_ID }
        val cards = active(snapshot).map(::item)
        return result.copy(rows = if (cards.isEmpty()) rest else listOf(ContentRow(ROW_ID, "Watching", cards)) + rest)
    }
}

internal object WatchingCodec {
    private fun progress(json: JSONObject): EpisodeProgress {
        val season = nonnegativeLong(json, "season"); val episode = nonnegativeLong(json, "episode")
        require(season <= 99 && episode in 1..999)
        return EpisodeProgress(season.toInt(), episode.toInt())
    }
    private fun progress(value: EpisodeProgress): JSONObject = JSONObject().put("season", value.season).put("episode", value.episode)
    fun reply(json: JSONObject): WatchingSnapshot {
        require(json.getInt("version") == 1)
        val revision = nonnegativeLong(json, "revision")
        val array = json.getJSONArray("entries")
        require(array.length() <= WatchingPolicy.MAX_ENTRIES)
        val entries = (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val key = row.getString("key")
            require(key.startsWith("series:") && WatchedPolicy.validKey(key))
            val watching = row.get("watching").also { require(it is Boolean) } as Boolean
            val itemRevision = nonnegativeLong(row, "revision").also { require(it <= revision) }
            fun text(name: String, limit: Int): String? = if (!row.has(name) || row.isNull(name)) null else
                row.getString(name).also { require(it.length <= limit && it.none(Char::isISOControl)) }.takeIf(String::isNotBlank)
            val title = text("title", 200)
            require(!watching || title != null)
            val progress = if (row.has("progress") && !row.isNull("progress")) progress(row.getJSONObject("progress")) else null
            val mode = row.optString("progressMode", "stremio").also { require(it in setOf("manual", "stremio")) }
            val source = text("progressSource", 20)?.also { require(it in setOf("manual", "stremio")) }
            WatchingEntry(key, watching, itemRevision, title, text("year", 40), text("poster", 1024)?.let(WatchedPolicy::publicPoster), progress, mode, source)
        }
        require(entries.map { it.key }.distinct().size == entries.size)
        return WatchingSnapshot(revision, entries)
    }
    fun exchange(json: JSONObject): WatchingExchange {
        val array = json.getJSONArray("acknowledged"); require(array.length() <= 200)
        val ids = (0 until array.length()).map { array.getString(it).also { id -> require(java.util.UUID.fromString(id).toString() == id) } }
        require(ids.distinct().size == ids.size)
        return WatchingExchange(reply(json), ids.toSet())
    }
    fun operation(op: WatchingOperation): JSONObject = JSONObject().put("id", op.id).put("key", op.key).apply {
        op.watching?.let { put("watching", it) }
        if (op.progressSet) put("progress", op.progress?.let(::progress) ?: JSONObject.NULL)
        op.progressMode?.let { put("progressMode", it) }
        op.title?.let { put("title", it) }; op.year?.let { put("year", it) }; op.poster?.let { put("poster", it) }
    }
    fun pending(array: JSONArray): List<WatchingOperation> {
        require(array.length() <= WatchingPolicy.MAX_PENDING)
        val operations = (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            val watching = if (row.has("watching")) row.get("watching").also { require(it is Boolean) } as Boolean else null
            val hasProgress = row.has("progress")
            val next = if (hasProgress && !row.isNull("progress")) progress(row.getJSONObject("progress")) else null
            fun text(name: String): String? = if (row.has(name) && !row.isNull(name)) row.getString(name) else null
            WatchingOperation(row.getString("id"), row.getString("key"), watching, hasProgress, next,
                text("title"), text("year"), text("poster"), text("progressMode"))
        }
        require(operations.map { it.id }.distinct().size == operations.size)
        return operations
    }
    private fun nonnegativeLong(json: JSONObject, name: String): Long {
        val number = json.get(name)
        require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble() && number.toLong() >= 0)
        return number.toLong()
    }
    fun cache(snapshot: WatchingSnapshot): JSONObject = JSONObject().put("version", 1).put("revision", snapshot.revision)
        .put("updatedAt", snapshot.updatedAt).put("entries", JSONArray(snapshot.entries.map { entry ->
            JSONObject().put("key", entry.key).put("watching", entry.watching).put("revision", entry.revision).apply {
                entry.title?.let { put("title", it) }; entry.year?.let { put("year", it) }; entry.poster?.let { put("poster", it) }
                entry.progress?.let { put("progress", progress(it)) }
                put("progressMode", entry.progressMode); entry.progressSource?.let { put("progressSource", it) }
            }
        }))
    fun cached(json: JSONObject): WatchingSnapshot = reply(json).copy(updatedAt = nonnegativeLong(json, "updatedAt"))
}
