package com.majendra.tvlauncher.providers

import android.content.Context
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.data.LauncherHttp
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Public catalogue metadata only; never calls player, authentication or stream endpoints. */
class BroadcasterExtras(context: Context, private val service: String) {
    private val cache = File(context.filesDir, "broadcaster-$service-metadata.json")
    private val mutex = Mutex()
    // Ten of these exist (five broadcasters, in two sections): only the ABC one needs this, and only when it loads.
    private val abc by lazy { AbcNowNext(context) }
    private var attemptedAt = 0L
    private val client by lazy { LauncherHttp.client.newBuilder().connectTimeout(8, TimeUnit.SECONDS).callTimeout(18, TimeUnit.SECONDS).build() }
    suspend fun load(): List<ContentRow> = mutex.withLock {
        val now = System.currentTimeMillis()
        if (service == "abc") return@withLock abc.load()
        if (service !in setOf("7plus", "9now")) return@withLock emptyList()
        if ((!cache.exists() || now - cache.lastModified() > 5 * 60_000L) && now - attemptedAt >= 60_000L) {
            attemptedAt = now
            runCatching {
                val source = if (service == "7plus") SEVEN else NINE
                val payload = client.newCall(Request.Builder().url(source).header("User-Agent", "ChromecastLauncher/1.0 metadata").build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Metadata HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Missing metadata")
                    if (body.contentLength() > MAX_BYTES) throw IOException("Metadata exceeds size limit")
                    body.byteStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) { val count = input.read(buffer); if (count < 0) break
                            if (output.size() + count > MAX_BYTES) throw IOException("Metadata exceeds size limit")
                            output.write(buffer, 0, count)
                        }; JSONObject(output.toString("UTF-8"))
                    }
                }
                // Persist only allowlisted catalogue fields. Stream/DRM/auth fields are discarded.
                val channels = if (service == "7plus") seven(payload) else nine(payload)
                if (channels.length() == 0) throw IOException("No public channel metadata")
                val safe = JSONObject().put("retrievedAt", now).put("channels", channels)
                val temp = File(cache.path + ".tmp")
                try { temp.writeText(safe.toString()); if (!temp.renameTo(cache)) throw IOException("Cannot store metadata") }
                finally { temp.delete() }
            }
        }
        val safe = runCatching { if (cache.isFile && cache.length() <= MAX_BYTES) JSONObject(cache.readText()) else null }.getOrNull() ?: return@withLock emptyList()
        val scheduleNow = System.currentTimeMillis()
        // Expired schedules never remain marked live. Do not retain indefinitely stale directories.
        if (scheduleNow - safe.optLong("retrievedAt") > 24 * 60 * 60_000L) return@withLock emptyList()
        val current = mutableListOf<ContentItem>(); val next = mutableListOf<ContentItem>()
        safe.optJSONArray("channels")?.objects()?.forEach { channel ->
            val programmes = channel.optJSONArray("programmes")?.objects().orEmpty()
            programmes.filter { it.optLong("start") <= scheduleNow && scheduleNow < it.optLong("end") }.maxByOrNull { it.optLong("start") }?.let {
                current.add(item(channel, it, scheduleNow, true, safe.optLong("retrievedAt")))
            }
            programmes.filter { it.optLong("start") > scheduleNow }.minByOrNull { it.optLong("start") }?.let {
                next.add(item(channel, it, scheduleNow, false, safe.optLong("retrievedAt")))
            }
        }
        listOf(ContentRow("$service-extra-now", "Extra live channels & events", current),
            ContentRow("$service-extra-next", "Next on extra channels", next)).filter { it.items.isNotEmpty() }
    }
    private fun seven(payload: JSONObject): JSONArray {
        val channels = JSONArray(); val seen = mutableSetOf<String>()
        payload.optJSONArray("items")?.objects()?.filter { it.optString("childType") == "channelItem" }?.forEach { section ->
            section.optJSONArray("mediaItems")?.objects()?.filter { it.optString("type") == "ChannelItem" }?.forEach { ch ->
                val id = ch.optString("channelId")
                if (id !in setOf("4", "5", "6", "39", "202", "38") && id.isNotBlank() && seen.add(id)) {
                    val programmes = JSONArray()
                    ch.optJSONObject("schedules")?.optJSONArray("items")?.objects()?.forEach { p ->
                        programme(p.optString("title"), p.optString("synopsis"), p.optString("startTime"), p.optString("endTime"), p.optJSONObject("mediaImage")?.optString("url"))?.let(programmes::put)
                    }
                    channels.put(JSONObject().put("id", id).put("name", ch.optString("name"))
                        .put("image", https(ch.optJSONObject("channelLogo")?.optString("url")))
                        .put("programmes", programmes))
                }
            }
        }; return channels
    }
    private fun nine(payload: JSONObject): JSONArray {
        val channels = JSONArray()
        payload.optJSONObject("data")?.optJSONObject("getLivestream")?.optJSONArray("events")?.objects()?.forEach { ch ->
            val programmes = JSONArray()
            listOf("currentEvent", "nextEvent").forEach { field -> ch.optJSONObject(field)?.let { p ->
                programme(p.optString("name"), p.optString("description"), p.optString("programStartTime"), p.optString("programEndTime"), null, p.optString("subtitle"))?.let(programmes::put)
            } }
            // Scheduled sports events may have no currentEvent. Use their real event interval.
            if (programmes.length() == 0) programme(ch.optString("name"), ch.optString("description"), ch.optString("startDate"), ch.optString("endDate"), null)?.let(programmes::put)
            channels.put(JSONObject().put("id", ch.optString("id")).put("name", ch.optString("name"))
                .put("image", https(ch.optJSONObject("image")?.optJSONObject("sizes")?.optString("w768")))
                .put("programmes", programmes))
        }; return channels
    }
    private fun programme(title: String, description: String, start: String, end: String, image: String?, subtitle: String = ""): JSONObject? {
        val a = runCatching { Instant.parse(start).toEpochMilli() }.getOrNull() ?: return null
        val b = runCatching { Instant.parse(end).toEpochMilli() }.getOrNull() ?: return null
        if (title.isBlank() || b <= a) return null
        return JSONObject().put("title", title).put("description", description).put("start", a).put("end", b).put("image", https(image)).put("subtitle", subtitle)
    }
    private fun item(channel: JSONObject, p: JSONObject, now: Long, current: Boolean, retrieved: Long): ContentItem {
        val start = p.optLong("start"); val end = p.optLong("end")
        val episode = p.optString("subtitle").takeIf { it.isNotBlank() }
        return ContentItem("extra:${channel.optString("id")}:$start", service, p.optString("title"),
            listOfNotNull(channel.optString("name"), episode).joinToString(" · "), p.optString("description"),
            https(p.optString("image")) ?: https(channel.optString("image")), startTime = start, endTime = end, isLive = current,
            progress = if (current && end - start < 24 * 60 * 60_000L) ((now - start).toDouble() / (end - start)).toFloat().coerceIn(0f, 1f) else null,
            metadata = mapOf("channelName" to channel.optString("name"), "integration" to "official_public_metadata", "metadataRetrievedAt" to retrieved.toString(),
                "scheduleOnly" to "true", "playbackFallback" to "app", "guideSource" to if (service == "7plus") SEVEN else NINE))
    }
    private fun https(url: String?): String? = url?.takeIf { it.startsWith("https://") && it.length <= 4096 }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    companion object {
        const val SEVEN = "https://component-cdn.swm.digital/content/live-tv?platform-id=web&market-id=26&platform-version=1.0.67393&api-version=5.9.0.0"
        const val NINE = "https://api.9now.com.au/ctv/livestreams?device=web&region=vic&offset=0"
        private const val MAX_BYTES = 4 * 1024 * 1024
    }
}
