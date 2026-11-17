package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.data.FeedResult

/** Section-specific unwatched picks. Resume and Watching remain in their own rows. */
object WatchTonightPolicy {
    const val ROW_ID = "watch-tonight"
    private const val MAX_ITEMS = 20
    private val imdb = Regex("tt[0-9]{1,12}")
    private val personalRows = setOf(ROW_ID, "movies-continue-watching", "watching-shows", "saved-shows")

    private fun key(item: ContentItem): String? = item.metadata["type"]
        ?.takeIf { it in setOf("movie", "series") && item.source == "stremio" && imdb.matches(item.id) }
        ?.let { "$it:${item.id}" }

    fun prepend(service: String, query: String, result: FeedResult, starred: List<ContentItem>,
        excludedKeys: Set<String> = emptySet(), isHidden: (ContentItem) -> Boolean = { false },
        isRowVisible: (String) -> Boolean = { true }, refreshSeed: Long = 0): FeedResult {
        val type = when (service) { "movies" -> "movie"; "series" -> "series"; else -> return result }
        if (query.isNotBlank() || !isRowVisible(ROW_ID)) return result
        // Keep Popular now intact; its titles cannot be borrowed through another catalogue or a star.
        val popularKeys = result.rows.firstOrNull { it.id == "$service-top" && isRowVisible(it.id) }
            ?.items.orEmpty().mapNotNull(::key).toSet()
        fun eligible(item: ContentItem): Boolean = key(item) != null && item.metadata["type"] == type &&
            key(item) !in excludedKeys && key(item) !in popularKeys && !isHidden(item) && !item.isLive && item.metadata["sportsSource"] == null &&
            item.metadata["stremioResume"] != "true" && item.metadata["watching"] != "true" &&
            (item.progress == null || item.progress.let { it.isFinite() && it == 0f })
        val catalogue = result.rows.asSequence().filter { it.id !in personalRows && isRowVisible(it.id) }
            .flatMap { it.items.asSequence() }.filter(::eligible).distinctBy(::key).toList()
        val catalogueByKey = catalogue.associateBy(::key)
        val stars = starred.filter(::eligible).distinctBy(::key).map { catalogueByKey[key(it)] ?: it }
        val starKeys = stars.mapNotNull(::key).toSet()
        val available = catalogue.filter { key(it) !in starKeys }
        val fresh = if (refreshSeed == 0L) available else
            com.majendra.tvlauncher.providers.StremioGenrePolicy.shuffled(available, refreshSeed) { key(it).orEmpty() }
        val groups = listOf(stars to "starred", fresh to "fresh")
        // Share the row between explicit choices and fresh picks; neither source can consume every slot.
        val items = buildList {
            var index = 0
            while (size < MAX_ITEMS && groups.any { index < it.first.size }) {
                for ((group, reason) in groups) {
                    val item = group.getOrNull(index) ?: continue
                    add(item.copy(metadata = item.metadata + ("watchTonightReason" to reason)))
                    if (size == MAX_ITEMS) break
                }
                index++
            }
        }
        val rest = result.rows.filterNot { it.id == ROW_ID }
        return result.copy(rows = if (items.isEmpty()) rest else listOf(ContentRow(ROW_ID, "Watch tonight", items)) + rest)
    }
}
