package com.majendra.tvlauncher.providers.cinemeta

import com.majendra.tvlauncher.data.ContentItem

enum class CinemetaSort { POPULARITY, NEWEST, RATING }

/**
 * What the viewer asked for. [genres] must all be present on a title; an empty set means any genre.
 * Years are inclusive and compare against the title's first release year. Titles without an IMDb rating
 * never satisfy [minRating], and titles without a year never satisfy a year bound.
 */
data class CinemetaFilter(val type: String = "movie", val genres: Set<String> = emptySet(), val yearFrom: Int? = null,
    val yearTo: Int? = null, val minRating: Double? = null, val sort: CinemetaSort = CinemetaSort.POPULARITY)

/** A lean public catalogue entry. Artwork addresses are derived from the id; the description is fetched on focus. */
data class CinemetaTitle(val id: String, val type: String, val name: String, val year: Int?, val rating: Double?,
    val runtimeMinutes: Int?, val genres: List<String>) {
    val poster get() = "https://images.metahub.space/poster/small/$id/img"
    val background get() = "https://images.metahub.space/background/medium/$id/img"

    /** The same card shape the Movies and TV shows rows use, so existing adapters, Seen and Starred work unchanged. */
    fun toContentItem(): ContentItem = ContentItem(id = id, source = "stremio", title = name, subtitle = year?.toString(),
        imageUrl = poster, landscapeImageUrl = background,
        playbackUri = "stremio:///detail/$type/$id" + if (type == "movie") "/$id" else "",
        metadata = buildMap {
            put("type", type); put("package", "com.stremio.one"); put("catalog", "Cinemeta"); put("personalised", "false")
            rating?.let { put("imdbRating", it.toString()); put("imdbRatingSource", "Cinemeta") }
            year?.let { put("year", it.toString()) }
            runtimeMinutes?.let { put("runtime", it.toString()) }
            if (genres.isNotEmpty()) put("genres", genres.joinToString("|"))
        })
}

/**
 * One page of matches from what is indexed so far. [total] counts every indexed match. [mayHaveMore] is true while
 * the catalogues that can hold further matches have unread pages: call [CinemetaCatalogue.extend] to read them.
 * When it is false, [total] is final. [revision] changes whenever the index gains or updates a title.
 */
data class CinemetaResults(val items: List<CinemetaTitle>, val total: Int, val mayHaveMore: Boolean, val revision: Long)

/** COMPLETE: nothing further can arrive. PARTIAL: more pages exist. FAILED: a request failed; ask again later. */
enum class CinemetaCoverage { COMPLETE, PARTIAL, FAILED }

data class CinemetaExtension(val matches: Int, val pagesFetched: Int, val coverage: CinemetaCoverage)
