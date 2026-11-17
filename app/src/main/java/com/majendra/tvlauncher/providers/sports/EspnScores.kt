package com.majendra.tvlauncher.providers.sports

import com.majendra.tvlauncher.data.ContentItem
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** ESPN's public, keyless scoreboard JSON: live first, then today's fixtures, then recent finals. */
object EspnScores {
    data class League(val path: String, val label: String, val sport: String) {
        val url get() = "https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"
    }
    enum class State { LIVE, UPCOMING, FINAL }
    /** One team (or player) in a head-to-head game. [score] is free text: cricket scores read "339 & 244/9". */
    data class Side(val name: String, val abbreviation: String?, val logo: String?, val score: String?, val winner: Boolean)
    /**
     * [home]/[away] are set for head-to-head games; races and tournaments have [lines] instead (podium or leaders).
     * [status] is the clock for live games ("Q3 12:04", "Bot 7th").
     */
    data class Game(val id: String, val league: League, val title: String, val status: String, val state: State,
                    val start: Long, val description: String?, val image: String?,
                    val home: Side? = null, val away: Side? = null, val lines: List<String> = emptyList(), val venue: String? = null)

    // Verified 2026-10-25, each returns 200 with an "events" array. Only the sports followed at home.
    val leagues = listOf(
        League("football/nfl", "NFL", SportCategories.NFL),
        League("australian-football/afl", "AFL", SportCategories.AFL),
        League("cricket/8044", "BBL", SportCategories.CRICKET),
        League("cricket/8043", "Sheffield Shield", SportCategories.CRICKET),
        League("soccer/eng.1", "Premier League", SportCategories.SOCCER),
        League("racing/f1", "F1", SportCategories.MOTORSPORT),
        League("racing/irl", "IndyCar", SportCategories.MOTORSPORT),
        League("racing/nascar-premier", "NASCAR", SportCategories.MOTORSPORT),
        League("baseball/mlb", "MLB", SportCategories.BASEBALL),
        League("basketball/nba", "NBA", SportCategories.BASKETBALL),
        League("basketball/nbl", "NBL", SportCategories.BASKETBALL)
    )
    private const val FINAL_WINDOW = 15 * SportsTime.HOUR // ~12 h after a typical 3 h fixture ends.
    private const val NFL_FINAL_WINDOW = 7 * 24 * SportsTime.HOUR // Weekly results remain useful between NFL slates.
    private const val PER_LEAGUE = 16
    /** Overnight US games (NFL, MLB) start after midnight here, so look a day and a half ahead. */
    const val AHEAD = 36 * SportsTime.HOUR

    /** Poll live games each minute; quiet leagues wait half an hour, bounded by their next kick-off. */
    fun cacheFresh(games: List<Game>, at: Long, now: Long, force: Boolean = false): Boolean {
        if (at <= 0 || now < at) return false
        if (force) return now - at < 15_000L
        val active = games.any { it.state == State.LIVE || (it.state == State.UPCOMING && it.start <= now) }
        if (now - at >= if (active) 60_000L else 30 * 60_000L) return false
        return games.none { it.state == State.UPCOMING && it.start > at && now >= it.start }
    }

    fun cacheFallbackUsable(games: List<Game>, at: Long, now: Long): Boolean = at > 0 && now >= at &&
        now - at < if (games.any { it.state == State.LIVE }) 15 * 60_000L else 60 * 60_000L

    fun time(value: String?): Long? = value?.takeIf { it.isNotBlank() }?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    /** Games worth showing now, ordered live → upcoming (soonest) → final (latest). Malformed entries are skipped. */
    fun parse(json: String, league: League, now: Long, zone: ZoneId): List<Game> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val leagueLogo = root.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("logos")?.optJSONObject(0)?.https("href")
        val events = root.optJSONArray("events") ?: return emptyList()
        val end = maxOf(SportsTime.windowEnd(now, zone), now + AHEAD)
        val games = mutableListOf<Game>()
        for (index in 0 until minOf(events.length(), 200)) {
            val event = events.optJSONObject(index) ?: continue
            val eventId = event.optString("id")
            if (eventId.isBlank()) continue
            val competitions = event.optJSONArray("competitions")
            val first = competitions?.optJSONObject(0)
            val teams = first?.optJSONArray("competitors")?.takeIf { it.length() == 2 && it.optJSONObject(0)?.has("team") == true }
            if (competitions?.length() == 1 && teams != null) headToHead(first, event, eventId, league, leagueLogo, now, zone)?.let(games::add)
            else eventLevel(event, eventId, league, leagueLogo, now, zone)?.let(games::add)
        }
        return order(games.filter { visible(it, now, end) }).take(PER_LEAGUE)
    }

    fun order(games: List<Game>): List<Game> = games.sortedWith(compareBy<Game> { it.state.ordinal }
        .thenBy { if (it.state == State.FINAL) -it.start else it.start })

    /** NFL is weekly: use the season/week declared by its working response, never guessed date-range queries. */
    fun nflSupplementUrls(json: String, games: List<Game>): List<String> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        val season = root.optJSONObject("season") ?: return emptyList()
        val year = season.optInt("year", 0)
        val type = season.optInt("type", 0)
        val week = root.optJSONObject("week")?.optInt("number", 0) ?: 0
        val lastWeek = when (type) { 1 -> 4; 2 -> 18; 3 -> 5; else -> return emptyList() }
        if (year !in 2000..2100 || week !in 1..lastWeek) return emptyList()
        val url = leagues.first { it.sport == SportCategories.NFL }.url
        return buildList {
            if (week > 1 && games.none { it.state == State.FINAL }) add("$url?dates=$year&seasontype=$type&week=${week - 1}")
            if (week < lastWeek && games.none { it.state == State.UPCOMING }) add("$url?dates=$year&seasontype=$type&week=${week + 1}")
        }
    }

    private fun visible(game: Game, now: Long, end: Long) = when (game.state) {
        State.LIVE -> true
        State.UPCOMING -> game.start in (now - SportsTime.HOUR)..end
        State.FINAL -> game.start in (now - if (game.league.sport == SportCategories.NFL) NFL_FINAL_WINDOW else FINAL_WINDOW)..now
    }

    private fun state(status: JSONObject?): State? = when (status?.optJSONObject("type")?.optString("state")) {
        "in" -> State.LIVE; "pre" -> State.UPCOMING; "post" -> State.FINAL; else -> null
    }

    /** The live clock as ESPN words it; upcoming games use the local start time rather than ESPN's US time zone. */
    private fun statusLabel(state: State, status: JSONObject?, start: Long, now: Long, zone: ZoneId) = when (state) {
        State.LIVE -> status?.optJSONObject("type")?.text("shortDetail") ?: "Live"
        State.FINAL -> "Final"
        State.UPCOMING -> SportsTime.label(start, now, zone)
    }

    private fun side(json: JSONObject, state: State): Side? {
        val team = json.optJSONObject("team") ?: return null
        val name = team.text("shortDisplayName") ?: team.text("displayName") ?: return null
        return Side(name, team.text("abbreviation"), team.https("logo"),
            json.text("score")?.takeIf { state != State.UPCOMING }, json.optBoolean("winner", false))
    }

    private fun headToHead(comp: JSONObject, event: JSONObject, eventId: String, league: League, leagueLogo: String?, now: Long, zone: ZoneId): Game? {
        val competitors = comp.optJSONArray("competitors") ?: return null
        val status = comp.optJSONObject("status") ?: event.optJSONObject("status")
        val state = state(status) ?: return null
        val sides = (0 until 2).mapNotNull { competitors.optJSONObject(it) }
        // Away team on top, home underneath, as scoreboards read ("TB @ MIL").
        val away = sides.firstOrNull { it.optString("homeAway") == "away" } ?: sides.getOrNull(1) ?: return null
        val home = sides.firstOrNull { it !== away } ?: return null
        val awaySide = side(away, state) ?: return null
        val homeSide = side(home, state) ?: return null
        val start = time(comp.optString("date")) ?: time(event.optString("date")) ?: return null
        val label = statusLabel(state, status, start, now, zone)
        val title = if (homeSide.score != null && awaySide.score != null && state != State.UPCOMING && league.sport != SportCategories.CRICKET)
            "${awaySide.name} ${awaySide.score} – ${homeSide.score} ${homeSide.name}" else "${awaySide.name} v ${homeSide.name}"
        val venue = comp.optJSONObject("venue")?.text("fullName")
        val description = listOfNotNull(event.text("name"), venue,
            status?.optJSONObject("type")?.text("detail")?.takeIf { state != State.UPCOMING }).distinct().joinToString(" · ")
        return Game("${league.path}:$eventId", league, title, "${league.label} · $label", state, start, description.ifBlank { null },
            homeSide.logo ?: leagueLogo, homeSide, awaySide, venue = venue)
    }

    /** Races: one card per event, with the podium (or the running order while live). */
    private fun eventLevel(event: JSONObject, eventId: String, league: League, leagueLogo: String?, now: Long, zone: ZoneId): Game? {
        val status = event.optJSONObject("status")
        val state = state(status) ?: return null
        val start = time(event.optString("date")) ?: return null
        // Multi-day events count as "final" from their end date.
        val effective = if (state == State.FINAL) time(event.optString("endDate"))?.coerceAtMost(now) ?: start else start
        val title = event.text("shortName") ?: event.text("name") ?: return null
        val comps = event.optJSONArray("competitions")?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } }.orEmpty()
        // The race itself is the last session (practice and qualifying come first).
        val race = comps.lastOrNull()
        val order = race?.optJSONArray("competitors")?.let { list -> (0 until minOf(list.length(), 40)).mapNotNull { list.optJSONObject(it) } }.orEmpty()
            .sortedBy { it.optInt("order", Int.MAX_VALUE) }
        val lines = if (state == State.UPCOMING) emptyList() else order.take(3).mapIndexedNotNull { i, c ->
            c.optJSONObject("athlete")?.let { it.text("shortName") ?: it.text("displayName") }?.let { "${i + 1}. $it" }
        }
        val venue = race?.optJSONObject("venue")?.text("fullName") ?: comps.firstOrNull()?.optJSONObject("venue")?.text("fullName")
        val label = statusLabel(state, status, start, now, zone)
        val description = listOfNotNull(event.text("name"), venue, lines.joinToString("  ").ifBlank { null }).distinct().joinToString(" · ")
        return Game("${league.path}:$eventId", league, title, "${league.label} · $label", state, effective, description.ifBlank { null },
            leagueLogo, lines = lines, venue = venue)
    }

    /** A scoreboard card. [watch] names where it's on ("Kayo · ESPN2"); with [watchUri] the card tunes straight to it. */
    fun item(game: Game, prefix: String, now: Long, zone: ZoneId, watch: String? = null, watchUri: String? = null, watchPackage: String? = null): ContentItem =
        ContentItem("$prefix:espn:${game.id}", "kayo", game.title, game.status, game.description, game.image, game.image,
            startTime = game.start, isLive = game.state == State.LIVE, playbackUri = watchUri,
            metadata = buildMap {
                put("sportsSource", if (watchUri != null) "tv" else "espn"); put("sport", game.league.sport); put("league", game.league.label)
                put("leaguePath", game.league.path); put("eventId", game.id.substringAfterLast(':'))
                put("card", if (game.home != null) "game" else "event"); put("scoreState", game.state.name.lowercase())
                put("clock", when (game.state) { State.LIVE -> game.status.substringAfter(" · "); State.FINAL -> "Final"; State.UPCOMING -> SportsTime.label(game.start, now, zone) })
                put("playbackPackage", watchPackage ?: "au.com.kayosports.tv"); put("playbackFallback", "app")
                game.home?.let { side(this, "home", it) }; game.away?.let { side(this, "away", it) }
                if (game.lines.isNotEmpty()) put("lines", game.lines.joinToString("\n"))
                (watch ?: game.venue)?.let { put("footer", it) }
                if (watch != null) put("watch", "true")
            })

    private fun side(into: MutableMap<String, String>, key: String, side: Side) {
        into["${key}Name"] = side.name
        side.abbreviation?.let { into["${key}Abbr"] = it }
        side.logo?.let { into["${key}Logo"] = it }
        side.score?.let { into["${key}Score"] = it }
        if (side.winner) into["${key}Winner"] = "true"
    }

    private fun JSONObject.text(key: String): String? = if (isNull(key)) null else optString(key).trim().takeIf { it.isNotBlank() }
    private fun JSONObject.https(key: String): String? = text(key)?.takeIf { it.startsWith("https://") }
}
