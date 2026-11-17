package com.majendra.tvlauncher.sync

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.providers.KidsSeriesSeeds
import java.util.Locale

data class WatchedEntry(val key: String, val seen: Boolean, val revision: Long = 0,
    val title: String? = null, val year: String? = null, val poster: String? = null, val kids: Boolean = false)
data class WatchedOperation(val id: String, val entry: WatchedEntry)
data class WatchedReply(val revision: Long, val acknowledged: Set<String>, val entries: List<WatchedEntry>)
data class WatchedState(val revision: Long = 0, val entries: Map<String, WatchedEntry> = emptyMap(),
    val pending: List<WatchedOperation> = emptyList(), val imports: Map<String, WatchedEntry> = emptyMap(),
    val seeded: Set<String> = emptySet(), val kidsKeys: Set<String> = emptySet(),
    val catalog: Map<String, WatchedEntry> = emptyMap(), val catalogPending: Set<String> = emptySet(),
    val lastSuccessMillis: Long = 0, val watching: WatchingSnapshot = WatchingSnapshot(),
    val watchingPending: List<WatchingOperation> = emptyList())

object WatchedPolicy {
    const val MAX_ENTRIES = 6500
    const val MAX_PENDING = 20000
    // Compiled once: these run for every title on every render.
    private val imdb = Regex("tt[0-9]{1,12}")
    private val kidsKey = Regex("kids:[A-Za-z0-9][A-Za-z0-9._:/-]{0,506}")
    private val releaseYear = Regex("(?:19|20)[0-9]{2}")
    private val posters = setOf("images.metahub.space", "images.cinemeta.strem.io", "v3-cinemeta.strem.io", "images.abc.net.au", "artworks.thetvdb.com")
    private val seriesIds by lazy { KidsSeriesSeeds.all.map { it.imdbId }.toSet() }
    fun validKey(key: String): Boolean = when {
        key.startsWith("movie:") || key.startsWith("series:") -> imdb.matches(key.substringAfter(':'))
        key.startsWith("kids:") -> key.length <= 512 && kidsKey.matches(key) && !key.contains("://")
        else -> false
    }
    fun legacyKidsKey(group: String): String? {
        if (group.startsWith("imdb:") && imdb.matches(group.removePrefix("imdb:"))) {
            val id = group.removePrefix("imdb:")
            return "kids:" + (if (id in seriesIds) "series:" else "movie:") + id
        }
        return "kids:$group".takeIf(::validKey)
    }
    fun key(item: ContentItem): String? {
        if (item.isLive || item.metadata["type"] in setOf("channel", "tv")) return null
        if (item.source == "kids") {
            val group = item.metadata["kidsGroupId"]?.takeIf(String::isNotBlank)
                ?: item.metadata["stremioId"]?.takeIf(imdb::matches)?.let { "imdb:$it" } ?: return null
            val id = group.removePrefix("imdb:")
            if (group.startsWith("imdb:") && imdb.matches(id) && item.metadata["type"] == "series") return "kids:series:$id"
            return legacyKidsKey(group)
        }
        return if (item.source == "stremio" && item.metadata["type"] in setOf("movie", "series") && imdb.matches(item.id))
            "${item.metadata.getValue("type")}:${item.id}" else null
    }
    fun publicPoster(value: String?): String? = value?.takeIf { it.length <= 1024 }?.takeIf {
        runCatching { val uri = URI(it)
            uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) in posters && uri.port in setOf(-1, 443) &&
                uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
        }.getOrDefault(false)
    }
    fun metadata(item: ContentItem): WatchedEntry? {
        val key = key(item) ?: return null
        val year = (item.metadata["year"] ?: releaseYear.find(item.subtitle.orEmpty())?.value)
            ?.take(40)?.filterNot(Char::isISOControl)?.takeIf(String::isNotBlank)
        return WatchedEntry(key, false, title = item.title.filterNot(Char::isISOControl).take(200).takeIf(String::isNotBlank),
            year = year, poster = publicPoster(item.imageUrl), kids = item.source == "kids")
    }
    fun effective(state: WatchedState): Map<String, WatchedEntry> = state.entries.toMutableMap().apply {
        state.imports.forEach { (key, entry) -> putIfAbsent(key, entry) }
        state.pending.forEach { put(it.entry.key, it.entry) }
    }
    /** History may add unknown completed adult films; explicit restored/pending choices always win. */
    fun importMovieHistory(state: WatchedState, items: List<ContentItem>): WatchedState {
        val known = effective(state)
        val imports = state.imports.toMutableMap()
        var remaining = MAX_ENTRIES - known.size
        items.forEach { item ->
            val value = metadata(item)?.takeIf { it.key.startsWith("movie:") } ?: return@forEach
            if (value.key !in known && value.key !in imports && remaining > 0) {
                imports[value.key] = value.copy(seen = true, kids = false); remaining--
            }
        }
        return state.copy(imports = imports)
    }
    fun isKidsKey(key: String): Boolean = key.startsWith("kids:") && validKey(key)

    /** Import genuine legacy choices once; a remote or local unseen tombstone always wins. */
    fun seed(state: WatchedState, source: String, keys: Set<String>, kids: Boolean = false): WatchedState {
        require(source in setOf("titles", "kids", "kids-scope-v2"))
        if (source in state.seeded) return state
        val valid = keys.filter { validKey(it) && (!kids || isKidsKey(it)) }.take(MAX_ENTRIES).toSet()
        val imports = state.imports.toMutableMap()
        valid.forEach { key -> if (key !in state.entries && key !in imports) imports[key] = WatchedEntry(key, true, kids = isKidsKey(key)) }
        return state.copy(imports = imports, seeded = state.seeded + source,
            kidsKeys = (state.kidsKeys.filter(::isKidsKey) + if (kids) valid else emptySet()).take(MAX_ENTRIES).toSet())
    }
    fun merge(state: WatchedState, reply: WatchedReply, importedKeys: Set<String> = emptySet(),
        sentCatalog: Map<String, WatchedEntry> = emptyMap(), at: Long = state.lastSuccessMillis): WatchedState {
        if (reply.revision < state.revision) return state
        return state.copy(revision = reply.revision, entries = reply.entries.associateBy { it.key },
            pending = state.pending.filterNot { it.id in reply.acknowledged }, imports = state.imports - importedKeys,
            kidsKeys = (state.kidsKeys.filter(::isKidsKey) + reply.entries.map { it.key }.filter(::isKidsKey)).take(MAX_ENTRIES).toSet(),
            catalogPending = state.catalogPending.filterNot { key -> sentCatalog[key]?.let { state.catalog[key] == it } == true }.toSet(),
            lastSuccessMillis = at)
    }
}

