package com.majendra.tvlauncher.kids

import com.majendra.tvlauncher.data.ContentItem

/** What the children are choosing tonight. */
enum class KidsPickKind(val noun: String, val emoji: String, val night: String) {
    MOVIE("movie", "🎬", "Movie night"), SHOW("show", "📺", "Show time")
}

/** The draw accepts only Australian G-rated movies or shows approved by Kids Corner. */
object KidsPickPolicy {
    private val imdbId = Regex("tt[0-9]{5,12}")
    private val abcVideo = Regex("[A-Z0-9]{8,24}")

    fun eligible(item: ContentItem): Boolean = eligible(item, KidsPickKind.MOVIE)

    fun eligible(item: ContentItem, kind: KidsPickKind): Boolean {
        if (item.source != "kids" || item.metadata["classification"] != "G" || item.metadata["classificationCountry"] != "AU") return false
        return when (kind) {
            KidsPickKind.MOVIE -> {
                val id = item.metadata["stremioId"] ?: return false
                item.metadata["type"] == "movie" && item.metadata["pickerEligible"] == "true" && imdbId.matches(id) &&
                    item.playbackUri in setOf("stremio:///detail/movie/$id", "stremio:///detail/movie/$id/$id")
            }
            KidsPickKind.SHOW -> when (item.metadata["type"]) {
                "series" -> item.metadata["stremioId"]?.let { id ->
                    imdbId.matches(id) && item.playbackUri == "stremio:///detail/series/$id"
                } == true
                "episode" -> item.metadata["abcVideoId"]?.let { id ->
                    abcVideo.matches(id) && item.playbackUri == "https://iview.abc.net.au/video/$id"
                } == true
                else -> false
            }
        }
    }

    /** An unbiased two-way random index is supplied by the view's SecureRandom. */
    fun winner(first: ContentItem?, second: ContentItem?, index: Int, kind: KidsPickKind = KidsPickKind.MOVIE): ContentItem? {
        if (first == null || second == null || !eligible(first, kind) || !eligible(second, kind)) return null
        return when (index) { 0 -> first; 1 -> second; else -> null }
    }
}
