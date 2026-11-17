package com.majendra.tvlauncher.sync

import android.content.Context
import com.majendra.tvlauncher.auth.StremioAccountClient
import com.majendra.tvlauncher.auth.StremioAccountResult
import com.majendra.tvlauncher.auth.StremioLibraryEntry
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.providers.StremioEpisode
import com.majendra.tvlauncher.providers.StremioMetadataClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal data class StremioHistoryResult(val accountGeneration: Long,
    val completedMovies: List<ContentItem> = emptyList(), val progressImports: List<StremioProgressImport> = emptyList(),
    val continueMovies: List<ContentItem> = emptyList(), val authenticationRequired: Boolean = false, val error: String? = null)

/** Shared memory-only reads. There is no job, timer, credential transfer or Stremio write operation. */
internal class StremioHistorySource private constructor(context: Context) {
    // The HTTP clients are only needed by a load, which runs on Dispatchers.IO; the generation check needs neither.
    private val app = context.applicationContext
    private val store = com.majendra.tvlauncher.auth.StremioAccountStore(app)
    private val account by lazy { StremioAccountClient(app) }
    private val metadata by lazy { StremioMetadataClient() }
    private val reader = StremioHistoryReader({ store.generation }, { account.loadLibrary() },
        { id -> metadata.historyVideos(id) })

    suspend fun load(watchingKeys: Set<String>, force: Boolean = false): StremioHistoryResult = reader.load(watchingKeys, force)
    fun isCurrent(result: StremioHistoryResult) = reader.isCurrent(result)
    fun cachedContinueMovies(): List<ContentItem>? = reader.cachedContinueMovies()
    /** Guard local enqueue atomically with unlink/relink. Keep all network work outside this callback. */
    fun <T> withCurrent(result: StremioHistoryResult, action: () -> T): T? =
        store.withGeneration(result.accountGeneration, action)

    companion object {
        @Volatile private var instance: StremioHistorySource? = null
        fun shared(context: Context): StremioHistorySource = instance ?: synchronized(this) {
            instance ?: StremioHistorySource(context).also { instance = it }
        }
    }
}

/** Injectable foreground reader lets generation, cadence and cancellation be tested without Android or credentials. */
internal class StremioHistoryReader(private val generation: () -> Long,
    private val library: suspend () -> StremioAccountResult<List<StremioLibraryEntry>>,
    private val metadata: suspend (String) -> List<StremioEpisode>, private val now: () -> Long = System::currentTimeMillis) {
    private data class Cached(val generation: Long, val at: Long, val entries: List<StremioLibraryEntry>)
    private data class Failure(val generation: Long, val at: Long, val value: StremioAccountResult.Failure)
    private data class Videos(val generation: Long, val at: Long, val values: List<StremioEpisode>)
    private data class Resume(val generation: Long, val cards: List<ContentItem>)
    private val mutex = Mutex()
    private var cached: Cached? = null
    private var failed: Failure? = null
    private val videos = linkedMapOf<String, Videos>()
    private val permits = Semaphore(2)
    private var nextSeries = 0
    @Volatile private var resume: Resume? = null
    fun isCurrent(result: StremioHistoryResult) = result.accountGeneration == generation()
    fun cachedContinueMovies(): List<ContentItem>? = resume?.takeIf { it.generation == generation() }?.cards

    suspend fun load(watchingKeys: Set<String>, force: Boolean = false): StremioHistoryResult {
        val initial = generation()
        val snapshot = mutex.withLock {
            if (cached?.generation != initial) cached = null
            if (failed?.generation != initial) failed = null
            videos.entries.removeAll { it.value.generation != initial }
            val current = cached?.takeIf { !force && now() - it.at in 0 until 300_000 }
            if (current != null) current.entries to null else {
                val priorFailure = failed?.takeIf { !force && now() - it.at in 0 until 30_000 }?.value
                when (val response = priorFailure ?: library()) {
                    is StremioAccountResult.Ready -> {
                        currentCoroutineContext().ensureActive()
                        if (generation() != initial) return@withLock emptyList<StremioLibraryEntry>() to StremioAccountResult.Failure("Account changed. Refresh Stremio.")
                        val entries = response.value.take(10_000)
                        cached = Cached(initial, now(), entries); failed = null
                        entries to null
                    }
                    is StremioAccountResult.Failure -> {
                        failed = Failure(initial, now(), response)
                        if (response.authenticationRequired) cached = null
                        cached?.entries.orEmpty() to response
                    }
                    StremioAccountResult.Pending -> emptyList<StremioLibraryEntry>() to StremioAccountResult.Failure("Stremio account approval is pending.", true)
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (generation() != initial) return StremioHistoryResult(initial, error = "Account changed. Refresh Stremio.")
        val (entries, failure) = snapshot
        val continueMovies = StremioHistoryPolicy.continueMovies(entries)
        resume = Resume(initial, continueMovies)
        val imports = if (failure != null) emptyList() else coroutineScope {
            val candidates = entries.filter { it.type == "series" && "series:${it.id}" in watchingKeys }
                .sortedByDescending { it.modified }
            val selected = mutex.withLock {
                if (candidates.isEmpty()) emptyList() else {
                    val start = nextSeries % candidates.size
                    val count = minOf(6, candidates.size)
                    List(count) { candidates[(start + it) % candidates.size] }.also { nextSeries = (start + count) % candidates.size }
                }
            }
            selected.map { entry -> async { permits.withPermit {
                val stored = mutex.withLock { videos[entry.id]?.takeIf { it.generation == initial && !force && now() - it.at in 0 until 300_000 } }
                val list = stored?.values ?: metadata(entry.id).also { loaded ->
                    currentCoroutineContext().ensureActive()
                    if (generation() == initial) mutex.withLock {
                        videos[entry.id] = Videos(initial, now(), loaded)
                        while (videos.size > 24) videos.remove(videos.keys.first())
                    }
                }
                StremioHistoryPolicy.progress(entry, list, now())
            } } }.awaitAll().filterNotNull()
        }
        currentCoroutineContext().ensureActive()
        if (generation() != initial) return StremioHistoryResult(initial, error = "Account changed. Refresh Stremio.")
        return StremioHistoryResult(initial, if (failure == null) StremioHistoryPolicy.completedMovies(entries) else emptyList(),
            imports, continueMovies, failure?.authenticationRequired ?: false, failure?.message)
    }
}
