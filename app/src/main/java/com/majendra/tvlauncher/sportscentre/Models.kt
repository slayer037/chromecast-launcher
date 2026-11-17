package com.majendra.tvlauncher.sportscentre

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.FeedResult
import com.majendra.tvlauncher.providers.Guide

data class SportsSnapshot(val feed: FeedResult, val games: List<ContentItem>, val guide: Guide?, val updatedAt: Long,
    val guideUpdatedAt: Long = 0, val guideSource: String = "Open-EPG")
data class PeriodScore(val label: String, val away: String, val home: String)
data class TeamStat(val label: String, val away: String, val home: String)
data class MatchDetail(val item: ContentItem, val periods: List<PeriodScore> = emptyList(), val stats: List<TeamStat> = emptyList(),
    val note: String? = null, val updatedAt: Long = 0, val headline: String? = null, val drive: String? = null)
data class LadderRow(val id: String, val name: String, val abbreviation: String, val values: List<String>, val form: List<String> = emptyList())
data class LadderTable(val league: String, val title: String, val columns: List<String>, val rows: List<LadderRow>,
    val updatedAt: Long, val note: String? = null, val source: String = "ESPN")

/** Stable across rows and fixture/live/final transitions; providers never share an ID namespace. */
object SportsIdentity {
    fun key(item: ContentItem): String {
        if (item.metadata["dataSource"] == "Squiggle" || item.metadata["sportsSource"] == "squiggle")
            return "squiggle:" + (item.metadata["squiggleId"] ?: item.id)
        val event = item.metadata["eventId"]
        val path = item.metadata["leaguePath"]
        return if (event != null && path != null) "espn:" + path + ":" + event else item.id.substringAfter(":espn:", item.id)
    }
}
