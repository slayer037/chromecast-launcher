package com.majendra.tvlauncher.providers

import android.content.Context
import com.majendra.tvlauncher.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BroadcasterProvider(private val context: Context, private val config: ServiceConfiguration, private val live: LiveTvProvider) : FeedAdapter {
    override val id = config.id
    override val displayName = config.displayName
    private val extras by lazy { BroadcasterExtras(context, id) }
    override suspend fun isAvailable() = context.packageManager.getLeanbackLaunchIntentForPackage(config.packageName) != null || context.packageManager.getLaunchIntentForPackage(config.packageName) != null
    override suspend fun load(forceRefresh: Boolean): FeedResult = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext FeedResult(status = FeedStatus.UNSUPPORTED, message = "$displayName is not installed. Install it from the TV app store to enable handoff.")
        val extraRows = extras.load()
        val result = live.load(forceRefresh)
        val rows = result.rows.map { row -> row.copy(id = "$id:${row.id}", items = row.items.filter { matches(it.metadata["channelName"].orEmpty()) }.map { it.copy(source = id, playbackUri = null, metadata = it.metadata + mapOf("playbackPackage" to config.packageName, "playbackFallback" to "app", "scheduleOnly" to "true")) }) }.filter { it.items.isNotEmpty() }
        val allRows = extraRows + rows
        result.copy(rows = allRows, status = if (extraRows.isNotEmpty()) FeedStatus.CACHED else if (rows.isEmpty() && result.rows.isNotEmpty()) FeedStatus.NO_CONTENT else result.status,
            message = "Select a channel to open $displayName, then choose it in the app. " +
                if (extraRows.isNotEmpty()) "Additional streaming channels are separate from the Melbourne broadcast guide." else "Melbourne broadcast schedule.")
    }
    private fun matches(name: String): Boolean {
        val n = name.lowercase()
        return when (id) {
            "abc" -> n.startsWith("abc")
            "sbs" -> n.startsWith("sbs") || n.startsWith("nitv")
            "9now" -> n.startsWith("nine") || Regex("^9(?:gem|go|life|rush|hd)").containsMatchIn(n)
            "7plus" -> n.startsWith("seven") || Regex("^7(?:two|mate|flix|bravo|hd|\\s)").containsMatchIn(n) || n.startsWith("racing.com")
            "10" -> n.startsWith("10") || n.startsWith("nickelodeon")
            else -> false
        }
    }
    override suspend fun resolvePlayback(item: ContentItem) = PlaybackResult.App(config.packageName, "Open $displayName and choose its live channel")
}
