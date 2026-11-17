package com.majendra.tvlauncher.providers.sports

import android.content.Context
import com.majendra.tvlauncher.auth.StremioAccountClient
import com.majendra.tvlauncher.auth.StremioAccountResult
import com.majendra.tvlauncher.auth.StremioAccountStore
import com.majendra.tvlauncher.data.*
import com.majendra.tvlauncher.providers.*
import java.io.File
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * The "kayo" section as a live-sport command centre: Kayo's channels, live scores, today's games, one row per followed
 * sport (games plus what's on TV), results, other sport channels and headlines. Every source is fetched independently and bounded,
 * and rows are published as each arrives. Scores open Kayo; headlines only open their details.
 */
class SportsCentreProvider(private val context: Context) : FeedAdapter {
    override val id = "kayo"
    override val displayName = "Sports Centre"
    private val kayo = KayoProvider(context)
    private val http = SportsHttp()
    // Only the Stremio channel source uses these. The Sports Centre preview can construct this provider on the main
    // thread, so the account store and both clients wait for that source's first run.
    private val account by lazy { StremioAccountClient(context) }
    private val accountStore by lazy { StremioAccountStore(context) }
    private val metadataClient by lazy { StremioMetadataClient() }
    private class Timed<T>(val at: Long, val value: T)
    private data class ScoreBatch(val games: List<EspnScores.Game>, val unavailable: List<String>, val stale: List<String>)
    private val scoreCache = ConcurrentHashMap<String, Timed<List<EspnScores.Game>>>()
    private val newsCache = ConcurrentHashMap<String, Timed<List<SportsNews.Headline>>>()
    @Volatile private var stremioCache: Pair<Long, Timed<List<StremioSportChannels.Channel>>>? = null

    /** Everything gathered so far; [result] turns it into rows in the fixed order. */
    private class Sources {
        var kayoRows = emptyList<ContentRow>(); var kayoMessage: String? = null
        var tiles = emptyList<ContentItem>(); var stremio = emptyList<ContentItem>()
        var entries = mapOf<String, List<SportsEntry>>()
        var games = emptyList<EspnScores.Game>(); var news = emptyList<SportsNews.Headline>()
        var nflNotice: String? = null
        val notes = linkedSetOf<String>()
    }

    override suspend fun isAvailable() = true

    /** The Sports Centre UI reuses this provider's saved guide; it never starts another downloader. */
    fun guideSnapshot(): Pair<Guide?, Long> = kayo.currentGuide() to File(context.filesDir, "kayo-open-epg.xml").lastModified()

    override suspend fun load(forceRefresh: Boolean): FeedResult = loadProgressively(forceRefresh) {}

    override suspend fun loadProgressively(forceRefresh: Boolean, onPartial: suspend (FeedResult) -> Unit): FeedResult = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val sources = Sources()
        val lock = Mutex()
        suspend fun update(block: Sources.() -> Unit) = lock.withLock { sources.block(); onPartial(result(sources, zone, false)) }
        coroutineScope {
            launch {
                val loaded = withTimeoutOrNull(KAYO_TIMEOUT) { runCatching { kayoSource(forceRefresh, zone) }.getOrNull() }
                update { if (loaded != null) { kayoRows = loaded.first; kayoMessage = loaded.third; entries = entries + ("kayo" to loaded.second) } else {
                    // Kayo's channels stay at the top even without a guide: their cards still open Kayo.
                    kayoRows = KayoGuide.result(null, System.currentTimeMillis(), 0, "timed out").rows
                    notes += "Kayo guide unavailable"
                } }
            }
            if (context.liveTvEnabled) launch {
                val loaded = withTimeoutOrNull(LOCAL_TIMEOUT) { runCatching { localSource(zone) }.getOrNull() }
                update { if (loaded != null) { tiles = loaded.first; entries = entries + ("tv" to loaded.second) } else notes += "TV channels unavailable" }
            }
            if (context.liveTvEnabled) launch {
                val loaded = try { withTimeoutOrNull(STREMIO_TIMEOUT) { stremioSource() } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { FeedDiagnostics.event(context, "stremio_live_failed", mapOf("error" to e.javaClass.simpleName, "message" to e.message?.take(200))); null }
                FeedDiagnostics.event(context, "stremio_live_done", mapOf("channels" to loaded?.first?.size, "note" to loaded?.second))
                update {
                    val channels = loaded?.first.orEmpty()
                    stremio = channels.map { StremioSportChannels.item(it, "channels") }
                    (loaded?.second ?: "Stremio channels timed out").takeIf { it.isNotBlank() }?.let { notes += it }
                }
            }
            launch {
                val loaded = scoresSource(forceRefresh, zone)
                update {
                    games = loaded.games
                    nflNotice = when {
                        "NFL" in loaded.unavailable -> "NFL scores unavailable. Other sports may still be available."
                        "NFL" in loaded.stale -> "Live NFL updates unavailable. Showing saved scores."
                        else -> null
                    }
                    if (loaded.unavailable.isNotEmpty()) notes += "Scores unavailable: ${loaded.unavailable.joinToString(", ")}"
                    if (loaded.stale.isNotEmpty()) notes += "Saved scores: ${loaded.stale.joinToString(", ")}"
                    if (loaded.games.isEmpty() && loaded.unavailable.isEmpty()) notes += "No games in the current score window"
                }
            }
            launch {
                val loaded = newsSource(forceRefresh)
                update { news = loaded; if (loaded.isEmpty()) notes += "Headlines unavailable" }
            }
        }
        lock.withLock { result(sources, zone, true) }
    }

    private fun result(sources: Sources, zone: ZoneId, complete: Boolean): FeedResult {
        val now = System.currentTimeMillis()
        val programmes = sources.entries.values.flatten().map { it.item }.filter { it.metadata["sportsSource"] in setOf("kayo", "tv") && it.startTime != null }
        // Every scoreboard card says where the game is on; a free-to-air match tunes straight to the channel.
        fun card(game: EspnScores.Game, prefix: String): ContentItem {
            val on = SportsRows.broadcast(game, programmes, now)
            val tv = on?.metadata?.get("sportsSource") == "tv" && !on.playbackUri.isNullOrBlank()
            return EspnScores.item(game, prefix, now, zone, on?.let { "📺  ${it.metadata["chip"]} · ${it.metadata["footer"]}" },
                on?.playbackUri?.takeIf { tv }, if (tv) "com.tcl.tv" else null)
        }
        val games = sources.games.filter { it.league.sport in SportCategories.followed }
        val priority = { game: EspnScores.Game -> SportCategories.followed.indexOf(game.league.sport) }
        val upcoming = games.filter { it.state == EspnScores.State.UPCOMING }.sortedBy { it.start }
        val gameEntries = games.map { game -> SportsEntry(game.league.sport, card(game, SportsRows.sportRowId(game.league.sport)),
            when (game.state) { EspnScores.State.LIVE -> 0; EspnScores.State.UPCOMING -> 1; EspnScores.State.FINAL -> 3 }, game.start) }
        val rows = SportsRows.build(SportsRows.kayoTiles(sources.kayoRows),
            games.filter { it.state == EspnScores.State.LIVE }.sortedWith(compareBy(priority).thenBy { it.start }).map { card(it, "live") },
            upcoming.map { card(it, "next") }, gameEntries + sources.entries.values.flatten(),
            games.filter { it.state == EspnScores.State.FINAL }.sortedByDescending { it.start }.map { card(it, "result") },
            sources.stremio, sources.tiles, SportsNews.merge(sources.news, now).map { SportsNews.item(it, now, zone) },
            upcoming.firstOrNull()?.let { "${it.title}, ${SportsTime.label(it.start, now, zone)}" }, sources.nflNotice)
        val any = rows.any { it.items.isNotEmpty() }
        val status = when { any -> FeedStatus.FRESH; complete -> FeedStatus.SOURCE_UNAVAILABLE; else -> FeedStatus.LOADING }
        val message = (if (context.liveTvEnabled) "Live scores for NFL, AFL, cricket, EPL, motor racing, baseball and basketball, with where each game is on, plus Stremio live sport." else "Scores, fixtures, results and sports headlines.") +
            (sources.notes.takeIf { it.isNotEmpty() }?.joinToString("; ", " Notes: ", ".") ?: "")
        return FeedResult(rows, status, message, now, if (!any && complete) FeedError("sports_unavailable", "No sports source responded") else null)
    }

    // --- Kayo: the existing guide, plus later-today programmes sorted by sport.
    private suspend fun kayoSource(force: Boolean, zone: ZoneId): Triple<List<ContentRow>, List<SportsEntry>, String> {
        val loaded = kayo.load(force)
        val guide = kayo.currentGuide()
        val now = System.currentTimeMillis()
        val end = SportsTime.windowEnd(now, zone)
        val entries = if (guide == null) emptyList() else KayoGuide.channels.flatMap { channel ->
            val art = guide.channels.firstOrNull { it.id == channel.guideId }?.image?.takeIf { it.startsWith("https://") }
            guide.programs(channel.guideId).filter { inWindow(it, now, end) }.mapNotNull { p ->
                val sport = SportCategories.ofProgramme(p.title, p.description, channel.name, true) ?: return@mapNotNull null
                programme("kayo:${channel.id}", "Kayo", channel.name, p, sport, now, zone, p.image?.takeIf { it.startsWith("https://") } ?: art, null,
                    mapOf("sportsSource" to "kayo", "playbackPackage" to "au.com.kayosports.tv", "playbackFallback" to "app"))
            }
        }
        return Triple(loaded.rows, entries, loaded.message)
    }

    private fun inWindow(p: GuideProgram, now: Long, end: Long) = p.start != null && p.end != null && p.end > p.start && p.end > now && p.start < end

    private fun programme(key: String, source: String, channelName: String, p: GuideProgram, sport: String, now: Long, zone: ZoneId,
                          image: String?, playbackUri: String?, extra: Map<String, String>): SportsEntry {
        val onNow = p.isNow(now)
        val start = p.start ?: now
        val item = ContentItem("${SportsRows.sportRowId(sport)}:$key:$start", "kayo", p.title,
            "$source · $channelName · ${if (onNow) "Now" else SportsTime.label(start, now, zone)}", p.description, image, image,
            p.start, p.end, false, if (onNow) p.progress(now) else null, playbackUri,
            extra + mapOf("sport" to sport, "onAir" to onNow.toString(), "scheduleOnly" to "true", "card" to "programme", "chip" to source,
                "clock" to if (onNow) "On now" else SportsTime.label(start, now, zone), "footer" to channelName))
        return SportsEntry(sport, item, if (onNow) 0 else 1, start)
    }

    // --- Antenna and TCL Channel: sport channel tiles, plus sport programmes from the cached Melbourne guide.
    private suspend fun localSource(zone: ZoneId): Pair<List<ContentItem>, List<SportsEntry>> {
        val channels = TvChannelSource.channels(context)
        val guide = freeToAirGuide()
        // Read-only use of Live TV's saved TCL Channel logo lookups; Live TV owns refreshing them.
        val logos = runCatching { ChannelLogoLookup.saved(context) }.getOrDefault(emptyMap())
        val now = System.currentTimeMillis()
        val end = SportsTime.windowEnd(now, zone)
        val tvChannels = guide?.let { GuideChannelPolicy.television(it.channels) }.orEmpty()
        fun match(channel: TvChannel) = guide?.channels?.let { list ->
            list.firstOrNull { it.number != null && it.number == channel.number } ?: list.firstOrNull { normalise(it.name) == normalise(channel.name) }
        }
        val tiles = if (channels.isNotEmpty()) channels.filter { isSportChannel(it.name, it.number, it.tclChannel) }
            .sortedBy { it.tclChannel }.map { tile(it, match(it), guide, now, logos[it.name]) }
        else tvChannels.filter { isSportChannel(it.name, it.number, false) }.map { tile(null, it, guide, now) }
        val entries = mutableListOf<SportsEntry>()
        if (guide != null) {
            val byId = tvChannels.associateBy { it.id }
            guide.programs.asSequence().filter { it.channel in byId && inWindow(it, now, end) }
                .distinctBy { "${it.title}|${it.start}" }.forEach { p ->
                    val gc = byId.getValue(p.channel)
                    val sport = SportCategories.ofProgramme(p.title, p.description, gc.name, isSportChannel(gc.name, gc.number, false)) ?: return@forEach
                    val tuned = channels.firstOrNull { (gc.number != null && it.number == gc.number) || normalise(it.name) == normalise(gc.name) }
                    val art = p.image?.takeIf { it.startsWith("https://") } ?: gc.image?.takeIf { it.startsWith("https://") }
                    entries += programme("fta:${gc.id}", "Free-to-air", gc.name, p, sport, now, zone, art, tuned?.playbackUri,
                        mapOf("sportsSource" to "tv", "playbackPackage" to "com.tcl.tv", "playbackFallback" to "nativeTV"))
                }
        }
        // TCL Channel streams have no guide: a channel dedicated to one sport joins that sport's row.
        channels.filter { it.tclChannel }.forEach { channel ->
            val sport = SportCategories.ofChannel(channel.name).takeIf { it != SportCategories.OTHER && isSportChannel(channel.name, channel.number, true) } ?: return@forEach
            tiles.firstOrNull { it.id == "tv:${channel.id}" }?.let { entries += SportsEntry(sport, onTv(it.copy(id = "${SportsRows.sportRowId(sport)}:${it.id}"), "TCL Channel"), 2, 0) }
        }
        return SimulcastPolicy.distinct(tiles) to entries.take(400)
    }

    /** Sport by Live TV's own category, or by known sport channel names (7mate, 9Rush, Racing.com, gecko…) in case
     * Live TV's categories change. Radio (ABC Sport digital radio, 200+ antenna numbers) never counts. */
    private fun isSportChannel(name: String, number: String?, tcl: Boolean): Boolean {
        if (LiveChannelCategories.of(name, number, tcl) == LiveChannelCategories.SPORT) return true
        val radio = RADIO.containsMatchIn(name) || (!tcl && (number?.toDoubleOrNull() ?: 0.0) >= 200)
        return !radio && SportCategories.isSportChannel(name)
    }

    /** Live TV downloads melbourne.xml; this only reads it, through the one parsed copy both sections share. */
    private fun freeToAirGuide(): Guide? = GuideStore.melbourne(context).load(System.currentTimeMillis())

    /** As Live TV's tile: the programme on now as the title, the channel's logo, and its source named underneath. */
    private fun tile(channel: TvChannel?, guideChannel: GuideChannel?, guide: Guide?, now: Long, lookedUpLogo: String? = null): ContentItem {
        val name = channel?.name ?: guideChannel?.name.orEmpty()
        val number = channel?.number ?: guideChannel?.number.orEmpty()
        val current = guideChannel?.let { guide?.current(it.id, now) }
        val next = guideChannel?.let { guide?.next(it.id, now) }
        val zone = ZoneId.systemDefault()
        val description = listOfNotNull(
            current?.let { p -> "Now: ${p.title}" + (p.end?.let { " (until ${SportsTime.label(it, now, zone)})" } ?: "") },
            next?.let { p -> "Next: ${p.title}" + (p.start?.let { " at ${SportsTime.label(it, now, zone)}" } ?: "") },
            current?.description?.takeIf { it.isNotBlank() }).joinToString("\n")
        val guideLogo = guideChannel?.image?.takeIf { it.startsWith("https://") } ?: lookedUpLogo?.takeIf { it.isNotBlank() } ?: channel?.iconUrl
        val tcl = channel?.tclChannel == true
        val logo = if (tcl) channel.logoUri else guideLogo
        val itemId = channel?.let { "tv:${it.id}" } ?: "guide:${guideChannel?.id}"
        return ContentItem(itemId, "kayo", current?.title ?: name,
            "${if (tcl) "TCL Channel" else "Free-to-air"} · $name", description.ifBlank { null }, logo, logo,
            current?.start, current?.end, false, current?.progress(now), channel?.playbackUri,
            mapOf("sportsSource" to "tv", "channelName" to name, "channelId" to itemId, "channelNumber" to number,
                "layout" to "channel", "logo" to "true", "fallbackArtwork" to guideLogo.orEmpty(),
                "sport" to SportCategories.ofChannel(name), "playbackPackage" to "com.tcl.tv", "playbackFallback" to "nativeTV") +
                (if (current != null) mapOf("onAir" to "true") else emptyMap()))
    }

    /** A sport channel shown in a sport's row: a programme-style card rather than a logo tile. */
    private fun onTv(item: ContentItem, chip: String) = item.copy(metadata = item.metadata + mapOf("card" to "programme", "chip" to chip,
        "clock" to "Live channel", "footer" to (item.metadata["channelName"] ?: item.title)))

    private fun normalise(value: String) = value.lowercase().replace(REGION, "").replace(NON_ALPHANUMERIC, "")

    // --- Stremio: every installed live TV catalogue, keeping only sport channels. Cached 15 minutes per account.
    private suspend fun stremioSource(): Pair<List<StremioSportChannels.Channel>, String> {
        val generation = accountStore.generation
        val now = System.currentTimeMillis()
        stremioCache?.takeIf { it.first == generation && now - it.second.at < STREMIO_CACHE }?.let { return it.second.value to "" }
        val catalogs = when (val result = account.loadLiveCatalogs(STREMIO_CATALOGUES).also {
            FeedDiagnostics.event(context, "stremio_live_catalogues", mapOf("result" to it.javaClass.simpleName,
                "count" to (it as? StremioAccountResult.Ready)?.value?.size))
        }) {
            is StremioAccountResult.Ready -> result.value
            is StremioAccountResult.Failure -> return emptyList<StremioSportChannels.Channel>() to (if (result.authenticationRequired) "" else "Stremio add-ons unavailable")
            else -> return emptyList<StremioSportChannels.Channel>() to ""
        }
        val found = java.util.Collections.synchronizedList(mutableListOf<StremioSportChannels.Channel>())
        withTimeoutOrNull(STREMIO_FETCH_TIMEOUT) {
            coroutineScope {
                val permits = Semaphore(8)
                catalogs.map { catalog -> async { permits.withPermit {
                    val json = metadataClient.liveCatalog(catalog)
                    val sport = StremioSportChannels.parse(json, catalog.type, catalog.extra != null)
                    found += sport
                    // Local-only diagnostics: which catalogues answered and how many sport channels each gave.
                    FeedDiagnostics.event(context, "stremio_live_catalogue", mapOf("type" to catalog.type, "catalogue" to catalog.catalogId,
                        "filter" to catalog.extra, "answered" to (json != null), "channels" to ((json?.optJSONArray("metas") ?: json?.optJSONArray("metasDetailed"))?.length() ?: 0),
                        "sport" to sport.size))
                } } }.awaitAll()
            }
        }
        if (accountStore.generation != generation) return emptyList<StremioSportChannels.Channel>() to ""
        val channels = synchronized(found) { found.toList() }.distinctBy { it.id }.sortedBy { it.name.lowercase() }.take(160)
        if (channels.isNotEmpty() || catalogs.isEmpty()) stremioCache = generation to Timed(now, channels)
        return channels to ""
    }

    // --- ESPN scoreboards, one bounded request per league, cached a minute (stale results kept 15 minutes).
    private suspend fun scoresSource(force: Boolean, zone: ZoneId): ScoreBatch {
        val collected = ConcurrentHashMap<String, List<EspnScores.Game>>()
        val stale = ConcurrentHashMap.newKeySet<String>()
        withTimeoutOrNull(SCORES_TIMEOUT) {
            coroutineScope {
                val permits = Semaphore(5)
                EspnScores.leagues.map { league -> async {
                    val cached = scoreCache[league.path]
                    val age = System.currentTimeMillis() - (cached?.at ?: 0L)
                    if (cached != null && EspnScores.cacheFresh(cached.value, cached.at, System.currentTimeMillis(), force)) { collected[league.path] = cached.value; return@async }
                    val response = permits.withPermit { http.get(league.url) }
                    val games = response?.let { text ->
                        runCatching {
                            // A malformed response is unavailable, not a valid empty schedule.
                            if (JSONObject(text).optJSONArray("events") == null) null
                            else EspnScores.parse(text, league, System.currentTimeMillis(), zone)
                        }.getOrNull()
                    }
                    if (games != null) { scoreCache[league.path] = Timed(System.currentTimeMillis(), games); collected[league.path] = games }
                    else cached?.takeIf { EspnScores.cacheFallbackUsable(it.value, it.at, System.currentTimeMillis()) }?.let { collected[league.path] = it.value; stale += league.path }
                    if (games != null && response != null && league.sport == SportCategories.NFL) {
                        // Publish/cache the working default first: a slow adjacent week must not discard live NFL scores.
                        EspnScores.nflSupplementUrls(response, games).forEach { url ->
                            val extra = withTimeoutOrNull(3_000L) { permits.withPermit { http.get(url) } }?.let { text ->
                                runCatching { EspnScores.parse(text, league, System.currentTimeMillis(), zone) }.getOrNull()
                            }.orEmpty()
                            if (extra.isNotEmpty()) {
                                val merged = EspnScores.order(collected[league.path].orEmpty() + extra).distinctBy { it.id }
                                collected[league.path] = merged
                                scoreCache[league.path] = Timed(System.currentTimeMillis(), merged)
                            }
                        }
                    }
                } }.awaitAll()
            }
        }
        // Include a bounded stale fallback even when the overall deadline cancelled an individual league request.
        val now = System.currentTimeMillis()
        EspnScores.leagues.filter { !collected.containsKey(it.path) }.forEach { league ->
            scoreCache[league.path]?.takeIf { EspnScores.cacheFallbackUsable(it.value, it.at, now) }?.let {
                collected[league.path] = it.value; stale += league.path
            }
        }
        val unavailable = EspnScores.leagues.filter { !collected.containsKey(it.path) }
        EspnScores.leagues.forEach { league ->
            FeedDiagnostics.event(context, "sports_scores_league", mapOf("league" to league.label,
                "games" to collected[league.path]?.size, "status" to when {
                    !collected.containsKey(league.path) -> "unavailable"
                    league.path in stale -> "saved"
                    else -> "fresh"
                }))
        }
        return ScoreBatch(collected.values.flatten(), unavailable.map { it.label }, EspnScores.leagues.filter { it.path in stale }.map { it.label })
    }

    // --- Headlines: ABC Sport RSS and ESPN league news, cached ten minutes.
    private suspend fun newsSource(force: Boolean): List<SportsNews.Headline> {
        val collected = ConcurrentHashMap<String, List<SportsNews.Headline>>()
        withTimeoutOrNull(NEWS_TIMEOUT) {
            coroutineScope {
                SportsNews.feeds.map { feed -> async {
                    val cached = newsCache[feed.url]
                    val age = System.currentTimeMillis() - (cached?.at ?: 0L)
                    if (cached != null && age < (if (force) 60_000L else 10 * 60_000L)) { collected[feed.url] = cached.value; return@async }
                    val items = http.get(feed.url)?.let { text ->
                        runCatching { if (feed.rss) SportsNews.rss(text, feed.label) else SportsNews.espn(text, feed.label) }.getOrNull()
                    }?.takeIf { it.isNotEmpty() }
                    if (items != null) { newsCache[feed.url] = Timed(System.currentTimeMillis(), items); collected[feed.url] = items }
                    else cached?.let { collected[feed.url] = it.value }
                } }.awaitAll()
            }
        }
        return collected.values.flatten()
    }

    override suspend fun resolvePlayback(item: ContentItem): PlaybackResult = when (item.metadata["sportsSource"]) {
        "news" -> PlaybackResult.Unavailable("Headline only")
        "tv" -> item.playbackUri?.takeIf { it.isNotBlank() }?.let { PlaybackResult.Ready(it, "com.tcl.tv") }
            ?: PlaybackResult.App("com.tcl.tv", "No tuned channel for this programme; opening native TV")
        "stremio" -> item.playbackUri?.takeIf { it.startsWith("stremio:///detail/") }?.let { PlaybackResult.Ready(it, StremioSportChannels.PACKAGE) }
            ?: PlaybackResult.App(StremioSportChannels.PACKAGE)
        else -> PlaybackResult.App("au.com.kayosports.tv", "Opening Kayo")
    }

    private companion object {
        const val KAYO_TIMEOUT = 32_000L
        const val LOCAL_TIMEOUT = 15_000L
        const val STREMIO_TIMEOUT = 32_000L
        const val STREMIO_FETCH_TIMEOUT = 24_000L
        const val STREMIO_CATALOGUES = 60
        const val STREMIO_CACHE = 15 * 60_000L
        const val SCORES_TIMEOUT = 18_000L
        const val NEWS_TIMEOUT = 14_000L
        // Compiled once: these run for every channel and guide-channel pair on each 30-second refresh.
        val REGION = Regex("\\b(melbourne|victoria|vic|hd)\\b")
        val NON_ALPHANUMERIC = Regex("[^a-z0-9]")
        val RADIO = Regex("(?i)radio|\\bfm\\b|abc sport")
    }
}
