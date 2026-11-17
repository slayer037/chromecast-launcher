package com.majendra.tvlauncher.data

/** Build-specific live television capability, also applied to saved content and playback handoffs. */
object LiveTvPolicy {
    fun sectionAllowed(enabled: Boolean, id: String) = enabled || id !in setOf("live", "tcl")

    /** Kayo is a streaming app on both TVs, so its channels and guide survive the live TV cut. */
    const val KAYO = "au.com.kayosports.tv"
    private fun kayo(item: ContentItem) = item.metadata["sportsSource"] !in setOf("tv", "stremio") &&
        (item.metadata["playbackPackage"] ?: item.metadata["package"]) == KAYO

    fun itemAllowed(enabled: Boolean, item: ContentItem): Boolean = enabled || kayo(item) ||
        (sectionAllowed(false, item.source) && item.metadata["type"] !in setOf("channel", "tv") &&
            item.metadata["channelName"] == null && item.metadata["channelId"] == null &&
            item.metadata["sportsSource"] !in setOf("tv", "stremio") &&
            item.metadata["card"] != "programme" &&
            targetAllowed(false, item.playbackUri, item.metadata["playbackPackage"] ?: item.metadata["package"]))

    fun filter(enabled: Boolean, feed: FeedResult): FeedResult = if (enabled) feed else feed.copy(
        rows = feed.rows.filterNot { it.id in setOf("kids-abc-live", "stremio-live") ||
            it.id.startsWith("catchup:") || it.id == "sports-channels" }
            .map { it.copy(items = it.items.filter { item -> itemAllowed(false, item) }) }
            .filter { it.items.isNotEmpty() || it.emptyMessage != null })

    fun targetAllowed(enabled: Boolean, uri: String?, packageName: String?) = enabled ||
        (packageName !in setOf("com.tcl.tv", "com.tcl.tvinput") &&
            uri?.startsWith("content://android.media.tv") != true)
}
