package com.majendra.tvlauncher.providers.cinemeta

import com.majendra.tvlauncher.providers.StremioGenrePolicy
import java.net.URLEncoder

/** One server-side listing of public Cinemeta titles: "top" (optionally one genre) or "year" (one year). */
internal data class CatalogueStream(val type: String, val catalogue: String, val option: String?) {
    val key get() = "$catalogue/${option.orEmpty()}"

    /** Null unless every part is a known, bounded value; the option is percent-encoded inside its path segment. */
    fun url(skip: Int = 0): String? {
        if (type !in TYPES || skip !in 0..MAX_SKIP || skip % PAGE != 0) return null
        val known = when (catalogue) {
            TOP -> option == null || option in StremioGenrePolicy.genres(type)
            YEAR -> option != null && option.length == 4 && option.toIntOrNull()?.let { it in minYear(type)..2100 } == true
            else -> false
        }
        if (!known) return null
        val extras = listOfNotNull(option?.let { "genre=" + URLEncoder.encode(it, "UTF-8").replace("+", "%20") },
            "skip=$skip".takeIf { skip > 0 }).joinToString("&")
        return "https://cinemeta-catalogs.strem.io/$catalogue/catalog/$type/$catalogue" + (if (extras.isEmpty()) "" else "/$extras") + ".json"
    }

    companion object {
        const val TOP = "top"
        const val YEAR = "year"
        const val PAGE = 50
        const val MAX_SKIP = 100_000
        val TYPES = setOf("movie", "series")
        fun top(type: String, genre: String? = null) = CatalogueStream(type, TOP, genre)
        fun year(type: String, year: Int) = CatalogueStream(type, YEAR, year.toString())
        /** Oldest year the Cinemeta manifest offers. */
        fun minYear(type: String) = if (type == "series") 1960 else 1920
    }
}

/** How far one stream has been read. [ahead] holds pages read past a failed one in the same batch; memory only. */
internal class StreamCursor(var nextSkip: Int = 0, var complete: Boolean = false, var startedAt: Long = 0, var headAt: Long = 0) {
    val ahead = HashMap<Int, Boolean>()
}

/**
 * Chooses which streams can hold every match for a filter, in the order to read them.
 *
 * The server filters by one genre or by one year, never both, and never by rating. So:
 * - Newest first walks the year streams from the newest year back: they are listed newest first.
 * - One or two years reads those years (a few hundred to about a thousand titles each), whatever the genre.
 * - Otherwise a chosen genre reads that genre's popularity listing; with several genres, the rarest.
 * - Otherwise up to six years reads those years, and anything broader reads the unfiltered popularity listing.
 * A title's year can be up to [YEAR_SPILL] later than the year stream that lists it, so year plans start that much earlier.
 * Rating and the remaining criteria are applied to the index. A stream already read to its end is used first: it costs nothing.
 */
internal object CoveragePlanner {
    const val YEAR_SPILL = 2
    // Broadest first (approximate IMDb genre frequency): the rarest selected genre has the fewest pages to read.
    private val breadth = listOf("Drama", "Comedy", "Thriller", "Romance", "Action", "Horror", "Crime", "Documentary",
        "Adventure", "Sci-Fi", "Family", "Fantasy", "Mystery", "Biography", "Animation", "History", "War", "Sport",
        "Western", "Reality-TV", "Talk-Show", "Game-Show")

    fun plan(filter: CinemetaFilter, currentYear: Int, complete: (CatalogueStream) -> Boolean): List<CatalogueStream> {
        val type = filter.type
        if (type !in CatalogueStream.TYPES) return emptyList()
        val floor = CatalogueStream.minYear(type)
        val from = filter.yearFrom ?: floor
        val to = filter.yearTo ?: currentYear
        if (from > to) return emptyList()
        val bounded = filter.yearFrom != null || filter.yearTo != null
        val years = (minOf(to, currentYear) downTo maxOf(from - YEAR_SPILL, floor)).map { CatalogueStream.year(type, it) }
        val genres = filter.genres.filter { it in StremioGenrePolicy.genres(type) }
            .sortedByDescending { breadth.indexOf(it) }.map { CatalogueStream.top(type, it) }
        val everything = CatalogueStream.top(type)
        if (complete(everything)) return listOf(everything)
        genres.firstOrNull(complete)?.let { return listOf(it) }
        if (bounded && years.all(complete)) return years
        val span = to - from + 1
        return when {
            filter.sort == CinemetaSort.NEWEST -> years
            bounded && span <= 2 -> years
            genres.isNotEmpty() -> listOf(genres.first())
            bounded && span <= 6 -> years
            else -> listOf(everything)
        }
    }
}
