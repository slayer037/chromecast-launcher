package com.majendra.tvlauncher.sportscentre

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.FeedStatus
import com.majendra.tvlauncher.providers.StremioMetadataPolicy
import com.majendra.tvlauncher.providers.sports.SportCategories
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Selection/order rules are shared by the dashboard and its focused tests. */
object SportsHomePolicy {
    fun matches(item: ContentItem, filter: String): Boolean = when (filter.uppercase(Locale.ROOT)) {
        "ALL", "LIVE" -> true
        "EPL" -> item.metadata["league"] in setOf("Premier League", "EPL")
        "NBA" -> item.metadata["league"] == "NBA"
        "MLB" -> item.metadata["league"] == "MLB" || item.metadata["sport"] == SportCategories.BASEBALL
        "CRICKET" -> item.metadata["sport"] == SportCategories.CRICKET
        else -> item.metadata["sport"].equals(filter, true) || item.metadata["league"].equals(filter, true)
    }

    fun games(items: List<ContentItem>, filter: String, state: String): List<ContentItem> = items
        .filter { matches(it, filter) && it.metadata["scoreState"] == state }
        .distinctBy(SportsIdentity::key)
        .sortedWith(compareBy<ContentItem> {
            SportCategories.followed.indexOf(it.metadata["sport"]).let { rank -> if (rank < 0) Int.MAX_VALUE else rank }
        }.thenBy { if (state == "final") -(it.startTime ?: 0L) else it.startTime ?: Long.MAX_VALUE })

    fun watchLabel(item: ContentItem): String? = item.metadata["footer"]
        ?.takeIf { item.metadata["watch"] == "true" }
        ?.replace("📺", "")?.trim()?.takeIf(String::isNotBlank)

    /** Keep weekly NFL results in view between games without presenting finals as live. */
    fun featured(items: List<ContentItem>, filter: String): List<ContentItem> {
        val live = games(items, filter, "live")
        if (filter.equals("Live", true) || (!filter.equals("All", true) && !filter.equals("NFL", true)) ||
            live.any { it.metadata["sport"] == SportCategories.NFL }) return live
        val finals = games(items, "NFL", "final").take((3 - live.size).coerceAtLeast(1))
        if (finals.isEmpty()) return live
        return (listOf(finals.first()) + live + finals.drop(1)).distinctBy(SportsIdentity::key)
    }

    fun featuredTitle(items: List<ContentItem>): String = when {
        items.any { it.metadata["scoreState"] == "final" } && items.any { it.metadata["scoreState"] == "live" } -> "Live & latest scores"
        items.any { it.metadata["scoreState"] == "final" } -> "Latest scores"
        else -> "Live now  ${items.size}"
    }
}

/** Native dashboard content; its Activity owns navigation, refreshing and focus restoration. */
class SportsHomePage(private val context: Context, availableWidthDp: Int? = null) {
    private val zone = ZoneId.of("Australia/Melbourne")
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH).withZone(zone)
    private val fixtureTime = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH).withZone(zone)
    private val pageWidth = availableWidthDp ?: ((context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density).toInt() - 88)
    private val liveWidth = ((pageWidth - 24) / 3).coerceIn(190, 330)
    private val compactWidth = ((pageWidth - 36) / 4).coerceIn(178, 248)

    fun render(snapshot: SportsSnapshot, filter: String, onFilter: (String) -> Unit,
        onMatch: (ContentItem) -> Unit, onOpen: (ContentItem) -> Unit, showFilters: Boolean = true): View {
        val liveOnly = filter.equals("Live", true)
        val body = SportsUi.column(context).apply { setPadding(2, context.sdp(1), 2, context.sdp(24)) }
        if (!liveOnly) {
            val kayo = snapshot.feed.rows.firstOrNull { it.id == "sports-kayo-now" }?.items.orEmpty()
            val updated = if (snapshot.guideUpdatedAt > 0) "Guide updated ${time.format(Instant.ofEpochMilli(snapshot.guideUpdatedAt)).lowercase(Locale.ENGLISH)} · ${snapshot.guideSource}"
                else "Guide update time unavailable"
            section(body, "On Kayo now", updated)
            if (kayo.isEmpty()) body.addView(empty("Kayo programme guide is unavailable. Open the Kayo guide tab to try again."))
            else body.addView(strip(kayo.take(12).map { guideCard(it, onOpen) }))
            body.addView(SportsUi.space(context, 8))
        }
        val live = SportsHomePolicy.games(snapshot.games, filter, "live")
        val featured = SportsHomePolicy.featured(snapshot.games, filter)
        val titleRow = SportsUi.row(context)
        titleRow.addView(SportsUi.heading(context, SportsHomePolicy.featuredTitle(featured), 19f), LinearLayout.LayoutParams(0, -2, 1f))
        if (!liveOnly && showFilters) titleRow.addView(filters(filter, onFilter), LinearLayout.LayoutParams(-2, -2))
        body.addView(titleRow, LinearLayout.LayoutParams(-1, -2))
        body.addView(SportsUi.space(context, 6))
        if (featured.isEmpty()) {
            val note = snapshot.feed.rows.firstOrNull { it.id == "sports-nfl-scores" && it.items.isEmpty() }?.emptyMessage
                ?.takeIf { filter.equals("NFL", true) }
            val message = when {
                snapshot.feed.status == FeedStatus.LOADING -> "Loading live scores…"
                snapshot.feed.status == FeedStatus.SOURCE_UNAVAILABLE -> "Live scores are currently unavailable."
                snapshot.feed.message.contains("Scores unavailable:", true) -> "Some live scores are unavailable. Check the individual sport status below."
                liveOnly -> "No live games in the current feed window."
                else -> "No live games in this view. Upcoming fixtures and recent results are below."
            }
            body.addView(empty(note ?: message))
        } else body.addView(strip(featured.take(if (liveOnly) 18 else 9).map { gameCard(it, liveWidth, true, onMatch) }))
        if (live.isNotEmpty() && (liveOnly || filter.equals("All", true) || filter.equals("NFL", true))) {
            snapshot.feed.rows.firstOrNull { it.id == "sports-nfl-scores" }?.emptyMessage?.let {
                body.addView(SportsUi.space(context, 7))
                body.addView(SportsUi.label(context, it, 12f, SportsUi.MUTED))
            }
        }
        if (liveOnly) return scroll(body)

        val upcoming = balanced(SportsHomePolicy.games(snapshot.games, filter, "upcoming"))
        section(body, "Coming up", "Local start times · odds unavailable")
        if (upcoming.isEmpty()) body.addView(empty("No upcoming fixtures in the current feed window."))
        else body.addView(strip(upcoming.take(12).map { gameCard(it, compactWidth, false, onMatch) }))

        val nflResults = SportsHomePolicy.games(snapshot.games, "NFL", "final")
        if (filter.equals("NFL", true) || (filter.equals("All", true) && live.none { it.metadata["sport"] == SportCategories.NFL })) {
            if (nflResults.isNotEmpty()) {
                section(body, "Latest NFL scores")
                body.addView(strip(nflResults.take(16).map { gameCard(it, compactWidth, false, onMatch, "match:recent") }))
            } else snapshot.feed.rows.firstOrNull { it.id == "sports-nfl-scores" }?.emptyMessage?.let {
                section(body, "NFL scores"); body.addView(empty(it))
            }
        }

        val listings = snapshot.feed.rows.filter { it.id == "sports-stremio-events" || it.id == "sports-stremio" }
            .flatMap { it.items }.filter { SportsHomePolicy.matches(it, filter) }.distinctBy { it.id }
        if (listings.isNotEmpty()) {
            section(body, "Sports listings · Stremio", "Availability comes from your linked add-ons")
            body.addView(strip(listings.take(32).map { listingCard(it, onOpen) }))
        }
        val news = snapshot.feed.rows.firstOrNull { it.id == "sports-news" }?.items.orEmpty()
            .filter { filter.equals("All", true) || SportsHomePolicy.matches(it, filter) }
        if (news.isNotEmpty()) {
            section(body, "Headlines")
            body.addView(strip(news.take(12).map { item ->
                SportsUi.card(context, "news:${item.id}") { onOpen(item) }.apply {
                    layoutParams = stripParams(liveWidth, 116)
                    addView(SportsUi.label(context, item.metadata["newsSource"].orEmpty(), 11f, SportsUi.MUTED, condensed = true))
                    addView(SportsUi.space(context, 10))
                    addView(SportsUi.label(context, item.title, 16f, bold = true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
                }
            }))
        }
        return scroll(body)
    }

    private fun filters(selected: String, onFilter: (String) -> Unit): View {
        val row = SportsUi.row(context)
        for (name in listOf("All", "AFL", "EPL", "NBA", "NFL", "Cricket", "MLB")) {
            val active = selected.equals(name, true)
            row.addView(SportsUi.button(context, name, "filter:$name") { onFilter(name) }.apply {
                textSize = 12f; minHeight = context.sdp(29)
                setPadding(context.sdp(11), context.sdp(6), context.sdp(11), context.sdp(6))
                setTextColor(if (active) SportsUi.BACKGROUND else SportsUi.MUTED)
                background = SportsUi.shape(context, if (active) SportsUi.TEXT else SportsUi.BACKGROUND, 18)
                setOnFocusChangeListener { view, focus ->
                    view.background = SportsUi.shape(context, if (active) SportsUi.TEXT else SportsUi.SURFACE, 18, if (focus) SportsUi.LIME else null)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.sdp(3) })
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(row); layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
    }

    private fun gameCard(item: ContentItem, width: Int, large: Boolean, onMatch: (ContentItem) -> Unit, tagPrefix: String = "match"): View {
        val state = item.metadata["scoreState"]
        return SportsUi.card(context, "$tagPrefix:" + SportsIdentity.key(item)) { onMatch(item) }.apply {
            layoutParams = stripParams(width, if (large) 132 else 110)
            if (!large) setPadding(context.sdp(12), context.sdp(10), context.sdp(12), context.sdp(9))
            contentDescription = listOfNotNull(item.title, item.subtitle).joinToString(". ")
            val head = SportsUi.row(context)
            head.addView(SportsUi.label(context, item.metadata["league"].orEmpty().uppercase(Locale.ROOT), 11f, SportsUi.MUTED, condensed = true), LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(SportsUi.label(context, item.metadata["clock"] ?: item.startTime?.let { fixtureTime.format(Instant.ofEpochMilli(it)) }.orEmpty(),
                11f, if (state == "live") SportsUi.LIVE else SportsUi.TEXT, true, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-2, -2))
            addView(head)
            addView(SportsUi.space(context, if (large) 10 else 6))
            if (item.metadata["homeName"] != null && item.metadata["awayName"] != null) {
                addView(teamLine(item, "away", large))
                addView(SportsUi.space(context, if (large) 6 else 4))
                addView(teamLine(item, "home", large))
            } else {
                addView(SportsUi.label(context, item.title, if (large) 20f else 16f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
            }
            val foot = SportsUi.row(context)
            val watch = SportsHomePolicy.watchLabel(item)
            val footer = if (watch == null) item.metadata["footer"].orEmpty() else ""
            foot.addView(SportsUi.label(context, footer, 10f, SportsUi.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            if (watch != null) foot.addView(SportsUi.label(context, watch, 10f, SportsUi.LIME, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            addView(foot, LinearLayout.LayoutParams(-1, context.sdp(14)).apply { topMargin = context.sdp(3) })
        }
    }

    private fun teamLine(item: ContentItem, side: String, large: Boolean): View = SportsUi.row(context).apply {
        val name = item.metadata["${side}Name"].orEmpty()
        val score = item.metadata["${side}Score"] ?: "—"
        val other = if (side == "home") "away" else "home"
        val thisNumber = score.toDoubleOrNull(); val otherNumber = item.metadata["${other}Score"]?.toDoubleOrNull()
        val behind = thisNumber != null && otherNumber != null && thisNumber < otherNumber
        val color = if (behind) SportsUi.MUTED else SportsUi.TEXT
        if (large) {
            val abbr = item.metadata["${side}Abbr"] ?: name.split(Regex("\\s+")).mapNotNull { it.firstOrNull() }.joinToString("").take(3)
            addView(SportsUi.label(context, abbr, 10f, SportsUi.TEXT, true, true).apply {
                gravity = Gravity.CENTER; background = SportsUi.shape(context, SportsUi.RAISED, 20)
            }, LinearLayout.LayoutParams(context.sdp(28), context.sdp(28)).apply { rightMargin = context.sdp(9) })
        }
        addView(SportsUi.label(context, name, if (large) 17f else 15f, color).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(SportsUi.label(context, score, if (large) 27f else 20f, color, true, true).apply { maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = context.sdp(6) })
    }

    private fun guideCard(item: ContentItem, onOpen: (ContentItem) -> Unit): View = SportsUi.card(context, "guide:${item.id}") { onOpen(item) }.apply {
        layoutParams = stripParams(compactWidth, 64)
        setPadding(context.sdp(14), context.sdp(6), context.sdp(14), context.sdp(6))
        contentDescription = listOfNotNull(item.metadata["channelName"], item.title).joinToString(". ")
        addView(SportsUi.label(context, item.metadata["channelName"].orEmpty(), 11f, SportsUi.LIME, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        addView(SportsUi.space(context, 4))
        addView(SportsUi.label(context, if (item.metadata["scheduleAvailable"] == "false") "Programme guide unavailable" else item.title, 12f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, 0, 1f))
        val progress = item.progress?.takeIf { it.isFinite() }
        if (progress != null) addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; this.progress = (progress.coerceIn(0f, 1f) * 1000).toInt()
            progressTintList = ColorStateList.valueOf(SportsUi.MUTED); progressBackgroundTintList = ColorStateList.valueOf(SportsUi.RULE)
        }, LinearLayout.LayoutParams(-1, context.sdp(3)).apply { topMargin = context.sdp(4) })
    }

    private fun listingCard(item: ContentItem, onOpen: (ContentItem) -> Unit): View = SportsUi.card(context, "stremio:${item.id}") { onOpen(item) }.apply {
        layoutParams = stripParams(154, 258)
        setPadding(context.sdp(5), context.sdp(5), context.sdp(5), context.sdp(9))
        val placeholder = SportsUi.label(context, "Artwork unavailable", 12f, SportsUi.MUTED).apply {
            gravity = Gravity.CENTER; maxLines = 2
        }
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        addView(FrameLayout(context).apply {
            background = SportsUi.shape(context, SportsUi.RAISED, 8)
            addView(placeholder, FrameLayout.LayoutParams(-1, -1))
            addView(image, FrameLayout.LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(-1, context.sdp(194)))
        val url = (item.imageUrl ?: item.landscapeImageUrl)?.takeIf(StremioMetadataPolicy::publicUrl)
        if (url != null) {
            placeholder.text = "Loading artwork"
            context.imageLoader.enqueue(ImageRequest.Builder(context).data(url)
                .size(context.sdp(144), context.sdp(194)).scale(Scale.FIT).crossfade(false)
                .apply { if (item.metadata["accountAuthorised"] == "true") diskCachePolicy(CachePolicy.DISABLED) }
                .listener(onSuccess = { _, _ -> placeholder.visibility = View.GONE },
                    onError = { _, _ -> placeholder.text = "Artwork unavailable" })
                .target(image).build())
        }
        addView(SportsUi.space(context, 7))
        addView(SportsUi.label(context, item.title, 12f, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, context.sdp(32)))
        addView(SportsUi.label(context, item.subtitle ?: "Open in Stremio", 10f, SportsUi.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        contentDescription = listOfNotNull(item.title, item.subtitle).joinToString(". ")
    }

    private fun section(body: LinearLayout, title: String, note: String? = null) {
        body.addView(SportsUi.space(context, 8))
        body.addView(SportsUi.row(context).apply {
            addView(SportsUi.heading(context, title, 19f), LinearLayout.LayoutParams(0, -2, 1f))
            if (note != null) addView(SportsUi.label(context, note, 10f, SportsUi.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(-1, -2))
        body.addView(SportsUi.space(context, 6))
    }
    private fun empty(message: String): View = SportsUi.label(context, message, 14f, SportsUi.MUTED).apply {
        setPadding(context.sdp(14), context.sdp(18), context.sdp(14), context.sdp(18)); background = SportsUi.shape(context)
    }
    private fun strip(cards: List<View>): View = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipChildren = false; clipToPadding = false
        setPadding(context.sdp(2), context.sdp(2), context.sdp(2), context.sdp(3))
        addView(SportsUi.row(context).apply { gravity = Gravity.TOP; cards.forEach { addView(it) } })
    }
    private fun stripParams(width: Int, height: Int) = LinearLayout.LayoutParams(context.sdp(width), context.sdp(height)).apply { rightMargin = context.sdp(12) }
    private fun scroll(body: View): View = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false; isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false; descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        addView(body, ViewGroup.LayoutParams(-1, -2))
    }
    private fun balanced(items: List<ContentItem>): List<ContentItem> {
        val groups = items.groupBy { it.metadata["sport"] }
        val order = groups.keys.sortedBy { SportCategories.followed.indexOf(it).let { n -> if (n < 0) Int.MAX_VALUE else n } }
        return (0 until (groups.values.maxOfOrNull { it.size } ?: 0)).flatMap { index -> order.mapNotNull { groups[it]?.getOrNull(index) } }
    }
}
