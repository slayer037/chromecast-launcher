package com.majendra.tvlauncher.providers

import com.majendra.tvlauncher.auth.StremioLiveCatalog
import com.majendra.tvlauncher.auth.StremioSearchCatalog
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.providers.cinemeta.CataloguePage
import com.majendra.tvlauncher.providers.cinemeta.CinemetaPageParser
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.net.InetAddress
import java.util.Collections
import kotlin.coroutines.resume

/**
 * Bounded metadata reads only. No stream requests, credentials, disk cache or request logging.
 * Every instance shares [StremioHttp]'s connection pool, and one description cache, unless given its own client.
 */
internal class StremioMetadataClient(private val client: OkHttpClient? = null) {
    private val http get() = client ?: StremioHttp.metadata

    suspend fun enrich(type: String, id: String): Pair<String?, String?> = enrichDetails(type, id).let {
        it.description to it.background
    }

    suspend fun enrichItem(item: ContentItem): ContentItem {
        val detail = enrichDetails(item.metadata["type"].orEmpty(), item.id)
        return detail.applyTo(item)
    }

    suspend fun enrichDetails(type: String, id: String): StremioMetadataDetails {
        if (!id.matches(imdbId) || type !in setOf("movie", "series")) return StremioMetadataDetails()
        val key = "$type/$id"
        descriptions[key]?.takeIf { System.currentTimeMillis() - it.at < 86_400_000 }?.let { return it.details }
        // Streamed: a long series' episode list is skipped instead of being built in memory.
        val meta = read("https://v3-cinemeta.strem.io/meta/$type/$id.json") { CinemetaPageParser.details(it) }
            ?: return StremioMetadataDetails()
        val details = StremioMetadataDetails(meta.description, meta.background, meta.ratingText, meta.genres)
        descriptions[key] = Cached(System.currentTimeMillis(), details)
        return details
    }

    /** Episode arrays stay only in the small foreground history reader, never the broad description cache. */
    suspend fun historyVideos(id: String): List<StremioEpisode> {
        if (!id.matches(seriesId)) return emptyList()
        val meta = json("https://v3-cinemeta.strem.io/meta/series/$id.json")?.optJSONObject("meta") ?: return emptyList()
        return com.majendra.tvlauncher.sync.StremioHistoryPolicy.episodes(meta)
    }

    /** Null means unavailable; an empty list means no usable published episode dates. */
    suspend fun upcomingVideos(id: String): List<UpcomingEpisode>? {
        if (!id.matches(seriesId)) return emptyList()
        val meta = json("https://v3-cinemeta.strem.io/meta/series/$id.json")?.optJSONObject("meta") ?: return null
        return UpcomingEpisodesPolicy.episodes(meta)
    }

    /** One catalogue or search page, parsed as it downloads. Null means the request or the response failed. */
    suspend fun cataloguePage(url: String?): CataloguePage? = url?.let { address -> read(address) { CinemetaPageParser.page(it) } }

    suspend fun searchCatalog(catalog: StremioSearchCatalog, query: String): CataloguePage? =
        cataloguePage(StremioSearchPolicy.catalogUrl(catalog.transportUrl, catalog.type, catalog.catalogId, query))

    suspend fun liveCatalog(catalog: StremioLiveCatalog): JSONObject? {
        val transport = catalog.transportUrl.toHttpUrlOrNull() ?: return null
        if (!StremioMetadataPolicy.publicUrl(catalog.transportUrl) || !transport.encodedPath.endsWith("/manifest.json")) return null
        val base = transport.encodedPath.removeSuffix("manifest.json")
        val url = transport.newBuilder().encodedPath(base).addPathSegment("catalog").addPathSegment(catalog.type)
            .let { if (catalog.extra == null) it.addPathSegment("${catalog.catalogId}.json") else it.addPathSegment(catalog.catalogId).addEncodedPathSegment("${catalog.extra}.json") }
            .build()
        return json(url.toString())
    }

    private suspend fun json(url: String): JSONObject? = read(url) { JSONObject(it.readText()) }

    /** At most 2 MB is ever read. [parse] runs on the HTTP thread; any failure is a null result. */
    private suspend fun <T> read(url: String, parse: (Reader) -> T?): T? = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        val body = it.body
                        if (!it.isSuccessful || body == null || body.contentLength() > MAX_BYTES) null
                        else parse(InputStreamReader(Limited(body.byteStream()), Charsets.UTF_8))
                    }
                } catch (_: IOException) { null } catch (_: org.json.JSONException) { null }
                if (continuation.isActive) continuation.resume(result)
            }
        })
    }

    /** Fails the read once a response passes the size limit, whatever length it declared. */
    private class Limited(input: InputStream) : FilterInputStream(input) {
        private var remaining = MAX_BYTES
        override fun read(): Int = super.read().also { if (it >= 0 && --remaining < 0) throw IOException("Response too large") }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also {
            if (it > 0) { remaining -= it; if (remaining < 0) throw IOException("Response too large") }
        }
    }

    private data class Cached(val at: Long, val details: StremioMetadataDetails)
    private companion object {
        const val MAX_BYTES = 2_000_000L
        val imdbId = Regex("tt[0-9]+")
        val seriesId = Regex("tt[0-9]{1,12}")
        // Public Cinemeta descriptions, memory only, shared by every instance.
        val descriptions: MutableMap<String, Cached> = Collections.synchronizedMap(object : LinkedHashMap<String, Cached>(128, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 160
        })
    }
}

internal object StremioMetadataPolicy {
    fun publicUrl(value: String): Boolean {
        val url = value.toHttpUrlOrNull() ?: return false
        val host = url.host.lowercase()
        return url.isHttps && url.port == 443 && url.username.isEmpty() && url.password.isEmpty() &&
            host.contains('.') && !host.endsWith(".local") && !host.endsWith(".localhost") &&
            !host.endsWith(".internal") && !host.matches(Regex("[0-9.]+")) && ':' !in host &&
            host != "localhost" && url.fragment == null
    }
    fun publicAddress(address: InetAddress): Boolean {
        val first = address.address.first().toInt() and 255
        return !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress && !address.isMulticastAddress &&
            !(address.address.size == 16 && (first and 254) == 252) &&
            !(address.address.size == 4 && (first == 0 || first >= 240 ||
                (first == 100 && ((address.address[1].toInt() and 255) in 64..127))))
    }
}
