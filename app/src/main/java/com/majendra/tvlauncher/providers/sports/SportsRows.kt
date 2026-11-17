package com.majendra.tvlauncher.providers.sports

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow

/** One card in a per-sport row. [rank]: 0 live/on now, 1 later today, 2 channel, 3 recent final. */
data class SportsEntry(val sport: String, val item: ContentItem, val rank: Int, val time: Long)

/** The Sports Centre's fixed row order. Pure, so the order is unit tested. */
object SportsRows {
    const val MAX = 40
    const val KAYO = "sports-kayo-now"
    const val LIVE = "sports-live"
    const val NFL_SCORES = "sports-nfl-scores"
    const val UPCOMING = "sports-upcoming"
    const val RESULTS = "sports-results"
    const val CHANNELS = "sports-channels"
    const val STREMIO = "sports-stremio"
    const val STREMIO_EVENTS = "sports-stremio-events"
    const val NEWS = "sports-news"

    fun sportRowId(sport: String) = "sports-" + sport.lowercase().replace(Regex("[^a-z]+"), "-").trim('-')

    /**
     * A live-sport command centre: Kayo's channels as a compact strip, then live scores, today's games, one row per
     * followed sport (games plus what's on TV), recent results, other sport channels and headlines. Live now always shows,
     * with a line about the next game when nothing is on; other empty rows are skipped.
     */
    fun build(kayo: List<ContentItem>, live: List<ContentItem>, upcoming: List<ContentItem>, entries: List<SportsEntry>,
              results: List<ContentItem>, stremio: List<ContentItem>, channels: List<ContentItem>, news: List<ContentItem>, nextUp: String?,
              nflNotice: String? = null): List<ContentRow> {
        val nflScores = entries.filter { it.sport == SportCategories.NFL && it.item.metadata["scoreState"] != null }
        val sports = entries.filter { it.sport in SportCategories.followed &&
            !(it.sport == SportCategories.NFL && it.item.metadata["scoreState"] != null) }.groupBy { it.sport }
        val perSport = SportCategories.followed.mapNotNull { sport ->
            sports[sport]?.sortedWith(entryOrder)
                ?.map { it.item }?.distinctBy { it.id }?.take(MAX)?.takeIf { it.isNotEmpty() }
                ?.let { ContentRow(sportRowId(sport), SportCategories.rowTitle(sport), it) }
        }
        val liveRow = ContentRow(LIVE, "🔴  Live now", live.distinctBy { it.id }.take(MAX),
            "Nothing live right now." + (nextUp?.let { " Next up: $it." } ?: ""))
        // Stremio's live sport, by sport (followed sports first), so the strip reads AFL, NFL, cricket… rather than A to Z.
        val stremioRow = stremio.distinctBy { it.id }.sortedBy { SportCategories.followed.indexOf(it.metadata["sport"]).let { i -> if (i < 0) 99 else i } }
        val (stremioPosters, stremioChannels) = stremioRow.partition(StremioSportChannels::usesPosterCard)
        return (listOf(ContentRow(KAYO, "Kayo channels · on now", kayo.take(MAX)), liveRow,
            ContentRow(NFL_SCORES, if (nflNotice != null && nflScores.isNotEmpty()) "🏈  NFL · saved scores" else "🏈  NFL · scores & fixtures",
                nflScores.sortedWith(nflScoreOrder).map { it.item }.distinctBy { it.id }.take(MAX), nflNotice),
            ContentRow(STREMIO_EVENTS, "Sports listings · Stremio", stremioPosters.take(80)),
            ContentRow(STREMIO, "Sports channels · Stremio", stremioChannels.take(80)),
            ContentRow(UPCOMING, "Coming up", balanced(upcoming).take(MAX))) + perSport +
            listOf(ContentRow(RESULTS, "Results", balanced(results).take(MAX)),
                ContentRow(CHANNELS, "Free-to-air & TCL sports channels", channels.distinctBy { it.id }.take(MAX)),
                ContentRow(NEWS, "Headlines", news.distinctBy { it.id }.take(MAX))))
            .filter { it.items.isNotEmpty() || it.id == LIVE || (it.id == NFL_SCORES && nflNotice != null) }
    }

    // Real scores are never buried beneath a long guide or channel list; within them keep live, next, final order.
    private val entryOrder = compareBy<SportsEntry> { if (it.item.metadata["scoreState"] != null) 0 else 1 }
        .thenBy { it.rank }.thenBy { if (it.rank == 3) -it.time else it.time }
    private val nflScoreOrder = compareBy<SportsEntry> { when (it.item.metadata["scoreState"]) {
        "live" -> 0; "final" -> 1; else -> 2
    } }.thenBy { if (it.item.metadata["scoreState"] == "final") -it.time else it.time }

    /** One game per followed sport per pass; preserve the source's chronological order within each sport. */
    fun balanced(items: List<ContentItem>): List<ContentItem> {
        val groups = items.distinctBy { it.id }.groupBy { it.metadata["sport"] }
        val order = groups.keys.sortedBy { SportCategories.followed.indexOf(it).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        val largest = groups.values.maxOfOrNull { it.size } ?: return emptyList()
        return (0 until largest).flatMap { index -> order.mapNotNull { groups[it]?.getOrNull(index) } }
    }

    /** Kayo's "on now" guide row as compact channel tiles: logo, programme and progress (no channel name under the logo). */
    fun kayoTiles(rows: List<ContentRow>): List<ContentItem> = rows.firstOrNull { it.id == "kayo-now" }?.items.orEmpty().map { item ->
        item.copy(subtitle = null, // The logo already names the channel.
            imageUrl = item.metadata["fallbackArtwork"]?.takeIf { it.startsWith("https://") } ?: item.imageUrl,
            landscapeImageUrl = item.metadata["fallbackArtwork"]?.takeIf { it.startsWith("https://") } ?: item.landscapeImageUrl,
            metadata = item.metadata + mapOf("sportsSource" to "kayo", "layout" to "channel", "logo" to "true"))
    }

    /**
     * Where a game is on: a Kayo or free-to-air programme that names either team and airs around the game's start
     * (or is on now while it's live), or one titled with the league ("MLB") starting within half an hour of it.
     */
    fun broadcast(game: EspnScores.Game, programmes: List<ContentItem>, now: Long): ContentItem? {
        val names = listOfNotNull(game.home?.name, game.away?.name).filter { it.length >= 3 }.map { it.lowercase() }
        if (names.isEmpty()) return null
        fun near(p: ContentItem): Boolean {
            val start = p.startTime ?: return false
            return if (game.state == EspnScores.State.LIVE) now in start..(p.endTime ?: start) else game.start in (start - SportsTime.HOUR)..(p.endTime ?: start)
        }
        val named = programmes.filter { p -> near(p) && (p.title + " " + p.description.orEmpty()).lowercase().let { text -> names.any { text.contains(it) } } }
        // A listing titled with just the league ("MLB") counts only when nothing names the teams and it isn't another fixture.
        val league = Regex("\\b${Regex.escape(game.league.label.lowercase())}\\b")
        val candidates = named.ifEmpty { programmes.filter { p -> val start = p.startTime ?: return@filter false
            league.containsMatchIn(p.title.lowercase()) && !SportCategories.looksLikeFixture(p.title) && kotlin.math.abs(start - game.start) <= 30 * 60_000L } }
        return candidates.minByOrNull { if (it.metadata["sportsSource"] == "tv") 0 else 1 } // A free-to-air channel can be tuned straight away.
    }
}
