package com.majendra.tvlauncher.providers

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.majendra.tvlauncher.auth.StremioAccountClient
import com.majendra.tvlauncher.auth.StremioAccountResult
import com.majendra.tvlauncher.auth.StremioLibraryEntry
import com.majendra.tvlauncher.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** Account-authorised library/progress plus clearly labelled public Cinemeta discovery. */
class StremioProvider(private val context: Context, private val client: OkHttpClient? = null) : FeedAdapter {
    override val id = "stremio"
    override val displayName = "Stremio"
    private val packageName = "com.stremio.one"
    private val account = StremioAccountClient(context)
    private val metadataClient = StremioMetadataClient()
    private val http get() = client ?: StremioHttp.metadata
    private val imdbId = Regex("tt[0-9]+")

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            context.packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    override suspend fun load(forceRefresh: Boolean): FeedResult = loadProgressively(forceRefresh) {}

    private data class Update(val rows: List<ContentRow>, val notice: String? = null,
        val authRequired: Boolean = false, val failures: List<String> = emptyList())

    override suspend fun loadProgressively(forceRefresh: Boolean,
        onPartial: suspend (FeedResult) -> Unit): FeedResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val updates = kotlinx.coroutines.channels.Channel<Update>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val hadSession = account.store.hasSession()
            val metadataPermits = Semaphore(6)
            val jobs = mutableListOf<Job>()
            // Independent bounded requests: a slow account or add-on never holds discovery rows.
            for ((type, label) in listOf("movie" to "Discover movies", "series" to "Discover TV shows")) {
                jobs += launch {
                    val failures = mutableListOf<String>()
                    var row: ContentRow? = null
                    var loaded = false
                    try {
                        val request = Request.Builder()
                            // The verified final host: the shared metadata client does not follow redirects.
                            .url(StremioSearchPolicy.discoveryUrl(type)!!)
                            .header("Accept", "application/json").build()
                        http.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                failures += "$type: HTTP ${response.code}"
                            } else {
                                val body = response.body
                                if (body == null || body.contentLength() > 2_000_000) {
                                    failures += "$type: invalid response size"
                                } else {
                                    val source = body.source()
                                    source.request(2_000_001)
                                    if (source.buffer.size > 2_000_000) {
                                        failures += "$type: response too large"
                                    } else {
                                        val metas = JSONObject(source.readUtf8()).optJSONArray("metas")
                                        val items = mutableListOf<ContentItem>()
                                        for (index in 0 until minOf(metas?.length() ?: 0, 100)) {
                                            val meta = metas?.optJSONObject(index) ?: continue
                                            val itemId = meta.optString("id")
                                            val title = meta.optString("name")
                                            if (itemId.isBlank() || title.isBlank() || !itemId.matches(imdbId)) continue
                                            items += ContentItem(
                                                id = itemId, source = id, title = title,
                                                subtitle = meta.textOrNull("releaseInfo"),
                                                description = meta.textOrNull("description"),
                                                imageUrl = meta.textOrNull("poster"),
                                                landscapeImageUrl = meta.textOrNull("background"),
                                                playbackUri = detailUri(type, itemId),
                                                metadata = mapOf("type" to type, "package" to packageName,
                                                    "catalog" to "Cinemeta Popular", "personalised" to "false") +
                                                    StremioSearchPolicy.imdbRating(meta.textOrNull("imdbRating"))?.let {
                                                        mapOf("imdbRating" to it, "imdbRatingSource" to "Cinemeta") }.orEmpty()
                                            )
                                            if (items.size >= 40) break
                                        }
                                        if (items.isNotEmpty()) {
                                            row = ContentRow("stremio-$type", label, items)
                                            loaded = true
                                        }
                                    }
                                }
                            }
                        }
                    } catch (_: IOException) {
                        failures += "$type: network unavailable"
                    } catch (_: JSONException) {
                        failures += "$type: invalid catalogue response"
                    }
                    if (!loaded) row = ContentRow("stremio-$type", label, emptyList(), "This discovery catalogue could not be loaded. Open Stremio or try again shortly.")

                    updates.send(Update(listOfNotNull(row), failures = failures))
                    row?.takeIf { it.items.isNotEmpty() }?.let { loadedRow ->
                        val details = java.util.concurrent.ConcurrentHashMap<String, StremioMetadataDetails>()
                        val completed = java.util.concurrent.atomic.AtomicInteger()
                        fun enrichedRow() = loadedRow.copy(items = loadedRow.items.map { item ->
                            val detail = details[item.id]
                            detail?.applyTo(item) ?: item
                        })
                        withTimeoutOrNull(6_000) {
                            loadedRow.items.map { item -> async {
                                metadataPermits.withPermit {
                                    if (item.description.isNullOrBlank() || item.landscapeImageUrl.isNullOrBlank())
                                        details[item.id] = metadataClient.enrichDetails(type, item.id)
                                    if (completed.incrementAndGet() % 6 == 0) updates.send(Update(listOf(enrichedRow())))
                                }
                            } }.awaitAll()
                        }
                        updates.send(Update(listOf(enrichedRow())))
                    }
                }
            }
            if (context.liveTvEnabled) jobs += launch {
                val liveResult = account.loadLiveCatalogs()
                val liveItems = java.util.Collections.synchronizedList(mutableListOf<ContentItem>())
                if (liveResult is StremioAccountResult.Ready) {
                    withTimeoutOrNull(5_000) {
                        val permits = Semaphore(3)
                        liveResult.value.take(12).map { catalog -> async {
                            permits.withPermit {
                                val response = metadataClient.liveCatalog(catalog)
                                val metas = response?.optJSONArray("metas") ?: response?.optJSONArray("metasDetailed")
                                val items = (0 until minOf(metas?.length() ?: 0, 40)).mapNotNull { index ->
                                    val meta = metas?.optJSONObject(index) ?: return@mapNotNull null
                                    val itemId = meta.optString("id")
                                    val title = meta.optString("name")
                                    if (itemId.isBlank() || itemId.length > 512 || title.isBlank()) null else ContentItem(
                                        id = itemId, source = id, title = title,
                                        description = meta.textOrNull("description"), imageUrl = meta.textOrNull("poster"),
                                        landscapeImageUrl = meta.textOrNull("background"), playbackUri = detailUri(catalog.type, itemId),
                                        metadata = mapOf("type" to catalog.type, "package" to packageName,
                                            "accountAuthorised" to "true", "catalog" to "Installed live TV add-on"))
                                }
                                liveItems.addAll(items)
                            }
                        } }.awaitAll()
                    }
                }
                val message = when {
                    liveResult is StremioAccountResult.Failure -> liveResult.message
                    liveResult is StremioAccountResult.Ready && liveResult.value.isEmpty() ->
                        "Your installed Stremio add-ons do not publish a live TV catalogue. Add a live TV catalogue in Stremio to show it here."
                    else -> "Your installed live TV add-ons returned no accessible channels. Open Stremio to check the add-ons."
                }
                updates.send(Update(listOf(ContentRow("stremio-live", "Live TV shows", liveItems.distinctBy { it.id }.take(40), message))))
            }
            launch { jobs.joinAll(); updates.close() }
            val state = StremioProgressiveRows()
            var notice = ""
            var expiredSession = false
            val failed = mutableListOf<String>()
            fun snapshot() = FeedResult(rows = state.rows(), status = when {
                expiredSession -> FeedStatus.AUTHENTICATION_REQUIRED
                state.rows().any { it.items.isNotEmpty() } -> FeedStatus.FRESH
                failed.isNotEmpty() -> FeedStatus.SOURCE_UNAVAILABLE
                else -> FeedStatus.NO_CONTENT
            }, message = "$notice Discover movies and series are public Cinemeta catalogues, not personal recommendations." +
                if (failed.isNotEmpty()) " Some catalogues unavailable: ${failed.joinToString("; ")}." else "",
                error = if (failed.isEmpty()) null else FeedError("cinemeta_unavailable", failed.joinToString("; ")))
            for (update in updates) {
                state.merge(update.rows)
                update.notice?.let { notice = it }
                expiredSession = expiredSession || update.authRequired
                failed += update.failures
                onPartial(snapshot())
            }
            snapshot()
        }
    }

    private fun enrichedItem(entry: StremioLibraryEntry, enriched: Map<String, StremioMetadataDetails>): ContentItem {
        val metadata = enriched[entry.id]
        return metadata?.applyTo(accountItem(entry)) ?: accountItem(entry)
    }

    override suspend fun resolvePlayback(item: ContentItem): PlaybackResult {
        val type = item.metadata["type"] ?: return PlaybackResult.Unavailable("This item has no supported Stremio details link.")
        return if (item.source == id && type in setOf("movie", "series", "tv", "channel") && item.id.isNotBlank() && item.id.length <= 512) {
            PlaybackResult.Ready(detailUri(type, item.id, item.metadata["videoId"]), packageName)
        } else PlaybackResult.Unavailable("This item has no supported Stremio details link.")
    }

    private fun detailUri(type: String, itemId: String, videoId: String? = null): String {
        val base = "stremio:///detail/$type/${Uri.encode(itemId)}"
        return if (!videoId.isNullOrBlank()) "$base/${Uri.encode(videoId)}"
            else if (type == "movie") "$base/${Uri.encode(itemId)}" else base
    }

    private fun accountItem(entry: StremioLibraryEntry) = ContentItem(
        id = entry.id, source = id, title = entry.name,
        imageUrl = entry.poster,
        progress = if (entry.duration > 0 && entry.timeOffset > 0)
            (entry.timeOffset.toDouble() / entry.duration.toDouble()).coerceIn(0.0, 1.0).toFloat() else null,
        playbackUri = detailUri(entry.type, entry.id, entry.videoId),
        metadata = buildMap {
            put("type", entry.type); put("package", packageName); put("accountAuthorised", "true")
            entry.videoId?.let { put("videoId", it) }
        }
    )

    private fun JSONObject.textOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

object StremioContinuePolicy {
    fun isUnfinished(position: Long, duration: Long) = position > 0 && (duration <= 0 || position < duration)
}
