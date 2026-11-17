package com.majendra.tvlauncher.providers

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.tv.TvContract
import com.majendra.tvlauncher.data.*
import com.majendra.tvlauncher.providers.youtube.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * YouTube rows: the user's published recommendations (TvContract, needs READ_TV_LISTINGS) split into category rows and topped up to
 * 20-40 videos each from logged-out InnerTube search; Subscriptions/Trending kept; Continue watching, Free movies and Music dropped.
 * Sleep/white-noise videos are filtered everywhere. Category rows still appear without the listings permission.
 */
class YouTubeProvider(private val context: Context, private val innerTube: InnerTubeClient = InnerTubeClient()): FeedAdapter, MemoryTrimmable {
    override val id = "youtube"
    override val displayName = "YouTube"
    private val packageName get() = Services.get(id).packageName
    private val gate = Semaphore(4)
    @Volatile private var cached: FeedResult? = null
    @Volatile private var cachedAt: Long? = null
    private data class Published(val seeds: List<YouTubeSeed> = emptyList(), val rows: List<ContentRow> = emptyList(), val granted: Boolean = true, val problem: String? = null)

    override suspend fun isAvailable() = context.packageManager.getLeanbackLaunchIntentForPackage(packageName) != null
    /** The ten-minute mix is the same rows the repository holds: drop it with them. */
    override fun trimMemory() { cached = null; cachedAt = null }
    override suspend fun load(forceRefresh: Boolean): FeedResult = loadProgressively(forceRefresh) {}
    override suspend fun loadProgressively(forceRefresh: Boolean, onPartial: suspend (FeedResult) -> Unit): FeedResult = withContext(Dispatchers.IO) {
        cached?.takeIf { !forceRefresh && System.currentTimeMillis() - (cachedAt ?: 0L) < CACHE_MS }?.let { return@withContext it.copy(updatedAt = System.currentTimeMillis()) }
        val random = Random(System.nanoTime())
        val published = published()
        val plans = YouTubeMix.plan(published.seeds, random)
        val done = ConcurrentHashMap<String, List<ContentItem>>()
        val failures = ConcurrentHashMap<String, String>()
        val emit = Mutex()
        fun rows(final: Boolean): List<ContentRow> {
            val ready = plans.filter { done[it.id]?.isNotEmpty() == true }
            val items = done.let { d -> ready.map { d.getValue(it.id) } }.let { if (final) YouTubeMix.dedupeAcrossRows(it) else it }
            return ready.zip(items).map { (plan, list) -> ContentRow("youtube-cat-${plan.id}", "Recommended · ${plan.label}", list) } + published.rows
        }
        if (published.rows.isNotEmpty()) onPartial(FeedResult(rows(false), FeedStatus.FRESH, "Loading YouTube recommendations…"))
        coroutineScope { plans.map { plan -> val planRandom = Random(random.nextLong()); async {
            done[plan.id] = try { fill(plan, planRandom) } catch (e: CancellationException) { throw e }
                catch (e: Exception) { failures[plan.id] = e.javaClass.simpleName; plan.seeds }
            emit.withLock { onPartial(FeedResult(rows(false), FeedStatus.FRESH, "Loading YouTube recommendations…")) }
        } }.awaitAll() }
        val contentRows = rows(true)
        FeedDiagnostics.event(context, "youtube_mix", mapOf("categories" to plans.size, "seeds" to published.seeds.size, "rows" to contentRows.size,
            "items" to contentRows.sumOf { it.items.size }, "failures" to failures.size, "permissionGranted" to published.granted, "force" to forceRefresh))
        val message = listOfNotNull("Recommended for you, by category • OK on Refresh for a new mix",
            if (!published.granted) "Allow TV listings to mix in YouTube's own picks" else published.problem).joinToString(" • ")
        val result = when {
            contentRows.any { it.items.isNotEmpty() } -> FeedResult(contentRows, FeedStatus.FRESH, message)
            !published.granted -> FeedResult(status = FeedStatus.PERMISSION_DENIED, message = "Allow TV listings to see YouTube's published recommendations.")
            else -> FeedResult(status = FeedStatus.SOURCE_UNAVAILABLE, message = "YouTube recommendations are temporarily unavailable. You can still open YouTube.",
                error = FeedError("provider_query", failures.values.firstOrNull() ?: "empty"))
        }
        if (result.status == FeedStatus.FRESH && failures.size < plans.size) { cached = result; cachedAt = System.currentTimeMillis() }
        result
    }

    /** Logged-out YouTube search: one "Results for …" row of up to 40 videos, same Shorts/live/noise filters, relevance order kept. */
    suspend fun search(query: String): FeedResult = withContext(Dispatchers.IO) {
        val q = query.trim().take(120)
        if (q.isEmpty()) return@withContext FeedResult(status = FeedStatus.NO_CONTENT, message = "Type something to search YouTube.")
        try {
            val first = gate.withPermit { innerTube.search(q) }
            fun usable(v: List<YouTubeVideo>) = v.count { it.playable && !YouTubeNoiseFilter.isNoise(it.title, it.channel) }
            var videos = first.videos; var next = first.continuation; var pages = 1
            while (usable(videos) < YouTubeMix.MAX_PER_ROW && next != null && pages < 3) {
                val page = runCatching { gate.withPermit { innerTube.search(q, next) } }.getOrNull() ?: break
                videos = videos + page.videos; next = page.continuation; pages++
            }
            val items = YouTubeMix.assemble(emptyList(), videos, packageName, null)
            FeedDiagnostics.event(context, "youtube_search", mapOf("items" to items.size, "pages" to pages))
            if (items.isEmpty()) FeedResult(listOf(ContentRow("youtube-search", "Results for “$q”", emptyList(), "No videos found for “$q”.")), FeedStatus.NO_CONTENT, "No YouTube videos found for “$q”.")
            else FeedResult(listOf(ContentRow("youtube-search", "Results for “$q”", items)), FeedStatus.FRESH, "YouTube search • ${items.size} videos")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            FeedDiagnostics.event(context, "youtube_search_error", mapOf("error" to e.javaClass.simpleName))
            FeedResult(status = FeedStatus.SOURCE_UNAVAILABLE, message = "YouTube search is unavailable right now. Try again shortly.", error = FeedError("search_failed", e.javaClass.simpleName))
        }
    }

    override suspend fun resolvePlayback(item: ContentItem): PlaybackResult = item.playbackUri?.let { PlaybackResult.Ready(it, packageName) }
        ?: PlaybackResult.App(packageName, "Video link unavailable")

    /** Tops a category up from seed- and category-derived searches until it can fill a row; the row is then shuffled and capped. */
    private suspend fun fill(plan: CategoryPlan, random: Random): List<ContentItem> = coroutineScope {
        val fetched = LinkedHashMap<String, YouTubeVideo>()
        val continuations = mutableListOf<Pair<String, String>>()
        fun usable() = plan.seeds.size + fetched.values.count { it.playable && !YouTubeNoiseFilter.isNoise(it.title, it.channel) }
        var lastError: Exception? = null
        suspend fun round(requests: List<Pair<String, String?>>) = requests.map { (q, token) -> async {
            try { q to gate.withPermit { innerTube.search(q, token) } } catch (e: CancellationException) { throw e } catch (e: Exception) { lastError = e; null }
        } }.awaitAll().filterNotNull().forEach { (q, page) -> page.videos.forEach { fetched.putIfAbsent(it.videoId, it) }; page.continuation?.let { continuations += q to it } }
        for (batch in plan.queries.chunked(3)) { if (usable() >= TARGET) break; round(batch.map { it to null }) }
        if (usable() < YouTubeMix.MIN_PER_ROW && continuations.isNotEmpty()) round(continuations.shuffled(random).take(3).map { it.first to it.second })
        if (fetched.isEmpty() && plan.seeds.isEmpty()) lastError?.let { throw it }
        YouTubeMix.assemble(plan.seeds, fetched.values.toList(), packageName, random)
    }

    private fun published(): Published {
        if (context.checkSelfPermission("android.permission.READ_TV_LISTINGS") != PackageManager.PERMISSION_GRANTED) return Published(granted = false)
        return try {
            val channels = linkedMapOf<String, Pair<String, YouTubeChannelPolicy.Kind>>()
            context.contentResolver.query(TvContract.Channels.CONTENT_URI, arrayOf("_id", "package_name", "display_name"), null, null, null)?.use { c ->
                while (c.moveToNext()) if (c.value("package_name") == packageName) c.value("display_name").let { channels[c.value("_id")] = it to YouTubeChannelPolicy.kind(it) }
            }
            val programs = channels.keys.associateWith { mutableListOf<YouTubeSeed>() }
            val columns = arrayOf("_id", "package_name", "channel_id", "title", "short_description", "author", "poster_art_uri", "intent_uri", "duration_millis")
            context.contentResolver.query(TvContract.PreviewPrograms.CONTENT_URI, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.value("package_name") != packageName) continue
                    val channel = c.value("channel_id"); val title = c.value("title")
                    if (title.isBlank() || channels[channel]?.second == YouTubeChannelPolicy.Kind.DROPPED) continue
                    val description = c.value("short_description")
                    val author = c.value("author").ifBlank { description.split(" · ", " • ", "•").first().trim().takeIf { it.length in 2..40 }.orEmpty() }
                    if (YouTubeNoiseFilter.isNoise(title, author) || YouTubeNoiseFilter.isNoise(description)) continue
                    val uri = c.value("intent_uri")
                    val videoId = Regex("(?:[?&]v=|youtu\\.be/|/watch/|/shorts/)([A-Za-z0-9_-]{11})").find(uri)?.groupValues?.get(1)
                    val art = c.value("poster_art_uri").takeIf { it.startsWith("https://") } ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }
                    val item = ContentItem(c.value("_id"), id, title, subtitle = description.ifBlank { author }.ifBlank { null }, imageUrl = art, landscapeImageUrl = art,
                        playbackUri = videoId?.let(YouTubeMix::watchUri) ?: uri.takeIf { it.isNotBlank() }, metadata = buildMap {
                            put("channelId", channel); put("package", packageName); put("channel", author); put("origin", "published")
                            videoId?.let { put("videoId", it) }; put("durationMillis", c.value("duration_millis"))
                        })
                    channels[channel]?.let { programs.getValue(channel).add(YouTubeSeed(title, author, item, it.second)) }
                }
            }
            val seeds = channels.keys.filter { channels.getValue(it).second != YouTubeChannelPolicy.Kind.OTHER }.flatMap { programs.getValue(it) }
            val rows = channels.filter { it.value.second !in setOf(YouTubeChannelPolicy.Kind.RECOMMENDED, YouTubeChannelPolicy.Kind.DROPPED) }
                .mapNotNull { (channel, info) -> programs.getValue(channel).takeIf { it.isNotEmpty() }?.let { ContentRow(channel, info.first, it.map { s -> s.item }.take(YouTubeMix.MAX_PER_ROW)) } }
            FeedDiagnostics.event(context, "youtube_query", mapOf("channels" to channels.size, "seeds" to seeds.size, "kept" to rows.size, "permissionGranted" to true))
            Published(seeds, rows)
        } catch (e: SecurityException) { Published(granted = false)
        } catch (e: Exception) {
            FeedDiagnostics.event(context, "youtube_error", mapOf("error" to e.toString()))
            Published(problem = "YouTube's own TV picks are unavailable")
        }
    }
    private fun Cursor.value(name: String): String { val i = getColumnIndex(name); return if (i >= 0 && !isNull(i)) getString(i).orEmpty() else "" }
    private companion object { const val CACHE_MS = 10 * 60_000L; const val TARGET = 30 }
}
