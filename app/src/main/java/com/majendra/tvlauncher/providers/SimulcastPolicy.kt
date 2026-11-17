package com.majendra.tvlauncher.providers

import com.majendra.tvlauncher.data.ContentItem

/**
 * Melbourne broadcasts most networks two or three times (SD, HD, duplicate numbers). Channels airing the same
 * programme from the same start time are one choice: keep the HD channel, else the lowest number, in the slot
 * of the first copy. "+1" channels start later, and channels without a guide are never merged.
 */
internal object SimulcastPolicy {
    fun distinct(tiles: List<ContentItem>): List<ContentItem> {
        val groups = linkedMapOf<String, MutableList<ContentItem>>()
        tiles.forEachIndexed { index, tile ->
            val key = tile.startTime?.let { "${tile.title}|$it" } ?: "unique:$index"
            groups.getOrPut(key) { mutableListOf() }.add(tile)
        }
        return groups.values.map { group ->
            group.minWith(compareBy<ContentItem>({ !isHd(it) }, { it.metadata["channelNumber"]?.toDoubleOrNull() ?: Double.MAX_VALUE }))
        }
    }

    private fun isHd(tile: ContentItem) = Regex("hd\\b", RegexOption.IGNORE_CASE).containsMatchIn(tile.metadata["channelName"].orEmpty())
}
