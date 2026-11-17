package com.majendra.tvlauncher.sync

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object WatchedCodec {
    fun entry(value: WatchedEntry, includeState: Boolean = true): JSONObject = JSONObject().put("key", value.key).apply {
        if (includeState) { put("seen", value.seen); put("revision", value.revision) }
        value.title?.let { put("title", it) }; value.year?.let { put("year", it) }; value.poster?.let { put("poster", it) }
        if (value.kids) put("kids", true)
    }
    private fun parseEntry(json: JSONObject, state: Boolean = true): WatchedEntry {
        val key = json.getString("key"); require(WatchedPolicy.validKey(key))
        val seen = if (state) json.get("seen").also { require(it is Boolean) } as Boolean else false
        val revision = json.optLong("revision", 0).also { require(it >= 0) }
        fun text(name: String, limit: Int): String? = if (!json.has(name) || json.isNull(name)) null else
            json.getString(name).also { require(it.length <= limit && it.none(Char::isISOControl)) }.takeIf(String::isNotBlank)
        val title = text("title", 200); val year = text("year", 40); val poster = text("poster", 1024)?.let(WatchedPolicy::publicPoster)
        return WatchedEntry(key, seen, revision, title, year, poster, json.optBoolean("kids", false))
    }
    private fun JSONArray.entries(state: Boolean = true): List<WatchedEntry> {
        require(length() <= WatchedPolicy.MAX_ENTRIES)
        return (0 until length()).map { parseEntry(getJSONObject(it), state) }.also { values -> require(values.map { it.key }.distinct().size == values.size) }
    }
    private fun JSONObject.array(name: String) = optJSONArray(name) ?: JSONArray()
    fun encode(state: WatchedState): ByteArray = JSONObject().put("version", 1).put("revision", state.revision)
        .put("entries", JSONArray(state.entries.values.map { entry(it) }))
        .put("pending", JSONArray(state.pending.map { entry(it.entry).put("id", it.id) }))
        .put("imports", JSONArray(state.imports.values.map { entry(it) }))
        .put("seeded", JSONArray(state.seeded.toList())).put("kidsKeys", JSONArray(state.kidsKeys.toList()))
        .put("catalog", JSONArray(state.catalog.values.map { entry(it, false) }))
        .put("catalogPending", JSONArray(state.catalogPending.toList())).put("lastSuccessMillis", state.lastSuccessMillis)
        .put("watching", WatchingCodec.cache(state.watching))
        .put("watchingPending", JSONArray(state.watchingPending.map(WatchingCodec::operation))).toString().toByteArray(Charsets.UTF_8)
    fun decode(bytes: ByteArray): WatchedState {
        require(bytes.size <= 32 * 1024 * 1024)
        val json = JSONObject(String(bytes, Charsets.UTF_8)); require(json.getInt("version") == 1)
        fun keys(name: String): Set<String> = json.array(name).let { array ->
            require(array.length() <= WatchedPolicy.MAX_ENTRIES)
            (0 until array.length()).map { array.getString(it).also { key -> require(WatchedPolicy.validKey(key)) } }.toSet()
        }
        val pending = json.array("pending").let { array ->
            require(array.length() <= WatchedPolicy.MAX_PENDING)
            (0 until array.length()).map { index -> array.getJSONObject(index).let {
                val id = it.getString("id"); require(UUID.fromString(id).toString() == id)
                WatchedOperation(id, parseEntry(it))
            } }.also { require(it.map(WatchedOperation::id).distinct().size == it.size) }
        }
        val seeded = json.array("seeded").let { array ->
            require(array.length() <= 3); (0 until array.length()).map { array.getString(it).also { source -> require(source in setOf("titles", "kids", "kids-scope-v2")) } }.toSet()
        }
        return WatchedState(json.getLong("revision").also { require(it >= 0) }, json.array("entries").entries().associateBy { it.key }, pending,
            json.array("imports").entries().associateBy { it.key }, seeded, keys("kidsKeys"),
            json.array("catalog").entries(false).associateBy { it.key }, keys("catalogPending"), json.optLong("lastSuccessMillis", 0).coerceAtLeast(0),
            json.optJSONObject("watching")?.let(WatchingCodec::cached) ?: WatchingSnapshot(),
            WatchingCodec.pending(json.array("watchingPending")))
    }
    fun reply(json: JSONObject): WatchedReply {
        require(json.getInt("version") == 1)
        val revision = json.getLong("revision").also { require(it >= 0) }
        val acknowledged = json.getJSONArray("acknowledged").let { array ->
            require(array.length() <= 200); (0 until array.length()).map { array.getString(it).also { id -> require(UUID.fromString(id).toString() == id) } }.toSet()
        }
        return WatchedReply(revision, acknowledged, json.getJSONArray("entries").entries().also { require(it.all { entry -> entry.revision <= revision }) })
    }
}
