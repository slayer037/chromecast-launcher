package com.majendra.tvlauncher.data
interface FeedAdapter {
    val id: String
    val displayName: String
    suspend fun isAvailable(): Boolean
    suspend fun load(forceRefresh: Boolean = false): FeedResult
    suspend fun loadProgressively(forceRefresh: Boolean = false, onPartial: suspend (FeedResult) -> Unit): FeedResult = load(forceRefresh)
    suspend fun resolvePlayback(item: ContentItem): PlaybackResult
}
