package com.majendra.tvlauncher.sync

import com.majendra.tvlauncher.data.ContentItem
import java.util.UUID

/** Explicit next episode, rather than a claim about playback or completed seasons. */
data class EpisodeProgress(val season: Int, val episode: Int) {
    init { require(season in 0..99 && episode in 1..999) }
    fun afterWatched(): EpisodeProgress? = if (episode < 999) copy(episode = episode + 1) else null
    fun label() = "S$season E$episode"
}

/** progressSet distinguishes omission from an explicit null clear. Never rewrite a queued operation. */
data class WatchingOperation(val id: String, val key: String, val watching: Boolean? = null,
    val progressSet: Boolean = false, val progress: EpisodeProgress? = null,
    val title: String? = null, val year: String? = null, val poster: String? = null,
    val progressMode: String? = null) {
    init {
        require(UUID.fromString(id).toString() == id)
        require(key.startsWith("series:") && WatchedPolicy.validKey(key))
        require(watching != null || progressSet || progressMode != null)
        require(progressSet || progress == null)
        require(progressMode == null || (progressMode == "stremio" && !progressSet))
        require(title == null || (title.length <= 200 && title.none(Char::isISOControl) && title.isNotBlank()))
        require(year == null || (year.length <= 40 && year.none(Char::isISOControl) && year.isNotBlank()))
        require(poster == null || WatchedPolicy.publicPoster(poster) == poster)
    }
}
data class WatchingExchange(val snapshot: WatchingSnapshot, val acknowledged: Set<String>)
data class WatchingMerge(val snapshot: WatchingSnapshot, val pending: List<WatchingOperation>)

enum class WatchingAction { OPEN, ADD, REMOVE, SET_EPISODE, WATCHED_EPISODE, CLEAR_EPISODE, USE_STREMIO, DETAILS }
object WatchingActions {
    fun key(item: ContentItem): String? = if (item.source == "stremio" && item.metadata["type"] == "series")
        "series:${item.id}".takeIf(WatchedPolicy::validKey) else null
    fun options(service: String, item: ContentItem, current: WatchingEntry?): List<WatchingAction> {
        if (service == "kids" || key(item) == null) return emptyList()
        return buildList {
            add(WatchingAction.OPEN)
            add(if (current?.watching == true) WatchingAction.REMOVE else WatchingAction.ADD)
            if (current?.watching == true) {
                add(WatchingAction.SET_EPISODE)
                if (current.progress?.afterWatched() != null) add(WatchingAction.WATCHED_EPISODE)
                if (current.progress != null) add(WatchingAction.CLEAR_EPISODE)
                if (current.progressMode == "manual") add(WatchingAction.USE_STREMIO)
            }
            add(WatchingAction.DETAILS)
        }
    }
    fun label(action: WatchingAction, current: WatchingEntry?): String = when (action) {
        WatchingAction.OPEN -> "Open in Stremio"
        WatchingAction.ADD -> "Add to Watching"
        WatchingAction.REMOVE -> "Remove from Watching"
        WatchingAction.SET_EPISODE -> if (current?.progress == null) "Set next episode" else "Edit next episode"
        WatchingAction.WATCHED_EPISODE -> "Watched episode"
        WatchingAction.CLEAR_EPISODE -> "Clear episode progress"
        WatchingAction.USE_STREMIO -> "Use Stremio progress"
        WatchingAction.DETAILS -> "Description & other options"
    }
}
