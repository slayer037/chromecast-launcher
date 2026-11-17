package com.majendra.tvlauncher.providers

import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import com.majendra.tvlauncher.data.*
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Properties
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request

/** Public linear schedules only: selecting any item opens the installed Kayo app. */
class KayoProvider(private val context: Context) : FeedAdapter {
    override val id = "kayo"
    override val displayName = "Kayo"
    private val packageName = "au.com.kayosports.tv"
    private val mutex = Mutex()
    // A cancelled sidebar request does not cancel a useful, bounded cache update.
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var refreshJob: Deferred<Unit>? = null
    @Volatile private var guide: Guide? = null
    @Volatile private var sourceError: String? = null
    private val cache = File(context.filesDir, "kayo-open-epg.xml")
    private val compactCache = AtomicFile(File(context.filesDir, "kayo-open-epg-compact.bin"))
    private val attempt = File(context.filesDir, "kayo-open-epg-attempt")
    private val retryFile = AtomicFile(File(context.filesDir, "kayo-open-epg-retry.properties"))
    private val retryLock = Any()
    private var retryState: KayoRetryPolicy.State? = null
    private val client by lazy { LauncherHttp.client.newBuilder().callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build() }

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try { context.packageManager.getApplicationInfo(packageName, 0); true }
        catch (_: PackageManager.NameNotFoundException) { false }
    }

    override suspend fun load(forceRefresh: Boolean): FeedResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (guide == null && cache.isFile) runCatching {
                readCompact() ?: cache.inputStream().use { XmlTvParser.parse(it, KayoCompactGuide.wanted) }
                    .let(KayoCompactGuide::select).also { validate(it); saveCompact(it) }
            }.onSuccess { guide = it }.onFailure { sourceError = "Saved guide could not be read" }
            val now = System.currentTimeMillis()
            // Manual refresh reevaluates Now/Next without redownloading the national
            // guide inside the publisher's daily update window.
            val due = guide == null || !cache.isFile || now - cache.lastModified() >= KayoGuide.CACHE_MILLIS
            if (due && refreshJob?.isActive != true && reserveAttempt(now)) {
                refreshJob = refreshScope.async { downloadGuide() }
            }
            if (guide == null) refreshJob?.await()
            KayoGuide.result(guide, System.currentTimeMillis(), cache.lastModified(), sourceError)
        }
    }

    private fun validate(data: Guide) {
        val ids = KayoGuide.channels.mapNotNull { it.guideId }.toSet()
        if (data.channels.none { it.id in ids } || data.programs.none { it.channel in ids })
            throw IOException("Guide contains no matching Kayo schedules")
    }

    private fun readCompact(): Guide? = runCatching {
        compactCache.openRead().use { input ->
            val bytes = KayoGuide.LimitedInputStream(input, KayoCompactGuide.MAX_BYTES.toLong()).readBytes()
            KayoCompactGuide.decode(bytes, cache.lastModified(), cache.length()).also { validate(it) }
        }
    }.getOrNull()

    private fun saveCompact(data: Guide) {
        var output: java.io.FileOutputStream? = null
        try {
            val bytes = KayoCompactGuide.encode(data, cache.lastModified(), cache.length())
            output = compactCache.startWrite()
            output.write(bytes)
            compactCache.finishWrite(output)
        } catch (_: IOException) {
            // Derived cache failure must never discard a valid raw guide or force a download.
            compactCache.failWrite(output)
        }
    }

    private fun state(now: Long): KayoRetryPolicy.State {
        retryState?.let { return it }
        val loaded = if (!retryFile.baseFile.exists()) {
            KayoRetryPolicy.migrate(cache.lastModified().coerceAtLeast(0), attempt.lastModified().coerceAtLeast(0))
        } else runCatching {
            val values = Properties().apply { retryFile.openRead().use { load(it) } }
            KayoRetryPolicy.decode(values)
        }.getOrElse {
            sourceError = "Guide request history could not be read; retry delayed"
            KayoRetryPolicy.State(now, KayoRetryPolicy.MAX_ATTEMPTS, 1, now + KayoRetryPolicy.DAY)
        }
        return loaded.also { retryState = it }
    }

    private fun persist(value: KayoRetryPolicy.State): Boolean {
        var output: java.io.FileOutputStream? = null
        return try {
            val values = KayoRetryPolicy.encode(value)
            output = retryFile.startWrite()
            values.store(output, "Open EPG request budget")
            retryFile.finishWrite(output)
            retryState = value
            true
        } catch (_: IOException) {
            retryFile.failWrite(output)
            sourceError = "Guide request history could not be saved"
            false
        }
    }

    private fun reserveAttempt(now: Long): Boolean = synchronized(retryLock) {
        val current = state(now)
        KayoRetryPolicy.mayAttempt(current, now) && persist(KayoRetryPolicy.reserve(current, now))
    }

    private fun downloadGuide() {
        val temp = File(context.filesDir, "kayo-open-epg-download.xml")
        try {
            client.newCall(Request.Builder().url(KayoGuide.SOURCE)
                .header("User-Agent", "ChromecastLauncher/1.0 personal EPG (24-hour cache)").build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Guide HTTP ${response.code}")
                val body = response.body ?: throw IOException("Guide body missing")
                if (body.contentLength() > KayoGuide.MAX_COMPRESSED_BYTES) throw IOException("Compressed guide exceeds size limit")
                body.byteStream().use { raw ->
                    GZIPInputStream(KayoGuide.LimitedInputStream(raw, KayoGuide.MAX_COMPRESSED_BYTES)).use { input ->
                        KayoGuide.LimitedInputStream(input, KayoGuide.MAX_XML_BYTES).use { limited ->
                            temp.outputStream().use { limited.copyTo(it, 16 * 1024) }
                        }
                    }
                }
            }
            val parsed = temp.inputStream().use { XmlTvParser.parse(it, KayoCompactGuide.wanted) }.let(KayoCompactGuide::select)
            validate(parsed)
            if (!temp.renameTo(cache)) throw IOException("Cannot save guide")
            sourceError = null
            synchronized(retryLock) {
                // The cache timestamp is an additional success barrier if persisting fails.
                val succeeded = KayoRetryPolicy.success(state(System.currentTimeMillis()), cache.lastModified())
                retryState = succeeded
                persist(succeeded)
            }
            guide = parsed
            saveCompact(parsed)
        } catch (error: Exception) {
            sourceError = when (error) {
                is IOException -> error.message ?: "Guide download unavailable"
                is org.xml.sax.SAXException -> "Guide could not be parsed"
                else -> "Guide refresh unavailable"
            }
        } finally { temp.delete() }
    }

    override suspend fun resolvePlayback(item: ContentItem): PlaybackResult =
        PlaybackResult.App(packageName, "Opening Kayo")

    /** The Kayo channels' full saved schedule (after [load]), for the Sports Centre's later-today rows. */
    fun currentGuide(): Guide? = guide
}
