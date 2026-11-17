package com.majendra.tvlauncher.providers

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.majendra.tvlauncher.auth.*
import com.majendra.tvlauncher.data.*
import com.majendra.tvlauncher.providers.cinemeta.CatalogueMeta
import com.majendra.tvlauncher.providers.cinemeta.CataloguePage
import com.majendra.tvlauncher.providers.cinemeta.CatalogueStream
import com.majendra.tvlauncher.providers.cinemeta.CinemetaCatalogue
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.majendra.tvlauncher.sync.StremioHistoryPolicy
import com.majendra.tvlauncher.sync.StremioHistorySource
import java.util.concurrent.ConcurrentHashMap

/**
 * Public discovery and user-authorised installed add-on search. Search results, account rows and configured
 * transports are memory only. The public Cinemeta pages read for the rows are also offered to the on-disk
 * [CinemetaCatalogue], and the repository saves the public browse rows of a complete load.
 */
class StremioDiscoveryProvider internal constructor(context: Context, override val id: String, val type: String,
    private val client: StremioMetadataClient, private val catalogue: CinemetaCatalogue?) : FeedAdapter {
    constructor(context: Context, id: String, type: String) :
        this(context, id, type, StremioMetadataClient(), CinemetaCatalogue.shared(context))
    private val context = context.applicationContext
    override val displayName = if (type == "movie") "Movies" else "TV shows"
    private val account = StremioAccountClient(context)
    private data class Catalogs(val generation: Long, val at: Long, val values: List<StremioSearchCatalog>)
    @Volatile private var cachedCatalogs: Catalogs? = null
    private val visit = StremioVisit()
    private class Page(val at: Long, val items: List<ContentItem>)
    // Rated cards of public pages already read, for as long as Cinemeta says the page may be reused.
    private val pages = ConcurrentHashMap<String, Page>()
    private class Loaded(val row: ContentRow?, val unavailable: Boolean = false, val incomplete: Boolean = false)
    init { require(type in setOf("movie", "series")) }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try { context.packageManager.getApplicationInfo("com.stremio.one", 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }
    }
    override suspend fun load(forceRefresh: Boolean): FeedResult = loadProgressively(forceRefresh) {}

    /** The section was opened afresh: the next load may read a different deeper page of each genre. */
    fun newVisit() = visit.renew()

    private val minimum = StremioGenrePolicy.minimum(type).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }
    private fun popularRow(items: List<ContentItem>) = ContentRow(StremioGenrePolicy.topRowId(id), "Popular now · IMDb $minimum+", items)

    /**
     * Popular now alone, asked of Cinemeta again whatever was read before: one request, for the repository to put into
     * genre rows it is holding for longer. Public data only. Null when the page could not be read or held no rated title.
     * The catalogue index is left alone, so a restarted launcher does not load it just for this row.
     */
    suspend fun popular(): ContentRow? = withContext(Dispatchers.IO) {
        val url = CatalogueStream.top(type).url(0) ?: return@withContext null
        val items = rated(client.cataloguePage(url) ?: return@withContext null, "Cinemeta Popular")
        pages[url] = Page(System.currentTimeMillis(), items)
        if (items.isEmpty()) null else popularRow(DiscoveryWorkloadPolicy.limit(context.lightweightLauncher, items))
    }

    /** A popular row, then one row per genre, each kept to titles at or above the IMDb minimum. */
    override suspend fun loadProgressively(forceRefresh: Boolean,
        onPartial: suspend (FeedResult) -> Unit): FeedResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val lightweight = context.lightweightLauncher
            val genres = StremioGenrePolicy.genres(type)
            val permits = Semaphore(DiscoveryWorkloadPolicy.concurrency(lightweight))
            val history = StremioHistorySource.shared(context)
            val initialGeneration = account.store.generation
            val seed = visit.seed(System.currentTimeMillis())
            // Account rows are optional: having nothing to resume is not a failed load.
            val accountTasks = if (type == "movie") listOf(StremioHistoryPolicy.MOVIES_ROW to suspend {
                val read = history.load(emptySet(), forceRefresh)
                Loaded(if (history.isCurrent(read) && read.continueMovies.isNotEmpty())
                    ContentRow(StremioHistoryPolicy.MOVIES_ROW, "Continue watching", read.continueMovies) else null)
            }) else emptyList()
            val publicTasks = listOf(StremioGenrePolicy.topRowId(id) to suspend {
                // Always asked again: the repository counts Popular now's hour from this load.
                val items = page(CatalogueStream.top(type), 0, "Cinemeta Popular", reuse = false)
                Loaded(items?.let(::popularRow), unavailable = items == null)
            }) + genres.map { genre -> StremioGenrePolicy.rowId(id, genre) to suspend {
                // Chromecast variant reads the two most popular pages of each genre; TCL adds a deeper page that changes each visit.
                val requested = DiscoveryWorkloadPolicy.pages(lightweight, seed, genre)
                    .map { skip -> async { page(CatalogueStream.top(type, genre), skip, "Cinemeta $genre") } }.awaitAll()
                val read = requested.filterNotNull()
                Loaded(if (read.isEmpty()) null else ContentRow(StremioGenrePolicy.rowId(id, genre), genre, read.flatten().distinctBy { it.id }),
                    unavailable = read.isEmpty(), incomplete = read.isNotEmpty() && read.size < requested.size)
            } }
            val tasks = accountTasks + publicTasks
            val updates = kotlinx.coroutines.channels.Channel<Pair<Int, Loaded>>(tasks.size)
            val jobs = tasks.mapIndexed { index, task -> launch { updates.send(index to permits.withPermit { task.second() }) } }
            launch { jobs.joinAll(); updates.close() }
            val rows = arrayOfNulls<ContentRow>(tasks.size)
            val unavailable = mutableListOf<String>()
            val incomplete = mutableListOf<String>()
            fun snapshot(): FeedResult {
                if (account.store.generation != initialGeneration) return FeedResult(message = "Account changed. Refresh Movies.")
                val loaded = rows.filterNotNull().filter { it.items.isNotEmpty() }
                val (status, error) = StremioLoadPolicy.outcome(loaded.isNotEmpty(), publicTasks.size, unavailable, incomplete)
                return FeedResult(rows = loaded, status = status,
                    message = "Titles rated IMDb $minimum or higher, by genre, in a new order each visit." +
                        if (error != null) " Some catalogues could not be loaded." else "", error = error)
            }
            for ((index, loaded) in updates) {
                rows[index] = loaded.row?.let {
                    if (it.id == StremioHistoryPolicy.MOVIES_ROW) it
                    else it.copy(items = DiscoveryWorkloadPolicy.limit(lightweight, it.items))
                }
                if (loaded.unavailable) unavailable += tasks[index].first
                if (loaded.incomplete) incomplete += tasks[index].first
                onPartial(snapshot())
            }
            catalogue?.flush()
            snapshot()
        }
    }

    /** Null when the page could not be read. With [reuse], a page read less than four hours ago is not asked for again. */
    private suspend fun page(stream: CatalogueStream, skip: Int, label: String, reuse: Boolean = true): List<ContentItem>? {
        val url = stream.url(skip) ?: return null
        val now = System.currentTimeMillis()
        if (reuse) pages[url]?.takeIf { now - it.at in 0 until CinemetaCatalogue.PAGE_TTL }?.let { return it.items }
        val loaded = client.cataloguePage(url) ?: return null
        catalogue?.observe(stream, skip, loaded)
        val items = rated(loaded, label)
        pages.entries.removeAll { now - it.value.at !in 0 until CinemetaCatalogue.PAGE_TTL }
        pages[url] = Page(now, items)
        return items
    }

    private fun rated(page: CataloguePage, label: String): List<ContentItem> =
        page.metas.mapNotNull { item(it, label, false) }.filter { StremioGenrePolicy.meetsMinimum(type, it.metadata["imdbRating"]) }

    suspend fun search(query: String): FeedResult = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.isBlank() || text.length > 200) return@withContext FeedResult(status = FeedStatus.NO_CONTENT,
            message = "Enter a search of 1 to 200 characters.")
        val generation = account.store.generation
        val result = coroutineScope {
            val public = async { client.searchCatalog(StremioSearchCatalog("https://v3-cinemeta.strem.io/manifest.json", type, "top"), text) }
            val catalogs = cachedCatalogs?.takeIf { it.generation == generation && System.currentTimeMillis() - it.at < 300_000 }?.values
                ?: when (val loaded = account.loadSearchCatalogs(type)) {
                    is StremioAccountResult.Ready -> loaded.value.also {
                        account.store.withGeneration(generation) { cachedCatalogs = Catalogs(generation, System.currentTimeMillis(), it) }
                    }
                    else -> emptyList()
                }
            val permits = Semaphore(3)
            val addons = withTimeoutOrNull(10_000) {
                catalogs.filterNot { it.transportUrl == "https://v3-cinemeta.strem.io/manifest.json" && it.catalogId == "top" }
                    .map { catalog -> async { permits.withPermit { client.searchCatalog(catalog, text) } } }.awaitAll()
            }.orEmpty()
            val cinemeta = public.await()
            // Search results are never indexed: they reflect what was typed and which add-ons are installed.
            val items = StremioSearchPolicy.deduplicate(cinemeta?.metas.orEmpty().mapNotNull { item(it, "Cinemeta search", false) } +
                addons.flatMap { page -> page?.metas.orEmpty().mapNotNull { item(it, "Installed add-on search", true) } })
            result(items, "Cinemeta and supported installed add-on catalogues. Some add-ons only support search inside Stremio.",
                failed = cinemeta == null && addons.none { it != null })
        }
        account.store.withGeneration(generation) { result }
            ?: FeedResult(status = FeedStatus.NO_CONTENT, message = "Account changed. Search again.")
    }

    override suspend fun resolvePlayback(item: ContentItem): PlaybackResult {
        val itemType = item.metadata["type"]
        if (item.source != "stremio" || itemType !in setOf("movie", "series") || item.id.isBlank() || item.id.length > 512)
            return PlaybackResult.Unavailable("This title has no supported Stremio details link.")
        val base = "stremio:///detail/$itemType/${Uri.encode(item.id)}"
        return PlaybackResult.Ready(base + if (itemType == "movie") "/${Uri.encode(item.id)}" else "", "com.stremio.one")
    }

    suspend fun enrichItem(item: ContentItem): ContentItem = client.enrichItem(item)

    private fun result(items: List<ContentItem>, message: String, failed: Boolean) = FeedResult(
        rows = listOf(ContentRow("$id-results", if (type == "movie") "Movies" else "TV shows", items,
            "No matching titles. You can also search inside Stremio.")),
        status = when { items.isNotEmpty() -> FeedStatus.FRESH; failed -> FeedStatus.SOURCE_UNAVAILABLE; else -> FeedStatus.NO_CONTENT },
        message = message)

    /** Year, genres ("A|B") and runtime (minutes) travel in the metadata map, beside the existing keys. */
    private fun item(meta: CatalogueMeta, catalog: String, authorised: Boolean): ContentItem? {
        if ((meta.type ?: type) != type) return null
        return ContentItem(id = meta.id, source = "stremio", title = meta.name, subtitle = meta.releaseInfo,
            description = meta.description, imageUrl = meta.poster, landscapeImageUrl = meta.background,
            playbackUri = "stremio:///detail/$type/${Uri.encode(meta.id)}" + if (type == "movie") "/${Uri.encode(meta.id)}" else "",
            metadata = buildMap {
                put("type", type); put("package", "com.stremio.one"); put("catalog", catalog); put("personalised", "false")
                if (authorised) put("accountAuthorised", "true")
                // IMDb provenance is only asserted for the Cinemeta source, not arbitrary add-on claims.
                if (!authorised) meta.ratingText?.let { put("imdbRating", it); put("imdbRatingSource", "Cinemeta") }
                if (meta.year > 0) put("year", meta.year.toString())
                if (meta.runtime > 0) put("runtime", meta.runtime.toString())
                if (meta.genres.isNotEmpty()) put("genres", meta.genres.joinToString("|"))
                // Lets search recognise an add-on's own entry for a title Cinemeta lists under its IMDb id.
                meta.imdbId?.takeIf { it != meta.id }?.let { put("imdbId", it) }
            })
    }
}
