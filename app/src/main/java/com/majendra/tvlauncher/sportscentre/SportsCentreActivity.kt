package com.majendra.tvlauncher.sportscentre

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import com.majendra.tvlauncher.LauncherApplication
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.FeedResult
import com.majendra.tvlauncher.data.FeedStatus
import com.majendra.tvlauncher.playback.PlaybackRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Full-screen sports workspace. All polling is tied to this visible Activity. */
class SportsCentreActivity : ComponentActivity() {
    private val app get() = application as LauncherApplication
    private val data get() = app.sportsData
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var headerView: View
    private lateinit var headerGap: View
    private lateinit var clock: TextView
    private lateinit var synced: TextView
    private lateinit var hint: TextView
    private val nav = linkedMapOf<String, TextView>()
    private var page = "home"
    private var filter = "All"
    private var league = "EPL"
    private var returnPage = "home"
    private var snapshot = SportsSnapshot(FeedResult(status = FeedStatus.LOADING), emptyList(), null, 0)
    private var match: MatchDetail? = null
    private var tables = emptyList<LadderTable>()
    private var active: Job? = null
    private var detailsJob: Job? = null
    private var matchKey: String? = null
    private var initialFocus = true
    private var lastLeftAt = 0L
    private var focusGeneration = 0L

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        page = state?.getString("page")?.takeIf { it in setOf("home", "live", "ladders", "guide") } ?: "home"
        filter = state?.getString("filter") ?: "All"
        league = state?.getString("league") ?: "EPL"
        root = SportsUi.column(this).apply {
            setBackgroundColor(SportsUi.BACKGROUND)
            setPadding(sdp(40), sdp(22), sdp(40), sdp(10))
        }
        headerView = header()
        headerGap = SportsUi.space(this, 6)
        root.addView(headerView, LinearLayout.LayoutParams(-1, sdp(48)))
        root.addView(headerGap)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        hint = SportsUi.label(this, "OK  Select     Back  Launcher     ⋯  Screensaver & sources", 11f, SportsUi.MUTED)
        root.addView(hint, LinearLayout.LayoutParams(-1, sdp(20)).apply { topMargin = sdp(8) })
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (page == "home") exitToLauncher() else show(if (page == "match") returnPage else "home") }
        })
        snapshot = data.latest ?: snapshot
        render(false)
        handlePageIntent(intent)
    }

    private fun header(): View {
        val row = SportsUi.row(this)
        val brand = SportsUi.row(this)
        brand.addView(SportsUi.button(this, "‹ Launcher", "nav:launcher") { exitToLauncher() },
            LinearLayout.LayoutParams(-2, sdp(36)).apply { marginEnd = sdp(9) })
        row.addView(brand, LinearLayout.LayoutParams(0, -1, 1f))
        for ((id, title) in listOf("home" to "Home", "live" to "Live", "ladders" to "Ladders", "guide" to "Kayo guide")) {
            val button = SportsUi.button(this, title, "nav:" + id) { show(id) }
            nav[id] = button
            row.addView(button, LinearLayout.LayoutParams(-2, sdp(36)).apply { marginStart = sdp(3) })
        }
        synced = SportsUi.label(this, "Loading…", 9f, SportsUi.MUTED).apply { gravity = Gravity.END; maxLines = 1 }
        row.addView(synced, LinearLayout.LayoutParams(sdp(78), -2).apply { marginStart = sdp(9) })
        clock = SportsUi.label(this, "", 16f, SportsUi.TEXT, true, true).apply { gravity = Gravity.END }
        row.addView(clock, LinearLayout.LayoutParams(sdp(83), -2).apply { marginStart = sdp(8) })
        row.addView(SportsUi.button(this, "⋯", "nav:more") { menu() }.apply {
            setPadding(0, 0, 0, 0); textSize = 22f; contentDescription = "More sports options"
        },
            LinearLayout.LayoutParams(sdp(32), sdp(36)).apply { marginStart = sdp(6) })
        return row
    }

    override fun onResume() {
        super.onResume()
        active?.cancel()
        active = lifecycleScope.launch {
            coroutineScope {
                launch {
                    app.repository.updates("kayo").filterNotNull().collect { feed ->
                        if (feed.status == FeedStatus.LOADING && feed.rows.isEmpty() && snapshot.feed.rows.isNotEmpty()) return@collect
                        snapshot = data.fromFeed(feed)
                        if (page == "home" || page == "live" || page == "guide") render()
                    }
                }
                launch {
                    while (isActive) {
                        try {
                            snapshot = data.dashboard()
                            if (page == "home" || page == "live" || page == "guide") render()
                            if (page == "match") refreshMatch()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { synced.text = "Update unavailable" }
                        delay(60_000)
                    }
                }
                launch { while (isActive) { updateClock(); delay(1_000) } }
            }
        }
        if (page == "ladders") loadLadder() else if (page == "match") match?.item?.let { openMatch(it, false) }
    }

    override fun onPause() {
        active?.cancel(); active = null
        detailsJob?.cancel(); detailsJob = null
        super.onPause()
    }

    @android.annotation.SuppressLint("RestrictedApi") // Same Activity override as the launcher's remote key handler.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (event.keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
                initialFocus = false
                focusGeneration++
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (lastLeftAt > 0 && event.eventTime - lastLeftAt <= 350) {
                    lastLeftAt = 0; exitToLauncher(); return true
                }
                lastLeftAt = event.eventTime
            } else lastLeftAt = 0
        }
        return super.dispatchKeyEvent(event)
    }

    private fun exitToLauncher() {
        startActivity(Intent(this, com.majendra.tvlauncher.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(com.majendra.tvlauncher.MainActivity.EXTRA_SIDEBAR, "kayo"))
        finish()
    }

    private fun updateClock() {
        val now = ZonedDateTime.now(ZoneId.of("Australia/Melbourne"))
        clock.text = now.format(DateTimeFormatter.ofPattern("h:mm a")).lowercase() + "\n" + now.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        synced.text = if (snapshot.updatedAt <= 0) "Loading…" else {
            val age = ((System.currentTimeMillis() - snapshot.updatedAt).coerceAtLeast(0) / 1_000)
            "●  Updated " + (if (age < 60) age.toString() + "s" else (age / 60).toString() + "m") + " ago"
        }
    }

    private fun show(id: String) {
        if (id !in setOf("home", "live", "ladders", "guide")) return
        detailsJob?.cancel()
        page = id
        initialFocus = true
        render(false)
        if (id == "ladders") loadLadder()
    }

    private fun render(restore: Boolean = true) {
        if (!::body.isInitialized) return
        val oldTag = if (restore) currentFocus?.tag as? String else null
        val oldScroll = body.getChildAt(0)?.let { findScroll(it)?.scrollY } ?: 0
        val renderedPage = page
        val renderedFocusGeneration = focusGeneration
        val dashboardPage = page == "home" || page == "live"
        headerView.visibility = if (dashboardPage) View.VISIBLE else View.GONE
        headerGap.visibility = if (dashboardPage) View.VISIBLE else View.GONE
        val content = when (page) {
            "match" -> MatchPage(this).render(match ?: MatchDetail(snapshot.games.firstOrNull() ?: ContentItem("none", "kayo", "Match unavailable")),
                snapshot.games.filter { it.isLive && it.id != match?.item?.id }.take(4),
                { openMatch(it) }, { openItem(it) }, { show(returnPage) })
            "ladders" -> LaddersPage(this).render(tables, { league = it; loadLadder() }, { show("home") }, league)
            "guide" -> GuidePage(this).render(snapshot.guide, snapshot.guideUpdatedAt, { openKayo() }, { show("home") })
            else -> SportsHomePage(this).render(snapshot, if (page == "live") "Live" else filter,
                { filter = it; render() }, { openMatch(it) }, { openItem(it) })
        }
        body.removeAllViews()
        body.addView(content, FrameLayout.LayoutParams(-1, -1))
        nav.forEach { (id, button) ->
            val selected = id == page
            button.setTextColor(if (selected) SportsUi.BACKGROUND else SportsUi.TEXT)
            button.background = SportsUi.shape(this, if (selected) SportsUi.TEXT else SportsUi.SURFACE, 22,
                if (button.hasFocus()) SportsUi.LIME else null)
            button.setOnFocusChangeListener { v, focus ->
                v.background = SportsUi.shape(this, if (selected) SportsUi.TEXT else SportsUi.SURFACE, 22, if (focus) SportsUi.LIME else null)
            }
        }
        hint.text = when (page) {
            "match" -> "OK  Watch / select     Back  Sports Centre"
            "home" -> "OK  Select     Back / double Left  Launcher     ⋯  Screensaver & sources"
            else -> "OK  Select     Back  Home     Double Left  Launcher"
        }
        content.post {
            if (page != renderedPage || isFinishing || body.getChildAt(0) !== content || focusGeneration != renderedFocusGeneration) return@post
            if (dashboardPage && !initialFocus && oldTag?.startsWith("nav:") == true) return@post
            val target = if (initialFocus) null else oldTag?.let { content.findViewWithTag<View>(it) }
            if (target != null) target.requestFocus()
            else if (initialFocus) {
                val first = when (page) {
                    "match" -> match?.item?.let { content.findViewWithTag<View>("match:watch:" + SportsIdentity.key(it)) }
                    "ladders" -> content.findViewWithTag<View>("ladder-tab:" + league)
                    "guide" -> content.findViewWithTag<View>("guide:open")
                    "home" -> snapshot.feed.rows.firstOrNull { it.id == "sports-kayo-now" }?.items?.firstOrNull()
                        ?.let { content.findViewWithTag<View>("guide:${it.id}") }
                        ?: nav[page].takeUnless { snapshot.feed.status == FeedStatus.LOADING }
                    else -> nav[page]
                }
                if (first?.requestFocus() == true) initialFocus = false
            }
            if (restore && target == null) findScroll(content)?.scrollTo(0, oldScroll)
        }
    }

    private fun findScroll(view: View): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findScroll(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun openMatch(item: ContentItem, remember: Boolean = true) {
        if (remember && page != "match") returnPage = page
        detailsJob?.cancel()
        page = "match"; matchKey = gameKey(item)
        match = MatchDetail(item, note = "Loading match details…")
        initialFocus = true; render(false)
        detailsJob = lifecycleScope.launch {
            val detail = try { data.match(item) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { MatchDetail(item, note = "Match details unavailable. The scoreboard remains available.") }
            if (page == "match" && matchKey == gameKey(item)) { match = detail; render() }
        }
    }

    private fun gameKey(item: ContentItem) = SportsIdentity.key(item)

    private suspend fun refreshMatch() {
        val key = matchKey ?: return
        val item = snapshot.games.firstOrNull { gameKey(it) == key } ?: match?.item ?: return
        val updated = data.match(item)
        if (page == "match" && matchKey == key) { match = updated; render() }
    }

    private fun loadLadder() {
        detailsJob?.cancel()
        val requested = league
        tables = listOf(LadderTable(requested, requested + " standings", emptyList(), emptyList(), 0, "Loading standings…"))
        render()
        detailsJob = lifecycleScope.launch {
            val loaded = try { data.ladder(requested) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { listOf(LadderTable(requested, requested + " standings", emptyList(), emptyList(), 0, "Standings unavailable.")) }
            if (page == "ladders" && league == requested) { tables = loaded; render() }
        }
    }

    private fun openItem(item: ContentItem) {
        if (item.metadata["openDetails"] == "true") {
            AlertDialog.Builder(this).setTitle(item.title).setMessage(item.description ?: "No further description is available.")
                .setPositiveButton("Close", null).show()
            return
        }
        lifecycleScope.launch {
            try {
                val result = app.repository.adapter("kayo").resolvePlayback(item)
                PlaybackRouter(this@SportsCentreActivity).open(result, item.metadata["playbackPackage"] ?: "au.com.kayosports.tv", item)
                    ?.let { Toast.makeText(this@SportsCentreActivity, it, Toast.LENGTH_SHORT).show() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { Toast.makeText(this@SportsCentreActivity, "Unable to open the app.", Toast.LENGTH_SHORT).show() }
        }
    }

    private fun openKayo() { PlaybackRouter(this).openApp("au.com.kayosports.tv")?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() } }

    private fun menu() {
        AlertDialog.Builder(this).setTitle("Sports Centre").setItems(arrayOf("Refresh now", "Sports screensaver", "Data sources", "Back to launcher")) { _, index ->
            when (index) {
                0 -> lifecycleScope.launch { snapshot = data.dashboard(true); render() }
                1 -> startActivity(Intent(this, SportsScreensaverPreviewActivity::class.java))
                2 -> sources()
                3 -> exitToLauncher()
            }
        }.show()
    }

    private fun sources() {
        AlertDialog.Builder(this).setTitle("Data sources")
            .setMessage("Scores and tables: public ESPN feeds. Kayo guide: Open EPG. No accounts, contact email or linked add-ons are configured in this edition.")
            .setPositiveButton("Close", null).show()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handlePageIntent(intent) }
    private fun handlePageIntent(intent: Intent?) {
        when (val requested = intent?.getStringExtra("page")) {
            "home", "live", "ladders", "guide" -> show(requested)
            "screensaver" -> startActivity(Intent(this, SportsScreensaverPreviewActivity::class.java))
            "sources" -> sources()
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", if (page == "match") returnPage else page)
        outState.putString("filter", filter); outState.putString("league", league)
        super.onSaveInstanceState(outState)
    }
}
