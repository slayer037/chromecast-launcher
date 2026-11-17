package com.majendra.tvlauncher.providers.cinemeta

import java.io.IOException
import java.io.Reader
import java.time.LocalDate

/**
 * One catalogue entry reduced to what the launcher shows or filters on. Unknown numbers are 0 (or [UNKNOWN_DAY],
 * or a negative popularity); [ratingText] is the source's own validated text, [rating] the same value times ten.
 */
class CatalogueMeta(val id: String, val imdbId: String?, val type: String?, val name: String, val releaseInfo: String?,
    val year: Int, val releasedDay: Int, val rating: Int, val ratingText: String?, val runtime: Int,
    val genres: List<String>, val description: String?, val poster: String?, val background: String?, val popularity: Float) {
    companion object { const val UNKNOWN_DAY = Int.MIN_VALUE }
}

/** [hasMore] is null when the add-on did not say. */
class CataloguePage(val metas: List<CatalogueMeta>, val hasMore: Boolean?)

internal object CinemetaPageParser {
    /** A catalogue or search response. A body without a "metas" array is an error, never an empty last page. */
    fun page(reader: Reader, maxItems: Int = 100, maxChars: Long = 2_000_000): CataloguePage {
        val json = JsonPull(reader, maxChars)
        val metas = ArrayList<CatalogueMeta>(64)
        var found = false
        var hasMore: Boolean? = null
        val root = json.objectOrSkip { name -> when (name) {
            "metas", "metasDetailed" -> if (json.arrayOrSkip { meta(json)?.let { if (metas.size < maxItems) metas += it } }) found = true
            "hasMore" -> hasMore = when (json.scalar()) { "true" -> true; "false" -> false; else -> null }
            else -> json.skip()
        } }
        if (!root || !found) throw IOException("Unexpected catalogue response")
        return CataloguePage(metas, hasMore)
    }

    /** A meta response; the episode list of a long series is skipped rather than parsed. */
    fun details(reader: Reader, maxChars: Long = 2_000_000): CatalogueMeta? {
        val json = JsonPull(reader, maxChars)
        var meta: CatalogueMeta? = null
        json.objectOrSkip { name -> if (name == "meta") meta = meta(json) else json.skip() }
        return meta
    }

    private fun meta(json: JsonPull): CatalogueMeta? {
        var id: String? = null; var imdbId: String? = null; var type: String? = null; var name: String? = null
        var releaseInfo: String? = null; var year: String? = null; var released: String? = null; var rating: String? = null
        var runtime: String? = null; var description: String? = null; var poster: String? = null; var background: String? = null
        var popularity: String? = null
        var genres: List<String> = emptyList()
        val read = json.objectOrSkip { field -> when (field) {
            "id" -> id = json.scalar()
            "imdb_id" -> imdbId = json.scalar()
            "type" -> type = json.scalar()
            "name" -> name = json.scalar()
            "releaseInfo" -> releaseInfo = json.scalar()
            "year" -> year = json.scalar()
            "released" -> released = json.scalar()
            "imdbRating" -> rating = json.scalar()
            "runtime" -> runtime = json.scalar()
            "description" -> description = json.scalar()
            "poster" -> poster = json.scalar()
            "background" -> background = json.scalar()
            "popularity" -> popularity = json.scalar()
            // Cinemeta sends the same list under both names; keep whichever has entries.
            "genres", "genre" -> { val values = ArrayList<String>(4); json.arrayOrSkip { json.scalar()?.let(values::add) }; if (values.isNotEmpty()) genres = CinemetaText.genres(values) }
            else -> json.skip()
        } }
        val itemId = id?.takeIf { it.isNotBlank() && it.length <= 512 }
        val title = name?.takeIf { it.isNotBlank() }?.take(500)
        if (!read || itemId == null || title == null) return null
        val ratingText = CinemetaText.rating(rating)
        return CatalogueMeta(itemId, imdbId?.takeIf(CinemetaIds::valid), type?.takeIf { it.isNotBlank() && it.length <= 40 }, title,
            releaseInfo?.takeIf { it.isNotBlank() }?.take(40), CinemetaText.year(year).takeIf { it > 0 } ?: CinemetaText.year(releaseInfo),
            CinemetaText.day(released), CinemetaText.rating10(ratingText), ratingText, CinemetaText.minutes(runtime), genres,
            description?.takeIf { it.isNotBlank() }?.take(8_000), poster?.takeIf { it.isNotBlank() && it.length <= 2048 },
            background?.takeIf { it.isNotBlank() && it.length <= 2048 },
            popularity?.toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: -1f)
    }
}

/** IMDb ids as numbers: "tt0096256" is 96256. Ids that do not survive the round trip are never indexed. */
internal object CinemetaIds {
    fun parse(id: String?): Long {
        if (id == null || id.length !in 3..14 || id[0] != 't' || id[1] != 't') return -1
        var value = 0L
        for (index in 2 until id.length) { val digit = id[index] - '0'; if (digit !in 0..9) return -1; value = value * 10 + digit }
        return if (value > 0 && format(value) == id) value else -1
    }
    fun valid(id: String) = parse(id) > 0
    fun format(value: Long) = "tt" + value.toString().padStart(7, '0')
    /** Accepts "tt…" or a title key such as "movie:tt…". */
    fun fromKey(key: String) = parse(key.substringAfterLast(':'))
}

internal object CinemetaText {
    /** One or two digits with at most one decimal, 0 to 10; anything else is not a rating. */
    fun rating(value: String?): String? {
        val text = value?.trim() ?: return null
        val dot = text.indexOf('.')
        val whole = if (dot < 0) text else text.substring(0, dot)
        if (whole.length !in 1..2 || !whole.all { it in '0'..'9' }) return null
        if (dot >= 0 && (text.length != dot + 2 || text[dot + 1] !in '0'..'9')) return null
        return text.takeIf { rating10(it) in 0..100 }
    }
    /** Ten times a rating already accepted by [rating]; 0 when there is none. */
    fun rating10(valid: String?): Int {
        val text = valid ?: return 0
        val dot = text.indexOf('.')
        return (if (dot < 0) text else text.substring(0, dot)).toInt() * 10 + if (dot < 0) 0 else text[dot + 1] - '0'
    }
    fun ratingText(rating10: Int) = "${rating10 / 10}.${rating10 % 10}"
    /** First year in "2019", "2018–2024" or 2019. */
    fun year(value: String?): Int {
        val text = value?.trim() ?: return 0
        if (text.length < 4 || !(0 until 4).all { text[it] in '0'..'9' } || (text.length > 4 && text[4] in '0'..'9')) return 0
        return text.substring(0, 4).toInt().takeIf { it in 1870..2100 } ?: 0
    }
    /** Epoch day of an ISO date or date-time. */
    fun day(value: String?): Int {
        val text = value ?: return CatalogueMeta.UNKNOWN_DAY
        if (text.length < 10 || text[4] != '-' || text[7] != '-') return CatalogueMeta.UNKNOWN_DAY
        return try {
            val year = text.substring(0, 4).toInt()
            if (year !in 1870..2100) CatalogueMeta.UNKNOWN_DAY
            else LocalDate.of(year, text.substring(5, 7).toInt(), text.substring(8, 10).toInt()).toEpochDay().toInt()
        } catch (_: RuntimeException) { CatalogueMeta.UNKNOWN_DAY }
    }
    /** "142 min" is 142. */
    fun minutes(value: String?): Int {
        val text = value?.trim() ?: return 0
        var result = 0; var index = 0
        while (index < text.length && text[index] in '0'..'9') { if (index == 4) return 0; result = result * 10 + (text[index] - '0'); index++ }
        return if (index > 0 && result <= 1440) result else 0
    }
    fun genres(values: List<String>): List<String> = values.asSequence().map { it.trim() }
        .filter { it.length in 1..80 && it.none(Char::isISOControl) && '|' !in it }.distinct().take(20).toList()
}
