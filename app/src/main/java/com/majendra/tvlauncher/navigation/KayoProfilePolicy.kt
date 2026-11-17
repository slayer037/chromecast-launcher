package com.majendra.tvlauncher.navigation

/**
 * Kayo's "Who's Watching?" screen exposes no accessibility nodes, so it is recognised from a screenshot
 * row through the avatars: a mostly dark row holding the user's lime-green avatar. Returns the avatar's
 * horizontal centre as a fraction of the width, or null when this isn't the profile screen.
 */
object KayoProfilePolicy {
    const val ROW = 0.486f
    const val WATCH_MILLIS = 15_000L
    const val MAX_TAPS = 4
    fun limeAvatar(row: IntArray): Float? {
        if (row.isEmpty()) return null
        var dark = 0; var first = -1; var last = -1; var lime = 0
        row.forEachIndexed { x, pixel ->
            val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
            if (r < 40 && g < 40 && b < 50) dark++
            if (r > 150 && g > 200 && b < 120) { lime++; if (first < 0) first = x; last = x }
        }
        if (dark < row.size * 0.6 || lime < row.size / 200 || last - first > row.size * 0.08) return null
        return (first + last) / 2f / row.size
    }
}
