package com.majendra.tvlauncher.providers

import com.majendra.tvlauncher.data.FeedError
import com.majendra.tvlauncher.data.FeedStatus
import com.majendra.tvlauncher.providers.cinemeta.CatalogueStream

/** Movies and TV shows browse rows: Cinemeta genre catalogues, kept to well-rated titles. */
internal object StremioGenrePolicy {
    private const val MOVIE_MINIMUM = 7.0
    private const val SERIES_MINIMUM = 7.5
    // The genre options of the Cinemeta manifest (3.0.14), in the launcher's row order.
    private val movieGenres = listOf("Action", "Comedy", "Drama", "Thriller", "Sci-Fi", "Crime", "Adventure",
        "Animation", "Family", "Fantasy", "Horror", "Mystery", "Romance", "Documentary", "Biography",
        "History", "War", "Sport", "Western")
    private val seriesGenres = listOf("Action", "Comedy", "Drama", "Thriller", "Sci-Fi", "Crime", "Adventure",
        "Animation", "Family", "Fantasy", "Horror", "Mystery", "Romance", "Documentary", "Biography",
        "History", "War", "Sport", "Western", "Reality-TV", "Game-Show", "Talk-Show")

    fun genres(type: String): List<String> = when (type) {
        "movie" -> movieGenres
        "series" -> seriesGenres
        else -> emptyList()
    }

    fun minimum(type: String): Double = if (type == "series") SERIES_MINIMUM else MOVIE_MINIMUM

    /** Unrated titles are excluded: the minimum can't be shown to be met. */
    fun meetsMinimum(type: String, imdbRating: String?): Boolean =
        StremioSearchPolicy.imdbRating(imdbRating)?.toDoubleOrNull()?.let { it >= minimum(type) } == true

    /** Only known genres and whole pages; the genre is percent-encoded inside its path segment. */
    fun genreUrl(type: String, genre: String, skip: Int = 0): String? = CatalogueStream.top(type, genre).url(skip)

    /**
     * Catalogue pages are 50 titles: the first two plus one deeper page. The deeper page depends only on the visit
     * and the genre, so every load during one visit reads the same three pages and rows keep their titles.
     */
    const val PAGE = CatalogueStream.PAGE
    fun pages(visit: Long, genre: String): List<Int> =
        listOf(0, PAGE, PAGE * (2 + Math.floorMod(java.util.Random(visit xor genre.hashCode().toLong()).nextInt(), 7)))

    /**
     * A fresh order every visit to a section, stable within the visit so refreshes don't move cards under focus.
     * Compute each rank once: sort comparisons must not repeatedly construct Random on the UI thread.
     */
    fun <T> shuffled(items: List<T>, seed: Long, key: (T) -> String): List<T> =
        items.map { item -> java.util.Random(seed xor key(item).hashCode().toLong()).nextLong() to item }
            .sortedBy { it.first }
            .map { it.second }

    fun topRowId(feedId: String) = "$feedId-top"
    fun rowId(feedId: String, genre: String) = "$feedId-genre-${genre.lowercase()}"
}

/**
 * One visit to Movies or TV shows, as far as the provider can tell: loads closer together than [gapMillis] belong
 * to the same visit and share a seed. The screen can also start a visit explicitly with [renew].
 */
internal class StremioVisit(private val gapMillis: Long = 30 * 60_000L, private val random: () -> Long = { kotlin.random.Random.nextLong() }) {
    private var seed = 0L
    private var lastLoad = Long.MIN_VALUE
    @Synchronized fun seed(now: Long): Long {
        if (lastLoad == Long.MIN_VALUE || now - lastLoad !in 0 until gapMillis) seed = random()
        lastLoad = now
        return seed
    }
    @Synchronized fun renew() { lastLoad = Long.MIN_VALUE }
}

/** How a Movies or TV shows load is reported when some public catalogue requests failed. */
internal object StremioLoadPolicy {
    /** Some rows are missing or were built from fewer pages than asked for; the rows present are current. */
    const val PARTIAL = "cinemeta_partial"
    /** No public catalogue row could be read at all. */
    const val UNAVAILABLE = "cinemeta_unavailable"
    private const val MISSING = "Unavailable rows: "
    private const val SHORT = "Incomplete rows: "

    /**
     * [unavailable]: row ids with no page read. [incomplete]: row ids built from only some of their pages.
     * A complete load is FRESH with no error. A partial one keeps FRESH (its rows are real and current) but always
     * carries a [PARTIAL] error naming the affected rows, so the repository can keep better rows it already holds.
     * When every public row failed the status is SOURCE_UNAVAILABLE with an [UNAVAILABLE] error.
     */
    fun outcome(anyItems: Boolean, publicRows: Int, unavailable: List<String>, incomplete: List<String>): Pair<FeedStatus, FeedError?> {
        val detail = listOfNotNull(unavailable.takeIf { it.isNotEmpty() }?.joinToString(", ", MISSING, "."),
            incomplete.takeIf { it.isNotEmpty() }?.joinToString(", ", SHORT, ".")).joinToString(" ")
        return when {
            publicRows > 0 && unavailable.size >= publicRows -> FeedStatus.SOURCE_UNAVAILABLE to FeedError(UNAVAILABLE, detail)
            // Nothing to show and something failed is a failure, never "no content": the repository must not discard what it holds.
            detail.isNotEmpty() -> (if (anyItems) FeedStatus.FRESH else FeedStatus.SOURCE_UNAVAILABLE) to FeedError(PARTIAL, detail)
            anyItems -> FeedStatus.FRESH to null
            else -> FeedStatus.NO_CONTENT to null
        }
    }

    /** Row ids named by a [PARTIAL] or [UNAVAILABLE] error: the rows a newer result should not be trusted to replace. */
    fun affectedRows(error: FeedError?): Set<String> {
        if (error == null || error.code !in setOf(PARTIAL, UNAVAILABLE)) return emptySet()
        return error.message.split(". ", ".").flatMap { part ->
            listOf(MISSING, SHORT).firstOrNull { part.startsWith(it) }?.let { part.removePrefix(it).split(", ") } ?: emptyList()
        }.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
