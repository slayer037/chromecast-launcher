package com.majendra.tvlauncher.sportscentre

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.navigation.QuietHoursPolicy
import com.majendra.tvlauncher.providers.sports.SportCategories
import java.time.Instant

object SportsDreamPolicy {
    fun allowed(bedroomGuard: Boolean, now: Instant, interactive: Boolean, mediaActive: Boolean) =
        !mediaActive && QuietHoursPolicy.allowsAutomaticLaunch(bedroomGuard, now, interactive)

    fun games(items: List<ContentItem>): List<ContentItem> = items.filter { it.metadata["scoreState"] != null }
        .distinctBy { item -> item.metadata["eventId"]?.let { "${item.metadata["leaguePath"]}:$it" } ?: item.id.substringAfter(":espn:") }
        .sortedWith(compareBy<ContentItem> {
            when (it.metadata["scoreState"]) { "live" -> 0; "upcoming" -> 1; else -> 2 }
        }.thenBy { SportCategories.followed.indexOf(it.metadata["sport"]).let { index -> if (index < 0) 99 else index } }
            .thenBy { if (it.metadata["scoreState"] == "final") -(it.startTime ?: 0) else it.startTime ?: Long.MAX_VALUE })
}
