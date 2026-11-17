package com.majendra.tvlauncher.sportscentre

import android.content.Context
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.FeedRepository
import com.majendra.tvlauncher.data.FeedResult
import com.majendra.tvlauncher.providers.sports.SportsCentreProvider
import com.majendra.tvlauncher.providers.sports.SportsRows
import com.majendra.tvlauncher.providers.sports.EspnScores
import java.io.IOException
import java.net.URI
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object SportsCachePolicy {
    fun fresh(at: Long, now: Long, ttl: Long) = at > 0 && now >= at && now - at < ttl
    fun aflFixturesFresh(at: Long, now: Long) = fresh(at, now, 30 * 60_000L)
    fun aflLiveFresh(at: Long, now: Long) = fresh(at, now, 60_000L)
}

/** Shared by the activity and optional dream; no background work starts from this constructor. */
class SportsDataRepository(private val context: Context, private val feedRepository: FeedRepository) {
    private data class Cached<T>(val value: T, val at: Long)
    private val fetch = SportsDataFetch()
    private val matches = ConcurrentHashMap<String, Cached<MatchDetail>>()
    private val ladders = ConcurrentHashMap<String, Cached<List<LadderTable>>>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    @Volatile private var aflContact: String? = null
    @Volatile private var aflGames: Cached<List<ContentItem>>? = null
    private var aflFixturesAt = 0L
    private var aflFixturesAttemptAt = 0L
    private var aflLiveAt = 0L
    @Volatile private var aflScoreContact: String? = null
    private var aflYear = 0
    private var aflResultsMissing = false
    @Volatile var latest: SportsSnapshot? = null
        private set

    /** Safe for progressive UI updates: uses memory and the guide file's timestamp, with no network request. */
    fun fromFeed(feed: FeedResult): SportsSnapshot {
        val provider = feedRepository.adapter("kayo") as? SportsCentreProvider
        val guide = provider?.guideSnapshot()
        val now = System.currentTimeMillis()
        val cached = aflGames?.takeIf { configuredContact() != null && configuredContact() == aflScoreContact && SportsCachePolicy.fresh(it.at, now, 24 * 60 * 60_000L) }
        val usable = SportsDataParsers.cachedAflForDisplay(cached?.value.orEmpty(), now)
        val games = SportsDataParsers.mergeAfl(SportsDataParsers.uniqueGames(feed.rows.flatMap { it.items }), usable, now)
        val programmes = feed.rows.flatMap { it.items }.filter { it.startTime != null && it.metadata["sportsSource"] in setOf("kayo", "tv") }
        val matched = games.map { item ->
            if (item.metadata["dataSource"] != "Squiggle") item else {
                val game = EspnScores.Game(item.id, EspnScores.leagues.first { it.sport == "AFL" }, item.title, item.subtitle.orEmpty(),
                    when (item.metadata["scoreState"]) { "live" -> EspnScores.State.LIVE; "final" -> EspnScores.State.FINAL; else -> EspnScores.State.UPCOMING },
                    item.startTime ?: now, item.description, null,
                    EspnScores.Side(item.metadata["homeName"].orEmpty(), null, null, item.metadata["homeScore"], false),
                    EspnScores.Side(item.metadata["awayName"].orEmpty(), null, null, item.metadata["awayScore"], false))
                val on = SportsRows.broadcast(game, programmes, now)
                val tv = on?.metadata?.get("sportsSource") == "tv" && !on?.playbackUri.isNullOrBlank()
                if (on == null || (!tv && on.metadata["sportsSource"] != "kayo")) item else item.copy(playbackUri = on.playbackUri?.takeIf { tv },
                    metadata = item.metadata + mapOf("watch" to "true", "footer" to "${on.metadata["chip"].orEmpty()} · ${on.metadata["footer"].orEmpty()}",
                        "playbackPackage" to (if (tv) "com.tcl.tv" else "au.com.kayosports.tv")) +
                        (if (tv) mapOf("sportsSource" to "tv") else emptyMap()))
            }
        }
        return SportsSnapshot(feed, matched, guide?.first, feed.updatedAt,
            guideUpdatedAt = guide?.second ?: 0).also { latest = it }
    }

    suspend fun dashboard(force: Boolean = false): SportsSnapshot {
        val feed = feedRepository.refresh("kayo", force).await()
        refreshAflGames()
        return fromFeed(feed)
    }

    suspend fun match(item: ContentItem): MatchDetail = withContext(Dispatchers.IO) {
        if (item.metadata["dataSource"] == "Squiggle") {
            val saved = aflGames?.value?.firstOrNull { it.id == item.id } ?: item
            val stale = saved.isLive && SportsDataParsers.cachedAflForDisplay(listOf(saved), System.currentTimeMillis()).isEmpty()
            val shown = if (stale) saved.copy(isLive = false, metadata = saved.metadata + mapOf("scoreState" to "unknown", "clock" to "Updates unavailable")) else saved
            return@withContext MatchDetail(shown,
            stats = listOf("Goals" to "Goals", "Behinds" to "Behinds").mapNotNull { (label, field) ->
                val away = shown.metadata["away$field"]; val home = shown.metadata["home$field"]
                if (away != null && home != null) TeamStat(label, away, home) else null
            }, note = if (stale) "Live AFL updates unavailable. Showing saved score details." else "Squiggle provides scores, goals and behinds. Quarter statistics are unavailable; Standard API updates can be delayed up to 60 seconds.",
            updatedAt = shown.metadata["sourceUpdatedAt"]?.toLongOrNull() ?: 0)
        }
        val identity = SportsDataParsers.matchIdentity(item)
            ?: return@withContext MatchDetail(item, note = "Detailed statistics are not available for this listing.")
        val key = "${identity.first}:${identity.second}"
        locks.getOrPut("match:$key") { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            val old = matches[key]
            if (old != null && SportsCachePolicy.fresh(old.at, now, 60_000L)) return@withLock old.value
            val json = fetch.get("https://site.api.espn.com/apis/site/v2/sports/${identity.first}/summary?event=${identity.second}")
            val parsed = json?.let { text -> safely { SportsDataParsers.match(text, item, now) } }
            if (parsed != null) {
                if (matches.size >= 80) matches.entries.minByOrNull { it.value.at }?.let { matches.remove(it.key) }
                matches[key] = Cached(parsed, now)
                parsed
            } else old?.value?.copy(note = "Match update unavailable. Showing saved details.")
                ?: MatchDetail(item, note = "Match details are temporarily unavailable.")
        }
    }

    suspend fun ladder(league: String): List<LadderTable> = withContext(Dispatchers.IO) {
        val key = when (league.uppercase()) { "PREMIER LEAGUE", "SOCCER" -> "EPL"; else -> league.uppercase() }
        val path = SportsDataParsers.leaguePaths[key]
            ?: return@withContext listOf(unavailable(key, "A ladder is not available from the configured sources."))
        locks.getOrPut("ladder:$key") { Mutex() }.withLock {
            val now = System.currentTimeMillis()
            val contact = configuredContact()
            if (key == "AFL" && contact != aflContact) { ladders.remove(key); aflContact = contact }
            val old = ladders[key]
            if (old != null && SportsCachePolicy.fresh(old.at, now, 6 * 60 * 60_000L)) return@withLock old.value
            val squiggle = if (key == "AFL" && contact != null) {
                val year = java.time.LocalDate.now(ZoneId.of("Australia/Melbourne")).year
                fetch.get("https://api.squiggle.com.au/?q=standings;year=$year", "SportsCentre/1.0 (personal TV dashboard; contact: $contact)")
                    ?.let { text -> safely { SportsDataParsers.squiggleStandings(text, now) } }?.takeIf { it.isNotEmpty() }
            } else null
            val loaded = squiggle ?: fetch.get("https://site.api.espn.com/apis/v2/sports/$path/standings")
                ?.let { text -> safely { SportsDataParsers.standings(text, key, now) } }?.takeIf { it.isNotEmpty() }
                ?.map { table -> if (key == "AFL") table.copy(note = if (contact == null) "ESPN standings." else "Squiggle unavailable; showing ESPN standings.") else table }
            if (loaded != null) {
                ladders[key] = Cached(loaded, now)
                loaded
            } else old?.value?.map { it.copy(note = "Ladder update unavailable. Showing saved standings.") }
                ?: listOf(unavailable(key, "Standings are temporarily unavailable. Try again later."))
        }
    }

    private fun unavailable(league: String, note: String) = LadderTable(league, "$league standings", emptyList(), emptyList(), 0, note)
    private fun configuredContact(): String? = null // Public edition does not collect contact details.

    private suspend fun refreshAflGames() = withContext(Dispatchers.IO) {
        locks.getOrPut("afl-games") { Mutex() }.withLock {
            val contact = configuredContact()
            if (contact == null) { aflGames = null; aflScoreContact = null; return@withLock }
            val now = System.currentTimeMillis()
            val year = java.time.LocalDate.now(ZoneId.of("Australia/Melbourne")).year
            if (contact != aflScoreContact || year != aflYear) {
                aflGames = null; aflFixturesAt = 0; aflFixturesAttemptAt = 0; aflLiveAt = 0; aflResultsMissing = false; aflScoreContact = contact; aflYear = year
            }
            val ua = "SportsCentre/1.0 (personal TV dashboard; contact: $contact)"
            suspend fun season(): Boolean {
                if (SportsCachePolicy.fresh(aflFixturesAttemptAt, now, 60_000L)) return false
                aflFixturesAttemptAt = now
                val json = withTimeoutOrNull(6_000L) { fetch.get("https://api.squiggle.com.au/?q=games;year=$year", ua) }
                val games = json?.let { safely { SportsDataParsers.squiggleGames(it, now) } } ?: return false
                aflGames = Cached(games, now); aflFixturesAt = now; aflResultsMissing = false
                return true
            }
            if ((!SportsCachePolicy.aflFixturesFresh(aflFixturesAt, now) || aflResultsMissing) && !season()) return@withLock
            val cached = aflGames?.value.orEmpty()
            val mayBeLive = cached.any { it.isLive || (it.metadata["scoreState"] == "upcoming" && it.startTime?.let { start -> now in (start - 5 * 60_000L)..(start + 4 * 60 * 60_000L) } == true) }
            if (mayBeLive && !SportsCachePolicy.aflLiveFresh(aflLiveAt, now)) {
                aflLiveAt = now
                val json = withTimeoutOrNull(3_000L) { fetch.get("https://api.squiggle.com.au/?q=games;year=$year;live=1", ua) }
                val live = json?.let { safely { SportsDataParsers.squiggleGames(it, now) } }
                if (live != null) {
                    val liveIds = live.map { it.id }.toSet()
                    val finished = cached.any { it.isLive && it.id !in liveIds }
                    aflGames = Cached(SportsDataParsers.reconcileAflLive(cached, live), now)
                    if (finished) { aflResultsMissing = true; season() }
                }
            }
        }
    }
    private inline fun <T> safely(block: () -> T): T? = try { block() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}

/** Only fixed public sports hosts are accepted. Never follows upstream links or redirects. */
private class SportsDataFetch {
    private val client by lazy { com.majendra.tvlauncher.data.LauncherHttp.client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build() }
    suspend fun get(url: String, userAgent: String = "SportsCentre/1.0 personal TV dashboard"): String? = suspendCancellableCoroutine { continuation ->
        val uri = try { URI(url) } catch (_: Exception) { null }
        if (uri == null || uri.scheme != "https" || uri.host !in setOf("site.api.espn.com", "api.squiggle.com.au") || uri.userInfo != null || uri.port !in setOf(-1, 443)) {
            continuation.resume(null); return@suspendCancellableCoroutine
        }
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resume(null) }
            override fun onResponse(call: Call, response: Response) {
                val body = try { response.use { r ->
                    val value = r.body
                    if (!r.isSuccessful || value == null || value.contentLength() > MAX_BYTES) null
                    else value.source().let { source -> source.request(MAX_BYTES + 1); if (source.buffer.size > MAX_BYTES) null else source.readUtf8() }
                } } catch (_: IOException) { null }
                if (continuation.isActive) continuation.resume(body)
            }
        })
    }
    private companion object { const val MAX_BYTES = 2_500_000L }
}
