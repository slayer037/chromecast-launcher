package com.majendra.tvlauncher.providers

import com.majendra.tvlauncher.data.ContentRow

/** Request-local accumulator. Never persisted; only the consumer coroutine mutates it. */
internal class StremioProgressiveRows {
    private val loaded = linkedMapOf<String, ContentRow>()
    fun merge(rows: List<ContentRow>) { rows.forEach { loaded[it.id] = it } }
    fun rows(): List<ContentRow> = loaded.values.sortedBy { order.indexOf(it.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }
    private val order = listOf("stremio-recent", "stremio-movie", "stremio-series", "stremio-live", "stremio-library", "stremio-continue")
}
