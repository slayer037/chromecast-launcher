package com.majendra.tvlauncher.providers

/**
 * Bedroom reads the same genre rows as the TCL, more gently: fewer requests at once, the two most popular pages of
 * each genre and shorter rows. Explicit search and the Filter control are independent of these limits.
 */
internal object DiscoveryWorkloadPolicy {
    const val BEDROOM_ROW_ITEMS = 40

    fun concurrency(lightweight: Boolean) = if (lightweight) 2 else 6

    /** [visit] keeps TCL's deeper page the same for every load of one visit; Bedroom never reads a deeper page. */
    fun pages(lightweight: Boolean, visit: Long, genre: String): List<Int> =
        if (lightweight) listOf(0, StremioGenrePolicy.PAGE) else StremioGenrePolicy.pages(visit, genre)

    fun <T> limit(lightweight: Boolean, items: List<T>): List<T> =
        if (lightweight) items.take(BEDROOM_ROW_ITEMS) else items
}
