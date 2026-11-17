package com.majendra.tvlauncher.providers

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * TCL Channel publishes no channel logos the launcher can read. Look each channel name up once in Wikipedia's page
 * images (50 names per request) and keep the answers, including misses, for a week. Misses keep the name tile.
 */
internal class ChannelLogoLookup(context: Context, private val client: () -> OkHttpClient) {
    private val file = file(context)

    /** Known logo URLs by channel name; blank means "looked up, none found". */
    fun cached(): Map<String, String> = saved(file)

    /** True when some names are unknown or the last full lookup is over a week old. */
    fun needsRefresh(names: Collection<String>) = wanted(names, cached(), fresh()).isNotEmpty()

    private fun fresh() = System.currentTimeMillis() - file.lastModified() < MAX_AGE_MILLIS

    /** Look up names not yet known, or everything once the week-old cache expires. Blocking; call off the main thread. */
    fun refresh(names: Collection<String>) {
        val fresh = fresh()
        val fullLookupAt = file.lastModified()
        val known = cached()
        val wanted = wanted(names, known, fresh)
        if (wanted.isEmpty()) return
        val found = known.toMutableMap()
        wanted.chunked(50).forEach { batch ->
            runCatching {
                val titles = batch.map { ALIASES[it] ?: it }
                // Exact titles only: search fallbacks returned wrong logos (Baby Shark TV -> Nickelodeon).
                // pilicense=any includes the non-free logos most channel articles use.
                val url = Uri.Builder().scheme("https").authority("en.wikipedia.org").path("w/api.php")
                    .appendQueryParameter("action", "query").appendQueryParameter("format", "json")
                    .appendQueryParameter("prop", "pageimages").appendQueryParameter("piprop", "thumbnail")
                    .appendQueryParameter("pithumbsize", "240").appendQueryParameter("pilicense", "any")
                    .appendQueryParameter("redirects", "1")
                    .appendQueryParameter("titles", titles.distinct().joinToString("|")).build().toString()
                client().newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                    if (response.isSuccessful) response.body?.string()?.let { body ->
                        val byTitle = parse(body, titles)
                        batch.forEach { name -> found[name] = byTitle[ALIASES[name] ?: name].orEmpty() }
                    }
                }
            }
        }
        memory = found
        runCatching {
            file.writeText(JSONObject(found as Map<*, *>).toString())
            // Adding a few new names must not push back the weekly full lookup.
            if (fresh) file.setLastModified(fullLookupAt)
        }
    }

    companion object {
        private const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1000
        /** Also sent on artwork downloads: Wikimedia's image servers reject generic client user agents. */
        const val USER_AGENT ="ChromecastLauncher/1.0 (personal TV launcher channel logos)"
        /** TCL Channel names whose Wikipedia article has a different title. */
        private val ALIASES = mapOf("DW English" to "DW-TV")
        private val LOGO_FILE = Regex("logo|symbol|wordmark", RegexOption.IGNORE_CASE)
        // One copy for the process, read on first use: only Live TV's lookup writes the file, and it updates this too.
        @Volatile private var memory: Map<String, String>? = null

        private fun file(context: Context) = File(context.filesDir, "channel-logos-v5.json") // v5: non-free logos, logo files and same-subject redirects only.

        /** The saved lookups, for readers that never refresh them (Sports Centre, Kids Corner). Reads the file once; call off the main thread. */
        fun saved(context: Context): Map<String, String> = saved(file(context))

        private fun saved(file: File): Map<String, String> = memory ?: synchronized(this) {
            memory ?: runCatching {
                val json = JSONObject(file.readText())
                json.keys().asSequence().associateWith { json.optString(it) }
            }.getOrDefault(emptyMap()).also { memory = it }
        }

        /** Names to look up: only unknown ones while the cache is fresh, every one once it is stale. */
        fun wanted(names: Collection<String>, cached: Map<String, String>, fresh: Boolean) =
            names.distinct().filter { !fresh || it !in cached }

        /** Map each requested name to its page thumbnail through Wikipedia's normalisation and redirects. */
        fun parse(body: String, requested: List<String>): Map<String, String> {
            val query = JSONObject(body).optJSONObject("query") ?: return requested.associateWith { "" }
            fun hops(key: String) = query.optJSONArray(key)?.let { list ->
                (0 until list.length()).map(list::getJSONObject).associate { it.optString("from") to it.optString("to") }
            }.orEmpty()
            val normalized = hops("normalized")
            val redirects = hops("redirects")
            val pages = query.optJSONObject("pages")
            // Only logo files: page images are often photos or title screens (Hell's Kitchen, Manhattan).
            val thumbnails = pages?.keys()?.asSequence()?.mapNotNull { key ->
                val page = pages.getJSONObject(key)
                page.optJSONObject("thumbnail")?.optString("source")
                    ?.takeIf { it.startsWith("https://") && LOGO_FILE.containsMatchIn(it.substringAfterLast('/')) }
                    ?.let { page.optString("title") to it }
            }?.toMap().orEmpty()
            fun key(value: String) = value.lowercase().replace(Regex("[^a-z0-9]"), "")
            return requested.associateWith { name ->
                val title = normalized[name] ?: name
                val target = redirects[title] ?: title
                // A redirect must stay on the same subject: "Al Jazeera" -> "Al Jazeera Media Network" is fine,
                // "FUEL TV" -> "Fox Sports 2" is not.
                if (!key(target).startsWith(key(title))) "" else thumbnails[target].orEmpty()
            }
        }
    }
}
