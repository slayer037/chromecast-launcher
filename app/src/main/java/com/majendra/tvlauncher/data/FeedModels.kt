package com.majendra.tvlauncher.data

data class ContentItem(
    val id: String, val source: String, val title: String,
    val subtitle: String? = null, val description: String? = null,
    val imageUrl: String? = null, val landscapeImageUrl: String? = null,
    val startTime: Long? = null, val endTime: Long? = null,
    val isLive: Boolean = false, val progress: Float? = null,
    val playbackUri: String? = null, val metadata: Map<String, String> = emptyMap()
)
data class ContentRow(val id: String, val title: String, val items: List<ContentItem>, val emptyMessage: String? = null)
enum class FeedStatus { LOADING, FRESH, CACHED, AUTHENTICATION_REQUIRED, PERMISSION_DENIED, SOURCE_UNAVAILABLE, UNSUPPORTED, NO_CONTENT }
data class FeedError(val code: String, val message: String)
data class FeedResult(val rows: List<ContentRow> = emptyList(), val status: FeedStatus = FeedStatus.NO_CONTENT,
    val message: String = "", val updatedAt: Long = System.currentTimeMillis(), val error: FeedError? = null)
sealed class PlaybackResult {
    data class Ready(val uri: String, val packageName: String? = null): PlaybackResult()
    data class App(val packageName: String, val explanation: String? = null): PlaybackResult()
    data class Unavailable(val reason: String): PlaybackResult()
}
data class ServiceConfiguration(val id: String, val displayName: String, val packageName: String, val symbol: String)
object Services {
    val all = listOf(
        // Section id stays "kayo" so saved order, favourites and the remote button keep working.
        ServiceConfiguration("kayo", "Sports Centre", "au.com.kayosports.tv", "⚑"),
        ServiceConfiguration("kids", "Kids Corner", "com.stremio.one", "★"),
        ServiceConfiguration("youtube", "YouTube", "com.google.android.youtube.tv", "▶"),
        ServiceConfiguration("movies", "Movies", "com.stremio.one", "▤"),
        ServiceConfiguration("series", "TV shows", "com.stremio.one", "▥"),
        ServiceConfiguration("starred", "Starred", "com.stremio.one", "☆"),
        ServiceConfiguration("live", "Live TV", "com.tcl.tv", "◉"),
        // TCL TV (antenna, TCL Channel and HDMI) is merged into Live TV; the broadcaster apps into Catch-up.
        ServiceConfiguration("catchup", "Catch-up", "", "↺")
    )
    /** Free-to-air apps: shown inside Catch-up, and still the source of their own extra channels. */
    val broadcasters = listOf(
        ServiceConfiguration("7plus", "7plus", "com.swm.live", "7"),
        ServiceConfiguration("9now", "9Now", "au.com.ninenow.ctv", "9"),
        ServiceConfiguration("abc", "ABC iview", "au.net.abc.iview", "a"),
        ServiceConfiguration("sbs", "SBS On Demand", "com.sbs.ondemand.tv", "s"),
        ServiceConfiguration("10", "10", "au.com.tenplay", "10")
    )
    /** Not a sidebar section any more (Movies and TV shows cover it), but Movies/TV show cards still open Stremio. */
    val apps = listOf(ServiceConfiguration("stremio", "Stremio", "com.stremio.one", "▣"))
    fun get(id: String) = (all + broadcasters + apps).first { it.id == id }
}
