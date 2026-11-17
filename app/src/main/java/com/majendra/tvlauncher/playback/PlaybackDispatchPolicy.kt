package com.majendra.tvlauncher.playback

import com.majendra.tvlauncher.data.PlaybackResult

/** A rejected title must never become an unrestricted fallback app launch. */
object PlaybackDispatchPolicy {
    fun dispatch(result:PlaybackResult,launch:()->String?):String? =
        if(result is PlaybackResult.Unavailable)result.reason else launch()
}
