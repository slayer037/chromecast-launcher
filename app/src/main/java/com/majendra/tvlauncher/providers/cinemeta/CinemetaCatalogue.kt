package com.majendra.tvlauncher.providers.cinemeta

import android.content.Context
import com.majendra.tvlauncher.providers.StremioGenrePolicy
import com.majendra.tvlauncher.providers.StremioMetadataClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

/**
 * Filterable view of the public Cinemeta catalogue.
 *
 * [query] answers at once from the on-disk index. [extend] reads further catalogue pages for one filter, and only
 * when called: nothing is fetched for a filter nobody opened. A screen shows [query], calls [extend] when it needs
 * more rows than it has, and queries again. "Still loading" is an [extend] call in flight; "no more matches" is
 * [CinemetaResults.mayHaveMore] false, or [CinemetaCoverage.COMPLETE].
 *
 * Seen, Starred and Not interested are never stored here: pass their ids as `excluded`. Only anonymous public
 * catalogue responses are indexed. The index also records how far each public listing has been read, which is a
 * faint trace of the filters used; it lives in no-backup storage that the diagnostic exports do not read.
 */
class CinemetaCatalogue internal constructor(directory: File, private val fetch: suspend (CatalogueStream, Int) -> CataloguePage?,
    private val now: () -> Long = System::currentTimeMillis, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val files = CinemetaIndexFile(directory)
    private val indexes = HashMap<String, CinemetaIndex>()
    private val lock = Any()
    private val gate = Mutex() // One extension at a time, so the request limit holds across filters.
    @Volatile private var blockedUntil = 0L

    /** Genres the server can filter by, in display order. */
    fun genres(type: String): List<String> = StremioGenrePolicy.genres(type)

    /** [excluded]: IMDb ids ("tt…") or title keys ("movie:tt…") to leave out, such as Seen and Not interested. */
    suspend fun query(filter: CinemetaFilter, offset: Int = 0, limit: Int = 60, excluded: Collection<String> = emptySet()): CinemetaResults =
        withContext(dispatcher) {
            val index = index(filter.type) ?: return@withContext CinemetaResults(emptyList(), 0, false, 0)
            val skipped = ids(excluded)
            synchronized(lock) {
                val (items, total) = index.query(filter, offset, limit, skipped)
                CinemetaResults(items, total, !plan(index, filter).all { complete(index, it) }, index.revision)
            }
        }

    /**
     * Reads catalogue pages for [filter], at most [CONCURRENCY] at a time, until [wanted] matches are indexed, the
     * listings end, or [maxPages] have been read in this call. Cancelling the caller stops it between pages.
     */
    suspend fun extend(filter: CinemetaFilter, wanted: Int = 60, excluded: Collection<String> = emptySet(), maxPages: Int = 12): CinemetaExtension =
        withContext(dispatcher) {
            val index = index(filter.type) ?: return@withContext CinemetaExtension(0, 0, CinemetaCoverage.COMPLETE)
            val skipped = ids(excluded)
            gate.withLock { extend(index, filter, wanted, skipped, maxPages) }
        }

    /** One match chosen with equal chance from everything indexed, for the movie picker. Call [extend] first for a deeper pool. */
    suspend fun draw(filter: CinemetaFilter, excluded: Collection<String> = emptySet(), random: Random = Random.Default): CinemetaTitle? =
        withContext(dispatcher) {
            val index = index(filter.type) ?: return@withContext null
            val skipped = ids(excluded)
            synchronized(lock) { index.draw(filter, skipped, random) }
        }

    /** Writes pending changes; call when a screen that used [extend] closes. [extend] also saves after each burst. */
    suspend fun flush() = withContext(dispatcher) { synchronized(lock) { indexes.values.forEach { if (it.dirty) files.save(it) } } }

    /** A public page the Movies or TV shows rows read anyway: its titles and position are kept. Blocking; call off the main thread. */
    internal fun observe(stream: CatalogueStream, skip: Int, page: CataloguePage) {
        val index = index(stream.type) ?: return
        synchronized(lock) { apply(index, stream, skip, page) }
    }

    private suspend fun extend(index: CinemetaIndex, filter: CinemetaFilter, wanted: Int, skipped: LongArray, maxPages: Int): CinemetaExtension {
        var fetched = 0
        var failed = false
        while (true) {
            currentCoroutineContext().ensureActive()
            var matches = 0
            var covered = false
            val blocked = failed || now() < blockedUntil
            val batch = synchronized(lock) {
                val plan = plan(index, filter)
                val requests = if (blocked) emptyList() else requests(index, plan, minOf(CONCURRENCY, maxPages - fetched)) { index.count(filter, skipped) >= wanted }
                matches = index.count(filter, skipped)
                covered = plan.all { complete(index, it) }
                requests
            }
            if (batch.isEmpty()) {
                if (fetched > 0) synchronized(lock) { if (index.dirty) files.save(index) }
                return CinemetaExtension(matches, fetched, if (covered) CinemetaCoverage.COMPLETE else if (blocked) CinemetaCoverage.FAILED else CinemetaCoverage.PARTIAL)
            }
            val pages = coroutineScope { batch.map { (stream, skip) -> async { fetch(stream, skip) } }.awaitAll() }
            synchronized(lock) {
                batch.forEachIndexed { position, (stream, skip) -> pages[position]?.let { apply(index, stream, skip, it) } ?: run { failed = true } }
            }
            fetched += batch.size
            if (failed) blockedUntil = now() + RETRY
        }
    }

    /** Stale first pages of listings already begun come first; then the next unread pages, shared across the plan in order. */
    private fun requests(index: CinemetaIndex, plan: List<CatalogueStream>, slots: Int, satisfied: () -> Boolean): List<Pair<CatalogueStream, Int>> {
        if (slots <= 0) return emptyList()
        val time = now()
        val out = ArrayList<Pair<CatalogueStream, Int>>(slots)
        var begun = 0
        for (stream in plan) {
            val cursor = index.cursors[stream.key] ?: continue
            if (cursor.startedAt != 0L && time - cursor.startedAt >= RECRAWL) {
                cursor.nextSkip = 0; cursor.complete = false; cursor.startedAt = 0; cursor.headAt = 0; cursor.ahead.clear(); index.dirty = true
            }
            if (cursor.nextSkip > 0 && begun++ < CONCURRENCY && time - cursor.headAt >= HEAD_TTL && out.size < slots) out += stream to 0
        }
        if (out.isNotEmpty() || satisfied()) return out
        val assigned = HashMap<String, Int>()
        var added = true
        while (added && out.size < slots) {
            added = false
            for (stream in plan) {
                if (out.size == slots) break
                val cursor = index.cursor(stream)
                if (cursor.complete) continue
                var skip = cursor.nextSkip + (assigned[stream.key] ?: 0) * CatalogueStream.PAGE
                while (cursor.ahead.containsKey(skip)) skip += CatalogueStream.PAGE
                if (skip > CatalogueStream.MAX_SKIP) { cursor.complete = true; index.dirty = true; continue }
                out += stream to skip
                assigned[stream.key] = (skip - cursor.nextSkip) / CatalogueStream.PAGE + 1
                added = true
            }
        }
        return out
    }

    private fun apply(index: CinemetaIndex, stream: CatalogueStream, skip: Int, page: CataloguePage) {
        val time = now()
        for (meta in page.metas) index.upsert(meta)
        val cursor = index.cursor(stream)
        val end = page.metas.isEmpty() || page.hasMore == false
        if (skip == 0) cursor.headAt = time
        if (cursor.startedAt == 0L) cursor.startedAt = time
        index.dirty = true
        if (cursor.complete) return
        if (skip > cursor.nextSkip) { cursor.ahead[skip] = end; return }
        if (skip < cursor.nextSkip) return
        var ended = end
        while (true) {
            cursor.nextSkip += CatalogueStream.PAGE
            if (ended) { cursor.complete = true; cursor.ahead.clear(); return }
            ended = cursor.ahead.remove(cursor.nextSkip) ?: return
        }
    }

    private fun plan(index: CinemetaIndex, filter: CinemetaFilter) =
        CoveragePlanner.plan(filter, LocalDate.ofEpochDay(Math.floorDiv(now(), 86_400_000L)).year) { complete(index, it) }

    private fun complete(index: CinemetaIndex, stream: CatalogueStream) =
        index.cursors[stream.key]?.let { it.complete && now() - it.startedAt < RECRAWL } == true

    private fun index(type: String): CinemetaIndex? =
        if (type !in CatalogueStream.TYPES) null else synchronized(lock) { indexes.getOrPut(type) { files.load(type) } }

    private fun ids(keys: Collection<String>): LongArray {
        if (keys.isEmpty()) return CinemetaIndex.NONE
        val out = LongArray(keys.size)
        var count = 0
        for (key in keys) { val id = CinemetaIds.fromKey(key); if (id > 0) out[count++] = id }
        return out.copyOf(count).also { it.sort() }
    }

    companion object {
        /** Cinemeta serves catalogue pages with Cache-Control max-age=14400: the same page is never requested again sooner. */
        const val PAGE_TTL = 4 * 3_600_000L
        internal const val HEAD_TTL = 24 * 3_600_000L
        internal const val RECRAWL = 7 * 24 * 3_600_000L
        internal const val RETRY = 30_000L
        const val CONCURRENCY = 3
        @Volatile private var instance: CinemetaCatalogue? = null

        /** One catalogue for the process. The index is read from disk on first use, off the main thread. */
        fun shared(context: Context): CinemetaCatalogue = instance ?: synchronized(this) {
            instance ?: StremioMetadataClient().let { client ->
                CinemetaCatalogue(File(context.applicationContext.noBackupFilesDir, "cinemeta-index"),
                    { stream, skip -> client.cataloguePage(stream.url(skip)) })
            }.also { instance = it }
        }
    }
}
