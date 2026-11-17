package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow

/** Presentation rules for adult browsing and the local Starred collection. */
object StreamingBrowsePolicy {
    fun enabled(service: String) = service in setOf("movies", "series", "starred")
    fun searchable(service: String) = service in setOf("movies", "series", "youtube")

    fun style(row: ContentRow): CardStyle? {
        val item = row.items.firstOrNull() ?: return null
        if (item.source != "stremio" || item.metadata["sportsSource"] != null ||
            item.metadata["type"] !in setOf("movie", "series")) return null
        return if (row.id.contains("continue") && item.metadata["type"] == "movie")
            CardStyle.STREAMING_CONTINUE else CardStyle.STREAMING_POSTER
    }

    /** On a narrow poster, the episode is more useful than repeating the release year. */
    fun subtitle(item: ContentItem): String? {
        val season = item.metadata["nextSeason"]?.toIntOrNull()
        val episode = item.metadata["nextEpisode"]?.toIntOrNull()
        return if (season != null && season in 0..99 && episode != null && episode in 1..999)
            "Up next S$season E$episode" else item.subtitle
    }
}
