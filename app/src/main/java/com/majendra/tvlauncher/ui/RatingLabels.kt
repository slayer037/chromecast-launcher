package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.data.ContentItem

object RatingLabels {
    fun short(item:ContentItem):String? {
        val score=item.metadata["imdbRating"]?.toDoubleOrNull()?.takeIf{it.isFinite()&&it in 0.0..10.0}?:return null
        return "IMDb ${String.format(java.util.Locale.ROOT,"%.1f",score)}/10"
    }
    fun detail(item:ContentItem):String?=short(item)?.let{"$it · via Cinemeta"}
}
