package com.majendra.tvlauncher.sportscentre

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.providers.sports.EspnScores
import com.majendra.tvlauncher.providers.sports.SportsTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/** Public data only. No link or $ref from a response is followed. */
object SportsDataParsers {
    val leaguePaths = mapOf("EPL" to "soccer/eng.1", "NBA" to "basketball/nba", "NFL" to "football/nfl", "AFL" to "australian-football/afl")

    fun contact(value: String?): String? = value?.trim()?.takeIf { it.length <= 160 &&
        it.matches(Regex("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")) }

    fun matchIdentity(item: ContentItem): Pair<String, String>? {
        if (item.metadata["sportsSource"] == "squiggle" || item.metadata["dataSource"] == "Squiggle") return null
        val legacy = item.id.substringAfter(":espn:", "")
        if (legacy.isEmpty() && item.metadata["sportsSource"] !in setOf("espn", "tv")) return null
        val path = item.metadata["leaguePath"] ?: legacy.substringBeforeLast(':', "")
        val event = item.metadata["eventId"] ?: legacy.substringAfterLast(':', "")
        return if (path in EspnScores.leagues.map { it.path } && event.matches(Regex("[0-9]{1,20}"))) path to event else null
    }

    fun uniqueGames(items: List<ContentItem>): List<ContentItem> = items.filter { it.metadata["scoreState"] != null }
        .distinctBy { item -> matchIdentity(item)?.let { "${it.first}:${it.second}" } ?: item.id }

    fun match(json: String, base: ContentItem, now: Long): MatchDetail {
        val root = JSONObject(json)
        val competition = root.optJSONObject("header")?.optJSONArray("competitions")?.optJSONObject(0)
            ?: return MatchDetail(base, note = "Detailed match statistics are unavailable.", updatedAt = now)
        val competitors = competition.optJSONArray("competitors").objects()
        val home = competitors.firstOrNull { it.optString("homeAway") == "home" }
        val away = competitors.firstOrNull { it.optString("homeAway") == "away" }
        val type = competition.optJSONObject("status")?.optJSONObject("type")
        val state = when (type?.optString("state")) { "in" -> "live"; "post" -> "final"; "pre" -> "upcoming"; else -> base.metadata["scoreState"] }
        val metadata = base.metadata.toMutableMap()
        if (state != null) metadata["scoreState"] = state
        (type?.text("shortDetail") ?: type?.text("detail"))?.let { metadata["clock"] = it }
        fun side(side: JSONObject?, prefix: String) {
            val team = side?.optJSONObject("team") ?: return
            (team.text("shortDisplayName") ?: team.text("displayName"))?.let { metadata["${prefix}Name"] = it }
            team.text("abbreviation")?.let { metadata["${prefix}Abbr"] = it }
            side?.text("score")?.let { metadata["${prefix}Score"] = it }
            side?.optJSONArray("record")?.optJSONObject(0)?.text("summary")?.let { metadata["${prefix}Record"] = it }
            metadata.remove("${prefix}Winner")
            if (side?.optBoolean("winner") == true) metadata["${prefix}Winner"] = "true"
        }
        side(away, "away"); side(home, "home")
        val title = if (state != "upcoming" && listOf("awayName", "awayScore", "homeScore", "homeName").all { metadata[it] != null })
            "${metadata["awayName"]} ${metadata["awayScore"]} – ${metadata["homeScore"]} ${metadata["homeName"]}" else base.title
        val item = base.copy(title = title, metadata = metadata, isLive = state == "live")
        val homePeriods = home?.optJSONArray("linescores").objects()
        val awayPeriods = away?.optJSONArray("linescores").objects()
        val league = metadata["leaguePath"].orEmpty()
        val count = maxOf(homePeriods.size, awayPeriods.size).coerceAtMost(20)
        val periods = (0 until count).map { i ->
            val label = when {
                league.startsWith("football/") || league.startsWith("basketball/") -> if (i < 4) "Q${i + 1}" else if (i == 4) "OT" else "OT${i - 3}"
                league.startsWith("baseball/") -> "${i + 1}"
                league.startsWith("soccer/") -> if (i < 2) "H${i + 1}" else "ET${i - 1}"
                else -> "${i + 1}"
            }
            PeriodScore(label, awayPeriods.getOrNull(i).score(), homePeriods.getOrNull(i).score())
        }.let { rows -> if (rows.isNotEmpty()) rows + PeriodScore("T", metadata["awayScore"] ?: "—", metadata["homeScore"] ?: "—") else rows }
        val boxTeams = root.optJSONObject("boxscore")?.optJSONArray("teams").objects()
        fun stats(side: JSONObject?): List<JSONObject> {
            val id = side?.optJSONObject("team")?.text("id") ?: return emptyList()
            return boxTeams.firstOrNull { it.optJSONObject("team")?.text("id") == id }?.optJSONArray("statistics").objects()
        }
        val homeStats = stats(home).associateBy { it.optString("name") }
        val awayStats = stats(away).associateBy { it.optString("name") }
        val preferred = listOf("totalYards", "netPassingYards", "rushingYards", "firstDowns", "possessionTime", "fieldGoalsMade-fieldGoalsAttempted", "fieldGoalPct", "threePointFieldGoalsMade-threePointFieldGoalsAttempted", "rebounds", "assists", "turnovers", "possessionPct", "totalShots", "shotsOnTarget")
        val common = homeStats.keys.intersect(awayStats.keys)
        val names = (preferred.filter { it in common } + common.sorted()).distinct().take(6)
        val stats = names.map { key ->
            val h = homeStats.getValue(key); val a = awayStats.getValue(key)
            TeamStat(h.text("label") ?: h.text("displayName") ?: key, a.text("displayValue") ?: "—", h.text("displayValue") ?: "—")
        }
        val drive = root.optJSONObject("drives")?.optJSONObject("current")?.takeIf { state == "live" }?.let { current ->
            current.optJSONArray("plays").objects().lastOrNull()?.text("text") ?: current.text("description")
        }?.take(300)
        val headline = root.optJSONArray("news")?.optJSONObject(0)?.text("headline")?.take(180)
        return MatchDetail(item, periods, stats,
            note = if (periods.isEmpty() && stats.isEmpty()) "This source has not supplied period scores or team statistics." else null,
            updatedAt = now, headline = headline, drive = drive)
    }

    fun standings(json: String, league: String, now: Long): List<LadderTable> {
        val root = JSONObject(json)
        val tables = mutableListOf<LadderTable>()
        fun visit(node: JSONObject, depth: Int) {
            if (depth > 5) return
            val entries = node.optJSONObject("standings")?.optJSONArray("entries").objects()
            if (entries.isNotEmpty()) {
                val spec = when (league) {
                    "EPL" -> listOf("P" to "gamesPlayed", "W" to "wins", "D" to "ties", "L" to "losses", "GD" to "pointDifferential", "Pts" to "points")
                    "AFL" -> listOf("P" to "gamesPlayed", "W" to "wins", "D" to "ties", "L" to "losses", "%" to "percentage", "Pts" to "points")
                    "NFL" -> listOf("W" to "wins", "L" to "losses", "T" to "ties", "PCT" to "winPercent", "+/−" to "pointDifferential", "Streak" to "streak")
                    else -> listOf("W" to "wins", "L" to "losses", "PCT" to "winPercent", "GB" to "gamesBehind", "Streak" to "streak")
                }
                fun stat(entry: JSONObject, name: String): JSONObject? = entry.optJSONArray("stats").objects().firstOrNull { it.optString("name") == name }
                val ranked = entries.sortedWith(compareBy<JSONObject> { stat(it, "rank")?.optDouble("value") ?: stat(it, "playoffSeed")?.optDouble("value") ?: Double.MAX_VALUE }
                    .thenByDescending { stat(it, "winPercent")?.optDouble("value") ?: stat(it, "points")?.optDouble("value") ?: 0.0 }
                    .thenByDescending { stat(it, "wins")?.optDouble("value") ?: 0.0 })
                val rows = ranked.take(40).mapNotNull { entry ->
                    val team = entry.optJSONObject("team") ?: return@mapNotNull null
                    val name = (if (league == "NBA" || league == "NFL") team.text("shortDisplayName") ?: team.text("name") else null)
                        ?: team.text("displayName") ?: return@mapNotNull null
                    LadderRow(team.text("id") ?: name, name, team.text("abbreviation") ?: name.take(3),
                        spec.map { (_, key) -> stat(entry, key)?.text("displayValue") ?: "—" })
                }
                if (rows.isNotEmpty()) tables += LadderTable(league, node.text("name") ?: league, spec.map { it.first }, rows, now)
            }
            node.optJSONArray("children").objects().forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return tables
    }

    fun squiggleStandings(json: String, now: Long): List<LadderTable> {
        val rows = JSONObject(json).optJSONArray("standings").objects().sortedBy { it.optInt("rank", Int.MAX_VALUE) }.mapNotNull { row ->
            val name = row.text("name") ?: row.text("team") ?: return@mapNotNull null
            fun value(vararg keys: String) = keys.firstNotNullOfOrNull { key -> row.text(key) } ?: "—"
            val percentage = value("percentage", "pct").toDoubleOrNull()?.takeIf { it.isFinite() }
                ?.let { String.format(java.util.Locale.ROOT, "%.1f", it) } ?: "—"
            LadderRow(value("teamid", "id").takeIf { it != "—" } ?: name, name, name.take(3).uppercase(),
                listOf(value("played"), value("wins"), value("draws"), value("losses"), percentage, value("pts", "points")))
        }
        return if (rows.isEmpty()) emptyList() else listOf(LadderTable("AFL", "AFL ladder", listOf("P", "W", "D", "L", "%", "Pts"), rows, now, source = "Squiggle"))
    }

    /** Standard API games: points remain the main score, with goals/behinds preserved separately. */
    fun squiggleGames(json: String, now: Long): List<ContentItem> {
        val array = JSONObject(json).optJSONArray("games") ?: throw IllegalArgumentException("Missing games array")
        return array.objects(400).mapNotNull { game ->
            val id = game.text("id")?.takeIf { it.matches(Regex("[0-9]{1,20}")) } ?: return@mapNotNull null
            val home = game.text("hteam")?.takeUnless { it.all(Char::isDigit) } ?: return@mapNotNull null
            val away = game.text("ateam")?.takeUnless { it.all(Char::isDigit) } ?: return@mapNotNull null
            val start = game.optLong("unixtime", 0).takeIf { it > 0 }?.times(1000)
                ?: runCatching { LocalDateTime.parse(game.optString("date"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atOffset(ZoneOffset.of(game.optString("tz"))).toInstant().toEpochMilli() }.getOrNull() ?: return@mapNotNull null
            val complete = game.optDouble("complete", 0.0)
            val state = when { complete >= 100 -> "final"; game.optInt("live", 0) == 1 || complete > 0 -> "live"; else -> "upcoming" }
            val homeScore = game.text("hscore")?.takeIf { state != "upcoming" }
            val awayScore = game.text("ascore")?.takeIf { state != "upcoming" }
            val clock = when (state) { "final" -> "Final"; "live" -> game.text("timestr") ?: "Live"; else -> SportsTime.label(start, now, java.time.ZoneId.of("Australia/Melbourne")) }
            val venue = game.text("venue")
            val metadata = buildMap {
                put("sportsSource", "squiggle"); put("dataSource", "Squiggle"); put("sport", "AFL"); put("league", "AFL")
                put("scoreState", state); put("card", "game"); put("clock", clock); put("squiggleId", id)
                put("homeName", home); put("awayName", away)
                homeScore?.let { put("homeScore", it) }; awayScore?.let { put("awayScore", it) }
                for ((name, field) in listOf("homeGoals" to "hgoals", "awayGoals" to "agoals", "homeBehinds" to "hbehinds", "awayBehinds" to "abehinds"))
                    game.text(field)?.takeIf { state != "upcoming" }?.let { put(name, it) }
                game.text("roundname")?.let { put("round", it) }
                put("footer", "Squiggle · up to 60s delay"); put("playbackPackage", "au.com.kayosports.tv")
                put("sourceUpdatedAt", now.toString())
            }
            val title = if (homeScore != null && awayScore != null) "$away $awayScore – $homeScore $home" else "$away v $home"
            ContentItem("squiggle:game:$id", "kayo", title, "AFL · $clock",
                listOfNotNull(venue, "Squiggle Standard API scores may be delayed by up to 60 seconds.").joinToString(" · "),
                startTime = start, isLive = state == "live", metadata = metadata)
        }
    }

    /** Show useful AFL fixtures/results, not an entire season; preserve all other sports and a working fallback. */
    fun mergeAfl(base: List<ContentItem>, squiggle: List<ContentItem>, now: Long): List<ContentItem> {
        val window = 7 * 24 * SportsTime.HOUR
        val useful = squiggle.filter { item ->
            when (item.metadata["scoreState"]) {
                "live" -> true
                "final" -> item.startTime?.let { it in (now - window)..now } == true
                "upcoming" -> item.startTime?.let { it in now..(now + window) } == true
                else -> false
            }
        }
        return if (useful.isEmpty()) base else base.filterNot { it.metadata["sport"] == "AFL" } + useful
    }

    /** A game missing from the next live set must stop claiming to be live, even if another game continues. */
    fun reconcileAflLive(cached: List<ContentItem>, live: List<ContentItem>): List<ContentItem> {
        val current = live.associateBy { it.id }
        return cached.filterNot { it.isLive || it.id in current } + live
    }

    fun cachedAflForDisplay(games: List<ContentItem>, now: Long): List<ContentItem> = if (games.any {
        it.isLive && !SportsCachePolicy.fresh(it.metadata["sourceUpdatedAt"]?.toLongOrNull() ?: 0, now, 2 * 60_000L)
    }) emptyList() else games

    private fun JSONArray?.objects(limit: Int = 100): List<JSONObject> = if (this == null) emptyList() else (0 until minOf(length(), limit)).mapNotNull(::optJSONObject)
    private fun JSONObject?.score(): String = this?.text("displayValue") ?: this?.text("value") ?: "—"
    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
}
