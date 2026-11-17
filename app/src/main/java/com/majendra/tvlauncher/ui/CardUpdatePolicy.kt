package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.data.ContentItem

/** Content text can refresh in place; membership, order and card geometry changes restore the anchor. */
object CardUpdatePolicy {
    fun requiresAnchorRestore(previous: List<ContentItem>, updated: List<ContentItem>): Boolean =
        previous.size != updated.size || previous.indices.any { index ->
            previous[index].source != updated[index].source || previous[index].id != updated[index].id ||
                // Kids channel heroes can be wider than other cards with the same identity.
                previous[index].metadata["type"] != updated[index].metadata["type"]
        }
}
