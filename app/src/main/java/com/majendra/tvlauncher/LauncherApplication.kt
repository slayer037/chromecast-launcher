package com.majendra.tvlauncher

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.majendra.tvlauncher.data.FeedRepository
import com.majendra.tvlauncher.data.LauncherHttp
import com.majendra.tvlauncher.data.lightweightLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Dns
import java.net.UnknownHostException
import java.io.IOException
import com.majendra.tvlauncher.providers.ChannelLogoLookup
import com.majendra.tvlauncher.providers.StremioMetadataPolicy

class LauncherApplication: Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        crashGuard.install()
        // Safe mode warms nothing: whatever keeps crashing may be in here.
        if (crashGuard.safeMode) return
        // Off the main thread, before the first activity: the repository is built, its disk housekeeping and the shared
        // HTTP engine are under way, and the last section's saved rows are read. MainActivity's first touch is then cheap.
        applicationScope.launch {
            repository.warm()
            runCatching { com.majendra.tvlauncher.settings.LauncherSettings(this@LauncherApplication).lastService() }.getOrNull()?.let { repository.preload(it) }
        }
    }
    val crashGuard by lazy { com.majendra.tvlauncher.diagnostics.CrashGuard(this) }
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repositoryHolder = lazy { FeedRepository(this, applicationScope) }
    val repository by repositoryHolder
    val sportsData by lazy { com.majendra.tvlauncher.sportscentre.SportsDataRepository(this, repository) }
    /** Releases what a local file restores when the UI is hidden, and more only under real memory pressure. Coil trims its own bitmaps. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (repositoryHolder.isInitialized()) repository.trimMemory(level)
    }
    override fun newImageLoader() = ImageLoader.Builder(this)
        .components { add(com.majendra.tvlauncher.ui.AppBannerFetcher.Factory()) } // Catch-up app tiles.
        // The loader is created by the first card bind, on the main thread; its client is built by the first network fetch, off it.
        .okHttpClient { LauncherHttp.client.newBuilder().followRedirects(true).followSslRedirects(false)
            .addInterceptor { chain ->
                if(!StremioMetadataPolicy.publicUrl(chain.request().url.toString())) throw IOException("Public HTTPS artwork required")
                // Wikimedia refuses OkHttp's default user agent (403), which hid every looked-up channel logo.
                chain.proceed(chain.request().newBuilder().header("User-Agent", ChannelLogoLookup.USER_AGENT).build())
            }
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val location = response.header("Location")
                if(response.isRedirect && location != null) {
                    val target = response.request.url.resolve(location)
                    if(target == null || !StremioMetadataPolicy.publicUrl(target.toString())) {
                        response.close();throw IOException("Unsupported artwork redirect")
                    }
                }
                response
            }
            .dns(object : Dns { override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).also { addresses ->
                if(addresses.isEmpty() || addresses.any { !StremioMetadataPolicy.publicAddress(it) })
                    throw UnknownHostException("Public artwork host required")
            } }).build() }
        .memoryCache { MemoryCache.Builder(this).maxSizeBytes((if (lightweightLauncher) 12 else 24) * 1024 * 1024).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(80L * 1024 * 1024).build() }
        // Artwork already on disk is used as it is. Honouring cache headers made Coil 2.7 go back to the network for every
        // saved image whose host sends no-cache or a short max-age (HttpUriFetcher only skips its CacheStrategy when this is off),
        // so posters waited on a round trip each launch and did not show offline. Artwork URLs here are per title or per logo.
        // Account artwork still never reaches the disk cache: those requests disable it themselves.
        .crossfade(false).respectCacheHeaders(false)
        // Only takes effect for a JPEG decoded as a software ARGB_8888 bitmap. Requests here allow hardware bitmaps
        // (Coil's default), so in practice artwork is not RGB_565; kept because it is harmless where it does apply.
        .allowRgb565(true).build()
}
