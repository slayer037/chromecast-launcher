package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.providers.cinemeta.CinemetaFilter
import com.majendra.tvlauncher.providers.cinemeta.CinemetaSort

/**
 * What the Filter control holds for Movies or TV shows. [genres] must all match; [minRating] is IMDb times ten.
 * Seen titles are left out by default. Not interested titles are always left out.
 */
data class MovieFilter(val genres: Set<String> = emptySet(), val yearFrom: Int? = null, val yearTo: Int? = null,
    val minRating: Int? = null, val sort: CinemetaSort = CinemetaSort.POPULARITY, val hideSeen: Boolean = true) {
    fun query(type: String) = CinemetaFilter(type, genres, yearFrom, yearTo, minRating?.let { it / 10.0 }, sort)
}

/** Pure rules for the Filter control: where it exists, what it may hold, and how it reads on screen. */
object MovieFilterPolicy {
    private val sections = mapOf("movies" to "movie", "series" to "series")
    val ratings = listOf(null, 60, 65, 70, 75, 80, 85, 90)
    const val FIRST_YEAR = 1920

    /** Only the adult Movies and TV shows sections, on both TVs. Never Kids Corner. */
    fun available(section: String) = section in sections
    fun type(section: String): String? = sections[section]
    fun noun(section: String) = if (section == "series") "TV shows" else "movies"

    /** Drops anything the catalogue cannot answer and puts a reversed year range the right way round. */
    fun normalise(filter: MovieFilter, genres: List<String>, currentYear: Int): MovieFilter {
        val from = filter.yearFrom?.coerceIn(FIRST_YEAR, currentYear)
        val to = filter.yearTo?.coerceIn(FIRST_YEAR, currentYear)
        val reversed = from != null && to != null && from > to
        return filter.copy(genres = genres.filter { it in filter.genres }.toSet(), yearFrom = if (reversed) to else from,
            yearTo = if (reversed) from else to, minRating = filter.minRating?.takeIf { it in ratings })
    }

    fun genresLabel(filter: MovieFilter) = if (filter.genres.isEmpty()) "Any genre" else filter.genres.joinToString(" + ")
    fun yearsLabel(filter: MovieFilter): String {
        val from = filter.yearFrom; val to = filter.yearTo
        return when {
            from == null && to == null -> "Any year"
            from != null && to != null -> if (from == to) "$from" else "$from to $to"
            from != null -> "$from or later"
            else -> "$to or earlier"
        }
    }
    fun ratingLabel(rating: Int?) = if (rating == null) "Any rating" else "IMDb ${rating / 10}.${rating % 10} or higher"
    fun sortLabel(sort: CinemetaSort) = when (sort) {
        CinemetaSort.POPULARITY -> "Most popular first"
        CinemetaSort.NEWEST -> "Newest first"
        CinemetaSort.RATING -> "Highest IMDb rating first"
    }
    fun seenLabel(filter: MovieFilter) = if (filter.hideSeen) "Hidden" else "Shown"

    /** The five lines of the Filter dialog, in order. */
    fun lines(filter: MovieFilter) = listOf("Genres:  ${genresLabel(filter)}", "Years:  ${yearsLabel(filter)}",
        "Rating:  ${ratingLabel(filter.minRating)}", "Order:  ${sortLabel(filter.sort)}", "Already seen:  ${seenLabel(filter)}")

    /** One line for the control and the first row: only what narrows the catalogue, then the order. */
    fun summary(filter: MovieFilter): String = listOfNotNull(
        filter.genres.takeIf { it.isNotEmpty() }?.joinToString(" + "),
        yearsLabel(filter).takeIf { filter.yearFrom != null || filter.yearTo != null },
        filter.minRating?.let { "IMDb ${it / 10}.${it % 10}+" },
        when (filter.sort) { CinemetaSort.POPULARITY -> "most popular"; CinemetaSort.NEWEST -> "newest"; CinemetaSort.RATING -> "top rated" },
        "seen included".takeIf { !filter.hideSeen }).joinToString(" · ").replaceFirstChar { it.uppercase() }

    /** Label, first year, last year. */
    fun yearChoices(currentYear: Int): List<Triple<String, Int?, Int?>> = listOf(
        Triple("Any year", null, null),
        Triple("This year and last (${currentYear - 1} to $currentYear)", currentYear - 1, currentYear),
        Triple("Last five years (${currentYear - 4} to $currentYear)", currentYear - 4, currentYear)) +
        (currentYear / 10 * 10 downTo 1980 step 10).map { decade -> Triple("${decade}s", decade, minOf(decade + 9, currentYear)) } +
        listOf(Triple("Before 1980", null, 1979))
    fun years(currentYear: Int): List<Int> = (currentYear downTo FIRST_YEAR).toList()

    /** A compact text form for the encrypted preferences store. */
    fun encode(filter: MovieFilter): String = listOf("1", filter.genres.joinToString(","), filter.yearFrom?.toString().orEmpty(),
        filter.yearTo?.toString().orEmpty(), filter.minRating?.toString().orEmpty(), filter.sort.name, if (filter.hideSeen) "1" else "0").joinToString("|")

    /** Null for anything this release did not write. */
    fun decode(value: String?): MovieFilter? {
        val parts = value?.takeIf { it.length <= 512 }?.split('|') ?: return null
        if (parts.size != 7 || parts[0] != "1") return null
        fun number(text: String, range: IntRange): Int? = text.toIntOrNull()?.takeIf { it in range }
        val genres = parts[1].split(',').filter { it.isNotEmpty() }
        if (genres.any { it.length > 40 || it.any(Char::isISOControl) } || genres.size > 32) return null
        val sort = CinemetaSort.entries.firstOrNull { it.name == parts[5] } ?: return null
        if (parts[6] != "1" && parts[6] != "0") return null
        return MovieFilter(genres.toSet(), number(parts[2], 1800..2200), number(parts[3], 1800..2200), number(parts[4], 0..100), sort, parts[6] == "1")
    }

    /** Distinguishes filtered results from a search in the render context; never shown. */
    fun token(filter: MovieFilter) = "\u0000filter|" + encode(filter)
}
