package com.majendra.tvlauncher.sync

import android.content.Context
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.settings.ContentPreferences
import com.majendra.tvlauncher.settings.KidsWatchedStore
import kotlinx.coroutines.flow.StateFlow

data class WatchedSyncStatus(val configured: Boolean, val lastSuccessMillis: Long, val revision: Long,
    val seenCount: Int, val pending: Int, val error: String?, val adultSeenCount: Int = 0, val kidsSeenCount: Int = 0,
    val stremioMoviesRead: Int = 0, val stremioShowsChecked: Int = 0, val stremioLastSuccessMillis: Long = 0, val stremioError: String? = null)

/** Local watched choices only. No endpoint, bearer key, account linking or network transport. */
class WatchedSync private constructor(context: Context) {
    private val app = context.applicationContext
    private val store = WatchedStore.shared(app)
    val changes: StateFlow<Long> = store.changes
    /** For titles selected locally (seen, watching, starred, hidden); browsing alone records nothing. */
    fun catalog(items: List<ContentItem>) { store.catalog(items) }
    fun watchingEntry(item: ContentItem): WatchingEntry? = WatchingActions.key(item)?.let { key -> store.watching().entries.firstOrNull { it.key == key } }
    fun setWatching(item: ContentItem, watching: Boolean): Boolean =
        store.setWatching(item, watching).also { if (it && watching) store.catalog(listOf(item)) }
    fun setEpisode(item: ContentItem, season: Int, episode: Int): Boolean = WatchingActions.key(item)?.let { key ->
        runCatching { EpisodeProgress(season, episode) }.getOrNull()?.let { store.setEpisode(key, it) }
    } == true
    fun clearEpisode(item: ContentItem): Boolean = WatchingActions.key(item)?.let { store.setEpisode(it, null) } == true
    fun advanceEpisode(item: ContentItem): Boolean = WatchingActions.key(item)?.let(store::advanceEpisode) == true
    fun useStremioProgress(item: ContentItem): Boolean = WatchingActions.key(item)?.let(store::useStremioProgress) == true
    fun status(): WatchedSyncStatus {
        val info = store.info()
        return WatchedSyncStatus(false, info.lastSuccessMillis, info.revision, info.seenCount, info.pending,
            if (!info.durable) "Local watched choices could not be saved securely." else null,
            info.adultSeenCount, info.kidsSeenCount)
    }
    suspend fun syncOnce(forceHistory: Boolean = false): Boolean = false
    companion object {
        @Volatile private var instance: WatchedSync? = null
        fun shared(context: Context): WatchedSync = instance ?: synchronized(this) {
            instance ?: WatchedSync(context).also { instance = it }
        }
        private val warming = java.util.concurrent.atomic.AtomicBoolean()
        /**
         * Reads and unseals the local stores on a background thread, once per process, so the first
         * screen finds them in memory. Safe from Application.onCreate; later calls do nothing.
         */
        fun warm(context: Context) {
            if (!warming.compareAndSet(false, true)) return
            val app = context.applicationContext ?: context
            Thread({
                runCatching { shared(app).store.seenKeys() }
                runCatching { ContentPreferences(app) }
            }, "store-warm").apply { isDaemon = true }.start()
        }
    }
}
