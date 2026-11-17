package com.majendra.tvlauncher.data

import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * The app's one HTTP engine. A client built from scratch loads the platform trust store and makes its own TLS context,
 * connection pool and dispatcher; every source instead derives from this one with `LauncherHttp.client.newBuilder()`,
 * which keeps that source's own timeouts, redirect rules, interceptors and DNS while sharing the TLS setup, sockets and threads.
 *
 * Deliberately policy-free (default redirects and DNS, no interceptors), so a derived client is exactly as strict as
 * its own builder calls make it. Connections are still never shared across differing DNS or TLS settings: OkHttp pools by address.
 */
object LauncherHttp {
    /** Each source used to have a dispatcher of its own; one shared host limit of five would queue sources behind each other. */
    const val MAX_REQUESTS_PER_HOST = 16

    /** Safe to first touch from any thread: later callers wait for the one build instead of repeating it. */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST }).build()
    }
}
