package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.data.ContentItem

/** A title badge is reset from the current item on every card bind. */
object StarBadgePolicy {
    private val imdb = Regex("tt[0-9]{1,12}")
    fun visible(item: ContentItem): Boolean = item.metadata["isStarred"] == "true" &&
        item.source == "stremio" && imdb.matches(item.id) &&
        item.metadata["type"] in setOf("movie", "series") &&
        !item.isLive && item.metadata["sportsSource"] == null

    fun description(item: ContentItem, showLive: Boolean): String =
        listOfNotNull(item.title, item.subtitle, if (showLive) "Live" else null,
            if (visible(item)) "Starred" else null).joinToString(". ")
}
