package com.majendra.tvlauncher.auth

import android.content.Context
import com.majendra.tvlauncher.providers.StremioHttp
import com.majendra.tvlauncher.providers.StremioSearchPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.GeneralSecurityException
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class StremioAccountResult<out T> {
    data class Ready<T>(val value: T): StremioAccountResult<T>()
    data object Pending: StremioAccountResult<Nothing>()
    data class Failure(val message: String, val authenticationRequired: Boolean = false): StremioAccountResult<Nothing>()
}

data class StremioLibraryEntry(
    val id: String, val type: String, val name: String, val poster: String?,
    val removed: Boolean, val temporary: Boolean, val videoId: String?,
    val timeOffset: Long, val duration: Long, val lastWatched: Long, val modified: Long,
    val watched: String? = null, val timesWatched: Int = 0, val flaggedWatched: Boolean = false, val timeWatched: Long = 0
)

/** Configured add-on URLs can contain private configuration. Keep them in memory only. */
/** [extra] is a catalogue filter such as "genre=Sports", for add-ons that only list channels by category. */
class StremioLiveCatalog(val transportUrl: String, val type: String, val catalogId: String, val extra: String? = null) {
    override fun toString() = "StremioLiveCatalog(<redacted>)"
}

/** Private configured transport stays in memory, and never appears in diagnostics or toString. */
class StremioSearchCatalog(val transportUrl: String, val type: String, val catalogId: String) {
    override fun toString() = "StremioSearchCatalog(<redacted>)"
}

/** Public edition: no account transport, linking or session storage. */
class StremioAccountClient(context: Context, private val http: OkHttpClient? = null) {
    val store = StremioAccountStore(context.applicationContext)
    suspend fun loadLibrary(): StremioAccountResult<List<StremioLibraryEntry>> = StremioAccountResult.Ready(emptyList())
    suspend fun loadLiveCatalogs(limit: Int = 6): StremioAccountResult<List<StremioLiveCatalog>> = StremioAccountResult.Ready(emptyList())
    suspend fun loadSearchCatalogs(type: String): StremioAccountResult<List<StremioSearchCatalog>> = StremioAccountResult.Ready(emptyList())
}

internal object StremioLibraryEntryParser {
    fun parse(item: JSONObject): StremioLibraryEntry? {
        val id = (item.opt("_id") as? String)?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
        val type = item.opt("type") as? String
        val name = (item.opt("name") as? String)?.take(200)?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")?.trim()
        if (type !in setOf("movie", "series") || name.isNullOrBlank()) return null
        val state = item.optJSONObject("state") ?: JSONObject()
        fun text(json: JSONObject, key: String, limit: Int) = (json.opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= limit }
        fun date(key: String, json: JSONObject) = runCatching { Instant.parse(json.optString(key)).toEpochMilli() }.getOrDefault(0)
        return StremioLibraryEntry(id, type!!, name, text(item, "poster", 2048), item.optBoolean("removed"), item.optBoolean("temp"),
            text(state, "video_id", 512), state.optLong("timeOffset").coerceAtLeast(0), state.optLong("duration").coerceAtLeast(0),
            date("lastWatched", state), date("_mtime", item), text(state, "watched", 131_072),
            state.optInt("timesWatched").coerceAtLeast(0), state.optInt("flaggedWatched") > 0 || state.optBoolean("flaggedWatched"),
            state.optLong("timeWatched").coerceAtLeast(0))
    }
}

/**
 * Which live catalogues to read. Live TV add-ons often list channels only by category: a required "genre" filter is
 * read once per sport-looking option ("Sports", "Football"…), and an optional one is narrowed to those options when
 * there are any. Catalogues needing a search or other required input are skipped.
 */
internal object StremioLiveCatalogPolicy {
    val types = setOf("tv", "channel", "channels", "events", "sports", "live")
    private val sporty = Regex("(?i)sport|football|soccer|\\bnfl\\b|\\bnba\\b|\\bafl\\b|\\bnrl\\b|cricket|racing|motor|\\bf1\\b|basketball|baseball|\\bmlb\\b|rugby|\\bufc\\b|fight|tennis|golf|\\bespn|\\bdazn|\\bbein")
    fun requests(extras: JSONArray?): List<String?> {
        val list = (0 until minOf(extras?.length() ?: 0, 20)).mapNotNull { extras?.optJSONObject(it) }
        val genre = list.firstOrNull { it.optString("name") == "genre" }
        val options = genre?.optJSONArray("options")?.let { o -> (0 until minOf(o.length(), 200)).mapNotNull { o.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
        val sportOptions = options.filter { sporty.containsMatchIn(it) && it.length <= 60 }.take(6)
        if (list.any { it.optBoolean("isRequired") && it.optString("name") != "genre" }) return emptyList()
        return when {
            sportOptions.isNotEmpty() -> sportOptions.map { "genre=" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            genre?.optBoolean("isRequired") == true -> emptyList()
            else -> listOf(null)
        }
    }
}
