package com.majendra.tvlauncher.sportscentre

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.majendra.tvlauncher.data.ContentItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A match page renders the supplied snapshot only; its owner handles refreshes and navigation. */
class MatchPage(private val context: Context) {
    fun render(detail: MatchDetail, otherGames: List<ContentItem>, onMatch: (ContentItem) -> Unit,
        onOpen: (ContentItem) -> Unit, onBack: () -> Unit): View {
        val item = detail.item
        val m = item.metadata
        val root = SportsUi.column(context)
        val top = SportsUi.row(context)
        top.addView(SportsUi.button(context, "‹  Home", "match:back", onBack))
        top.addView(SportsUi.label(context, listOfNotNull(m["league"], detail.headline ?: m["footer"]?.takeIf { m["watch"] != "true" })
            .joinToString(" · ").uppercase(), 12f, SportsUi.MUTED, condensed = true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = context.sdp(12) })
        root.addView(top, LinearLayout.LayoutParams(-1, context.sdp(38)))
        root.addView(SportsUi.space(context, 10))
        val columns = SportsUi.row(context).apply { gravity = Gravity.TOP }
        val main = SportsUi.column(context)
        main.addView(scoreHero(item), LinearLayout.LayoutParams(-1, context.sdp(144)))
        main.addView(SportsUi.space(context, 14))
        val lower = SportsUi.row(context).apply { gravity = Gravity.TOP }
        lower.addView(periodCard(detail), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.sdp(12) })
        lower.addView(statsCard(detail), LinearLayout.LayoutParams(0, -2, 1f))
        main.addView(lower)
        detail.note?.takeIf { it.isNotBlank() }?.let {
            main.addView(SportsUi.space(context, 10))
            main.addView(SportsUi.label(context, it, 12f, SportsUi.MUTED))
        }
        if (detail.updatedAt > 0) main.addView(SportsUi.label(context,
            "Updated ${DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.of("Australia/Melbourne")).format(Instant.ofEpochMilli(detail.updatedAt))}",
            10f, SportsUi.MUTED).apply { setPadding(0, context.sdp(8), 0, 0) })
        columns.addView(scroll(main, "match:main-scroll"), LinearLayout.LayoutParams(0, -1, 2.35f).apply { marginEnd = context.sdp(18) })

        val side = SportsUi.column(context)
        val watch = when {
            m["playbackPackage"] == "com.tcl.tv" -> "▶  Watch on TV"
            m["watch"] == "true" -> "▶  Watch on Kayo"
            else -> "▶  Open Kayo"
        }
        side.addView(SportsUi.button(context, watch, "match:watch:" + SportsIdentity.key(item)) { onOpen(item) }.apply {
            setTextColor(SportsUi.BACKGROUND); textSize = 20f; typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
            background = SportsUi.shape(context, SportsUi.LIME)
            minHeight = context.sdp(58)
            setOnFocusChangeListener { v, focused -> v.background = SportsUi.shape(context, SportsUi.LIME, stroke = if (focused) SportsUi.TEXT else null) }
        }, LinearLayout.LayoutParams(-1, -2))
        side.addView(SportsUi.label(context, if (m["playbackPackage"] == "com.tcl.tv") "Opens the TV channel" else
            if (m["watch"] == "true") "${m["footer"]?.replace("📺", "")?.trim().orEmpty()} · opens the Kayo app" else "Opens the Kayo app. A matching broadcast was not found in the guide.",
            11f, SportsUi.MUTED).apply { setPadding(context.sdp(6), context.sdp(7), context.sdp(6), 0) })
        side.addView(SportsUi.space(context, 14))
        side.addView(SportsUi.card(context, "match:odds").apply {
            addView(SportsUi.label(context, "Odds unavailable", 12f, SportsUi.MUTED))
        })
        side.addView(SportsUi.space(context, 14))
        side.addView(SportsUi.heading(context, "Other live games", 18f))
        side.addView(SportsUi.space(context, 7))
        val others = otherGames.filter { it.metadata["scoreState"] == "live" && identity(it) != identity(item) }.distinctBy(::identity).take(6)
        if (others.isEmpty()) side.addView(SportsUi.label(context, "No other live scores right now.", 12f, SportsUi.MUTED))
        others.forEach { game ->
            side.addView(compactGame(game) { onMatch(game) }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.sdp(9) })
        }
        columns.addView(scroll(side, "match:side-scroll"), LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun scoreHero(item: ContentItem): View = SportsUi.card(context, "match:score").apply {
        val m = item.metadata
        val away = m["awayName"] ?: item.title
        val home = m["homeName"].orEmpty()
        val awayScore = m["awayScore"] ?: "—"
        val homeScore = m["homeScore"] ?: "—"
        val board = SportsUi.row(context).apply { gravity = Gravity.CENTER; minimumHeight = context.sdp(86) }
        board.addView(team(away, m["awayAbbr"]), LinearLayout.LayoutParams(0, -2, 1f))
        board.addView(SportsUi.label(context, "$awayScore – $homeScore", if ((awayScore + homeScore).length > 8) 34f else 52f,
            SportsUi.TEXT, true, true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1.7f))
        board.addView(team(home, m["homeAbbr"]), LinearLayout.LayoutParams(0, -2, 1f))
        addView(board, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(SportsUi.label(context, listOfNotNull(m["league"], m["clock"] ?: item.subtitle).joinToString(" · "), 15f,
            if (m["scoreState"] == "live") SportsUi.LIVE else SportsUi.MUTED, true, true).apply { gravity = Gravity.CENTER })
    }

    private fun team(name: String, abbreviation: String?): View = SportsUi.column(context).apply {
        gravity = Gravity.CENTER
        abbreviation?.takeIf { it.isNotBlank() }?.let { addView(SportsUi.label(context, it, 12f, SportsUi.MUTED, true).apply { gravity = Gravity.CENTER }) }
        addView(SportsUi.label(context, name, 19f, SportsUi.TEXT, true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
    }

    private fun periodCard(detail: MatchDetail): View = SportsUi.card(context, "match:periods").apply {
        addView(SportsUi.heading(context, "Scoring by period", 18f)); addView(SportsUi.space(context, 12))
        if (detail.periods.isEmpty()) addView(SportsUi.label(context, "Period scores unavailable", 12f, SportsUi.MUTED))
        else {
            val periods = detail.periods.take(10)
            val header = SportsUi.row(context)
            header.addView(SportsUi.label(context, "", 11f), LinearLayout.LayoutParams(context.sdp(72), -2))
            periods.forEach { header.addView(cell(it.label, 10f, SportsUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f)) }
            addView(header)
            for (away in listOf(true, false)) {
                val row = SportsUi.row(context)
                val key = if (away) "away" else "home"
                row.addView(SportsUi.label(context, detail.item.metadata["${key}Abbr"] ?: detail.item.metadata["${key}Name"].orEmpty(), 13f, bold = true).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(context.sdp(72), context.sdp(28)))
                periods.forEach { row.addView(cell(if (away) it.away else it.home, 13f), LinearLayout.LayoutParams(0, context.sdp(28), 1f)) }
                addView(row)
            }
        }
        detail.drive?.takeIf { it.isNotBlank() }?.let { drive ->
            addView(SportsUi.space(context, 16))
            addView(SportsUi.label(context, "Current drive", 11f, SportsUi.MUTED))
            addView(SportsUi.label(context, drive, 14f).apply { setPadding(0, context.sdp(5), 0, 0) })
        }
    }

    private fun statsCard(detail: MatchDetail): View = SportsUi.card(context, "match:stats").apply {
        addView(SportsUi.heading(context, "Team stats", 18f)); addView(SportsUi.space(context, 10))
        if (detail.stats.isEmpty()) addView(SportsUi.label(context, "Team statistics unavailable", 12f, SportsUi.MUTED))
        detail.stats.take(12).forEach { stat ->
            val row = SportsUi.row(context)
            row.addView(cell(stat.away, 15f), LinearLayout.LayoutParams(context.sdp(48), -2))
            row.addView(cell(stat.label, 11f, SportsUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(cell(stat.home, 15f), LinearLayout.LayoutParams(context.sdp(48), -2))
            addView(row)
            val a = stat.away.removeSuffix("%").toDoubleOrNull()
            val h = stat.home.removeSuffix("%").toDoubleOrNull()
            if (a != null && h != null && a.isFinite() && h.isFinite() && a >= 0 && h >= 0 && a + h > 0) {
                addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 1000; progress = (a / (a + h) * 1000).toInt()
                    progressTintList = android.content.res.ColorStateList.valueOf(SportsUi.TEXT)
                    progressBackgroundTintList = android.content.res.ColorStateList.valueOf(SportsUi.RULE)
                }, LinearLayout.LayoutParams(-1, context.sdp(4)).apply { topMargin = context.sdp(5) })
            }
            addView(SportsUi.space(context, 12))
        }
    }

    private fun compactGame(item: ContentItem, action: () -> Unit): View = SportsUi.card(context, "match:other:${identity(item)}", action).apply {
        val m = item.metadata
        addView(SportsUi.label(context, listOfNotNull(m["league"], m["clock"]).joinToString(" · "), 11f, SportsUi.LIVE))
        for (key in listOf("away", "home")) {
            val row = SportsUi.row(context)
            row.addView(SportsUi.label(context, m["${key}Name"] ?: item.title, 15f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(SportsUi.label(context, m["${key}Score"] ?: "—", 17f, bold = true))
            addView(row, LinearLayout.LayoutParams(-1, context.sdp(28)))
        }
    }

    private fun cell(value: String, size: Float, color: Int = SportsUi.TEXT): TextView = SportsUi.label(context, value, size, color).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private fun scroll(child: View, focusTag: String) = ScrollView(context).apply {
        id = View.generateViewId(); tag = focusTag; isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        isFillViewport = true; isVerticalScrollBarEnabled = false; addView(child, ViewGroup.LayoutParams(-1, -2))
    }
    private fun identity(item: ContentItem) = SportsIdentity.key(item)
}
