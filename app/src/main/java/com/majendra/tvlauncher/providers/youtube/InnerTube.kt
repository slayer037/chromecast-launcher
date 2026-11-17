package com.majendra.tvlauncher.providers.youtube

import com.majendra.tvlauncher.data.LauncherHttp
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class YouTubeVideo(val videoId: String, val title: String, val channel: String, val lengthText: String,
    val lengthSeconds: Int, val viewCountText: String = "", val publishedText: String = "", val live: Boolean = false, val short: Boolean = false) {
    val thumbnail get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    /** Shorts, live/upcoming streams and anything under a minute never reach a row. */
    val playable get() = !live && !short && lengthSeconds >= 60 && VIDEO_ID.matches(videoId) && title.isNotBlank()
    companion object { val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}") }
}
data class InnerTubePage(val videos: List<YouTubeVideo>, val continuation: String?)

/** Parses the public (logged-out) WEB search response: `videoRenderer`s anywhere in the tree plus the trailing continuation token. */
object InnerTubeParser {
    fun parse(json: JSONObject): InnerTubePage {
        val videos = mutableListOf<YouTubeVideo>(); var continuation: String? = null
        fun walk(node: Any?) { when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { r -> video(r)?.let(videos::add) }
                node.optJSONObject("continuationItemRenderer")?.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")
                    ?.optString("token")?.takeIf { it.isNotBlank() }?.let { continuation = it }
                node.keys().forEach { if (it != "videoRenderer") walk(node.opt(it)) }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i))
        } }
        walk(json)
        return InnerTubePage(videos.distinctBy { it.videoId }, continuation)
    }
    fun video(r: JSONObject): YouTubeVideo? {
        val id = r.optString("videoId").takeIf { YouTubeVideo.VIDEO_ID.matches(it) } ?: return null
        val length = text(r.optJSONObject("lengthText"))
        val badges = r.optJSONArray("badges")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optJSONObject("metadataBadgeRenderer") } }.orEmpty()
        val live = r.has("upcomingEventData") || badges.any { it.optString("style").contains("LIVE") || it.optString("label").equals("LIVE", true) } ||
            r.optJSONArray("thumbnailOverlays")?.toString()?.contains("\"LIVE\"") == true
        val url = r.optJSONObject("navigationEndpoint")?.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
        return YouTubeVideo(id, text(r.optJSONObject("title")), text(r.optJSONObject("ownerText")).ifBlank { text(r.optJSONObject("longBylineText")) },
            length, seconds(length), text(r.optJSONObject("viewCountText")), text(r.optJSONObject("publishedTimeText")), live, url.startsWith("/shorts/"))
    }
    fun text(o: JSONObject?): String = o?.optString("simpleText")?.takeIf { it.isNotBlank() }
        ?: o?.optJSONArray("runs")?.let { a -> (0 until a.length()).joinToString("") { a.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
    /** "1:02:03" -> 3723; blank or malformed -> 0 (treated as live/unknown and excluded). */
    fun seconds(length: String): Int = length.trim().split(":").takeIf { p -> p.size in 1..3 && p.all { it.isNotEmpty() && it.all(Char::isDigit) } }
        ?.fold(0) { total, part -> total * 60 + part.toInt() } ?: 0
}

class InnerTubeClient(custom: OkHttpClient? = null) {
    // Derived on the first search, not when the YouTube section is constructed.
    private val client by lazy { custom ?: LauncherHttp.client.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build() }
    /** One logged-out search page (videos-only filter); pass [continuation] for the next page of the same search. */
    fun search(query: String, continuation: String? = null): InnerTubePage {
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject().put("clientName", "WEB")
            .put("clientVersion", CLIENT_VERSION).put("hl", "en").put("gl", "AU")))
            .apply { if (continuation != null) put("continuation", continuation) else put("query", query).put("params", VIDEOS_ONLY) }
        val request = Request.Builder().url(ENDPOINT).header("User-Agent", USER_AGENT).header("Accept-Language", "en-AU,en;q=0.8")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("InnerTube HTTP ${response.code}")
            val raw = response.body ?: throw IOException("Empty InnerTube response")
            if (raw.contentLength() > MAX_BYTES) throw IOException("InnerTube response exceeds size limit")
            val out = ByteArrayOutputStream()
            raw.byteStream().use { input -> val buffer = ByteArray(16 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > MAX_BYTES) throw IOException("InnerTube response exceeds size limit"); out.write(buffer, 0, n) } }
            return InnerTubeParser.parse(JSONObject(out.toString(Charsets.UTF_8.name())))
        }
    }
    companion object {
        private const val ENDPOINT = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false"
        private const val CLIENT_VERSION = "2.20251020.01.00"
        private const val VIDEOS_ONLY = "EgIQAQ%3D%3D"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
        private const val MAX_BYTES = 3 * 1024 * 1024
    }
}
