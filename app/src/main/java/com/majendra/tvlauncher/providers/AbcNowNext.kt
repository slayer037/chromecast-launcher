package com.majendra.tvlauncher.providers

import android.content.Context
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.data.LauncherHttp
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** ABC's public website Now/Next metadata; no authentication or player endpoints. */
class AbcNowNext(context: Context) {
    private val cache = File(context.filesDir, "abc-now-next.json")
    private val client by lazy { LauncherHttp.client.newBuilder().connectTimeout(3, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS).build() }
    private var lastAttempt = 0L
    private val streams = listOf(
        Triple("ABC Drama", "LS2506H001S00", "X0_698bd3047de70_2000.jpg"),
        Triple("ABC Comedy", "LS2507H001S00", "X0_698bd396957de_2000.jpg"),
        Triple("ABC Lifestyle", "LS2508H001S00", "X0_698bbfff0f31f_2000.jpg"),
        Triple("Back Roads", "LS2509H001S00", "X0_698bc08370b73_2000.jpg")
    )

    suspend fun load(): List<ContentRow> = coroutineScope {
        val now = System.currentTimeMillis()
        if ((!cache.isFile || now - cache.lastModified() >= 60_000) && now - lastAttempt >= 30_000) {
            lastAttempt = now
            val results = streams.map { (name, code, image) -> async(Dispatchers.IO) {
                try {
                    client.newCall(Request.Builder().url("$SOURCE/$code").build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        val body = response.body ?: return@use null
                        val text = body.source().readUtf8Bounded()
                        val payload = JSONObject(text)
                        val programmes = JSONArray()
                        listOf("now", "next").forEach { field -> payload.optJSONObject(field)?.let { p ->
                            val start = p.optLong("start") * 1000L
                            val end = p.optLong("end") * 1000L
                            val title = p.optString("displayTitle")
                            if (title.isNotBlank() && start > 0 && end > start) programmes.put(JSONObject()
                                .put("title", title).put("subtitle", p.optString("displaySubtitle"))
                                .put("description", p.optString("description")).put("start", start).put("end", end))
                        } }
                        JSONObject().put("name", name).put("code", code).put("image", image).put("programmes", programmes)
                    }
                } catch (_: IOException) { null }
                catch (_: org.json.JSONException) { null }
            } }.awaitAll().filterNotNull()
            if (results.isNotEmpty()) runCatching {
                // Retain failed channels' saved schedules; boundaries below prevent stale LIVE labels.
                val old = saved()?.optJSONArray("channels").objects().filter { previous -> results.none { it.optString("code") == previous.optString("code") } }
                val safe = JSONObject().put("channels", JSONArray(results + old))
                val temp = File(cache.path + ".tmp")
                try { temp.writeText(safe.toString()); if (!temp.renameTo(cache)) throw IOException("Cannot store ABC metadata") }
                finally { temp.delete() }
            }
        }
        val channels = saved()?.optJSONArray("channels").objects()
        val current = mutableListOf<ContentItem>(); val next = mutableListOf<ContentItem>()
        val evaluated = System.currentTimeMillis()
        channels.forEach { ch -> ch.optJSONArray("programmes").objects().forEach { p ->
            val start = p.optLong("start"); val end = p.optLong("end")
            val live = start <= evaluated && evaluated < end
            if (end > start && (live || start > evaluated)) {
                val code = ch.optString("code")
                val item = ContentItem("abc:$code:$start", "abc", p.optString("title"),
                    listOf(ch.optString("name"), p.optString("subtitle")).filter { it.isNotBlank() }.joinToString(" · "),
                    p.optString("description"), "https://cdn.iview.abc.net.au/thumbs/200/${ch.optString("image")}",
                    startTime = start, endTime = end, isLive = live,
                    progress = if (live) ((evaluated - start).toDouble() / (end - start)).toFloat().coerceIn(0f, 1f) else null,
                    metadata = mapOf("channelName" to ch.optString("name"), "integration" to "official_now_next",
                        "guideSource" to "$SOURCE/$code", "officialPage" to "https://iview.abc.net.au/video/$code",
                        "scheduleOnly" to "true", "playbackFallback" to "app"))
                if (live) current.add(item) else next.add(item)
            }
        } }
        listOf(ContentRow("abc-extra-now", "On ABC streaming channels", current),
            ContentRow("abc-extra-next", "Next on ABC streaming channels", next)).filter { it.items.isNotEmpty() }
    }
    private fun saved(): JSONObject? = runCatching {
        if (cache.isFile && cache.length() <= MAX_BYTES) JSONObject(cache.readText()) else null
    }.getOrNull()
    private fun okio.BufferedSource.readUtf8Bounded(): String {
        if (request(MAX_BYTES + 1L)) throw IOException("ABC metadata exceeds size limit")
        return readUtf8()
    }
    private fun JSONArray?.objects() = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    companion object {
        const val SOURCE = "https://api.iview.abc.net.au/v3/nownext"
        private const val MAX_BYTES = 512 * 1024
    }
}
