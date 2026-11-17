package com.majendra.tvlauncher.ui

import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options

/** Loads an installed app's TV banner (or its icon) for "app-banner://<package>" artwork. */
class AppBannerFetcher(private val packageName: String, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val pm = options.context.packageManager
        val drawable = pm.getApplicationBanner(packageName) ?: pm.getApplicationIcon(packageName)
        return DrawableResult(drawable, isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            data.takeIf { it.scheme == "app-banner" }?.host?.let { AppBannerFetcher(it, options) }
    }
}
