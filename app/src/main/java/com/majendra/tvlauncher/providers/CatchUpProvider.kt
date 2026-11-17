package com.majendra.tvlauncher.providers

import android.content.Context
import com.majendra.tvlauncher.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One sidebar entry for the free-to-air catch-up apps, replacing five near-identical sections whose live
 * channel rows now live in Live TV. Shows the apps themselves, then 7plus/9Now extra live channels and events.
 */
class CatchUpProvider(private val context: Context, private val broadcasters: List<ServiceConfiguration>) : FeedAdapter {
    override val id = "catchup"
    override val displayName = "Catch-up"
    private val extras = broadcasters.associate { it.id to BroadcasterExtras(context, it.id) }
    override suspend fun isAvailable() = true

    override suspend fun load(forceRefresh: Boolean): FeedResult = withContext(Dispatchers.IO) {
        val installed = broadcasters.filter { installed(it.packageName) }
        val apps = installed.map { app ->
            ContentItem("app:${app.id}", id, app.displayName, "Open app", imageUrl = "app-banner://${app.packageName}",
                landscapeImageUrl = "app-banner://${app.packageName}",
                metadata = mapOf("layout" to "app", "logo" to "true", "package" to app.packageName))
        }
        val extraRows = (if (context.liveTvEnabled) installed else emptyList()).flatMap { app ->
            runCatching { extras.getValue(app.id).load() }.getOrDefault(emptyList())
                .map { row -> row.copy(id = "catchup:${row.id}", title = "${app.displayName} · ${row.title}") }
        }
        val rows = listOfNotNull(ContentRow("catchup-apps", "Catch-up apps", apps).takeIf { apps.isNotEmpty() }) + extraRows
        FeedResult(rows, if (rows.isEmpty()) FeedStatus.NO_CONTENT else FeedStatus.FRESH,
            if (rows.isEmpty()) "Install 7plus, 9Now, ABC iview, SBS On Demand or 10 from the app store." else "")
    }

    override suspend fun resolvePlayback(item: ContentItem): PlaybackResult =
        item.metadata["package"]?.let { PlaybackResult.App(it) } ?: PlaybackResult.Unavailable("App not found")

    private fun installed(packageName: String) = context.packageManager.getLeanbackLaunchIntentForPackage(packageName) != null ||
        context.packageManager.getLaunchIntentForPackage(packageName) != null
}
