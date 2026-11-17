package com.majendra.tvlauncher.providers.sports

import com.majendra.tvlauncher.data.LauncherHttp
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response

/** Public, keyless GETs only: short timeouts, a byte cap, and a cancelled coroutine cancels the call. */
internal class SportsHttp {
    private val client by lazy { LauncherHttp.client.newBuilder().connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS).callTimeout(9, TimeUnit.SECONDS).retryOnConnectionFailure(false).build() }

    /** The body as text, or null on any HTTP, network or size failure. */
    suspend fun get(url: String, maxBytes: Long = MAX_BYTES): String? = suspendCancellableCoroutine { continuation ->
        if (!url.startsWith("https://")) { continuation.resume(null); return@suspendCancellableCoroutine }
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", "ChromecastLauncher/1.0 personal sports guide").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val text = try {
                    response.use {
                        val body = it.body
                        if (!it.isSuccessful || body == null || body.contentLength() > maxBytes) null
                        else body.source().let { source ->
                            source.request(maxBytes + 1)
                            if (source.buffer.size > maxBytes) null else source.readUtf8()
                        }
                    }
                } catch (_: IOException) { null }
                if (continuation.isActive) continuation.resume(text)
            }
        })
    }

    companion object { const val MAX_BYTES = 2_500_000L }
}
