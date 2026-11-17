package com.majendra.tvlauncher.providers

import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * One connection pool and dispatcher for every Stremio and Cinemeta request. The two variants are built from the
 * same root with newBuilder(), so they share its sockets and threads and differ only in timeouts and address checks.
 */
object StremioHttp {
    @Volatile private var root: OkHttpClient? = null
    @Volatile private var metadataClient: OkHttpClient? = null
    @Volatile private var accountClient: OkHttpClient? = null

    /** Point every Stremio client at the application's shared client. Safe to call at any time. */
    @Synchronized fun use(client: OkHttpClient) { root = client; metadataClient = null; accountClient = null }

    /** The application's shared engine unless [use] chose another: one TLS setup, pool and dispatcher for everything. */
    @Synchronized private fun root() = root ?: com.majendra.tvlauncher.data.LauncherHttp.client.also { root = it }

    /** Public add-on and Cinemeta metadata: short timeouts, no redirects, public addresses only. */
    val metadata: OkHttpClient get() = metadataClient ?: synchronized(this) {
        metadataClient ?: root().newBuilder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = Dns.SYSTEM.lookup(hostname).also { addresses ->
                    if (addresses.isEmpty() || addresses.any { !StremioMetadataPolicy.publicAddress(it) })
                        throw UnknownHostException("Public add-on host required")
                }
            }).build().also { metadataClient = it }
    }

    /** Official Stremio account API: longer timeouts for the library read, no redirects. */
    val account: OkHttpClient get() = accountClient ?: synchronized(this) {
        accountClient ?: root().newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(18, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .build().also { accountClient = it }
    }
}
