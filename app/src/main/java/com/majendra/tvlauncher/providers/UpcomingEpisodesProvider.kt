package com.majendra.tvlauncher.providers

import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.sync.WatchingPolicy
import com.majendra.tvlauncher.sync.WatchingSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

internal data class UpcomingEpisodesState(val row: ContentRow?, val accountGeneration: Long, val deadlineReached: Boolean = false)

/** Foreground-only memory cache. Caller owns cancellation (including on pause); no jobs or account writes. */
internal class UpcomingEpisodesProvider(
    private val read: suspend (String) -> List<UpcomingEpisode>? = StremioMetadataClient()::upcomingVideos,
    private val now: () -> Long = System::currentTimeMillis,
    private val deadlineMillis: Long = 12_000
) {
    private data class Cached(val at: Long, val episodes: List<UpcomingEpisode>?)
    private val cache = linkedMapOf<String, Cached>()
    private val refreshLock = Mutex()
    private val permits = Semaphore(2)
    private var generation = Long.MIN_VALUE
    private var offset = 0

    @Synchronized
    fun cachedRow(snapshot: WatchingSnapshot, accountGeneration: Long = 0): ContentRow? {
        val entries = WatchingPolicy.active(snapshot).filter { it.key.matches(Regex("series:tt[0-9]{1,12}")) && !it.title.isNullOrBlank() }
        if (entries.isEmpty()) return null
        val current = now()
        val rows = entries.mapNotNull { entry ->
            cache[entry.key]?.takeIf { generation == accountGeneration && current - it.at in 0 until 21_600_000 }
                ?.episodes?.let { UpcomingEpisodesPolicy.next(it, Instant.ofEpochMilli(current)) }?.let { UpcomingEpisodesPolicy.item(entry, it) }
        }.sortedWith(compareBy({ item ->
            val entry = entries.first { it.key == "series:${item.id}" }
            UpcomingEpisodesPolicy.next(cache.getValue(entry.key).episodes.orEmpty(), Instant.ofEpochMilli(current))!!.release.order()
        }, { it.title }))
        val unavailable = generation != accountGeneration || entries.any { entry ->
            cache[entry.key]?.let { it.episodes != null && current - it.at in 0 until 21_600_000 } != true
        }
        return ContentRow(UpcomingEpisodesPolicy.ROW_ID, "Upcoming episodes", rows,
            if (rows.isEmpty()) { if (unavailable) "Episode release information unavailable. Refresh to retry."
                else "No upcoming episode dates announced." } else null)
    }

    suspend fun refresh(snapshot: WatchingSnapshot, accountGeneration: Long = 0, force: Boolean = false): UpcomingEpisodesState = refreshLock.withLock {
        val entries = WatchingPolicy.active(snapshot).filter { it.key.matches(Regex("series:tt[0-9]{1,12}")) && !it.title.isNullOrBlank() }.distinctBy { it.key }
        val selected = synchronized(this) {
            if (generation != accountGeneration) { cache.clear(); offset = 0; generation = accountGeneration }
            val keys = entries.map { it.key }.toSet()
            cache.keys.retainAll(keys)
            val stale = entries.filter { entry -> cache[entry.key]?.let { cached ->
                !force && now() - cached.at in 0 until (if (cached.episodes == null) 30_000L else 21_600_000L)
            } != true }
            if (stale.isEmpty()) emptyList() else {
                val start = offset % stale.size
                List(minOf(6, stale.size)) { stale[(start + it) % stale.size] }.also { offset += it.size }
            }
        }
        val completed = withTimeoutOrNull(deadlineMillis) {
            coroutineScope { selected.map { entry -> async { permits.withPermit {
                val episodes = read(entry.key.removePrefix("series:"))?.take(8192)
                currentCoroutineContext().ensureActive()
                synchronized(this@UpcomingEpisodesProvider) {
                    cache[entry.key] = Cached(now(), episodes)
                    while (cache.size > 100) cache.remove(cache.keys.first())
                }
            } } }.awaitAll() }
            true
        } ?: false
        currentCoroutineContext().ensureActive()
        UpcomingEpisodesState(cachedRow(snapshot, accountGeneration), accountGeneration, !completed)
    }
}
