package com.majendra.tvlauncher.sportscentre

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Upstream standings are displayed without invented qualification lines or sample teams. */
class LaddersPage(private val context: Context) {
    fun render(tables: List<LadderTable>, onLeague: (String) -> Unit, onBack: () -> Unit,
        selectedLeague: String = "EPL"): View {
        val league = tables.firstOrNull()?.league ?: selectedLeague
        val root = SportsUi.column(context)
        val header = SportsUi.row(context)
        header.addView(SportsUi.button(context, "‹  Home", "ladders:back", onBack))
        header.addView(SportsUi.heading(context, "Ladders", 26f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = context.sdp(12) })
        val tabs = SportsUi.row(context)
        for (name in listOf("AFL", "EPL", "NBA", "NFL", "Shield")) {
            tabs.addView(SportsUi.button(context, name, "ladder-tab:$name") { onLeague(name) }.apply {
                if (name.equals(league, true)) {
                    setTextColor(SportsUi.BACKGROUND); background = SportsUi.shape(context, SportsUi.TEXT, 22)
                    setOnFocusChangeListener { v, f -> v.background = SportsUi.shape(context, SportsUi.TEXT, 22, if (f) SportsUi.LIME else null) }
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.sdp(3) })
        }
        header.addView(HorizontalScrollView(context).apply { isFocusable = false; isHorizontalScrollBarEnabled = false; addView(tabs) })
        root.addView(header, LinearLayout.LayoutParams(-1, context.sdp(44)))
        root.addView(SportsUi.space(context, 12))
        val body = SportsUi.row(context).apply { gravity = Gravity.TOP }
        val main = SportsUi.column(context)
        val primary = tables.firstOrNull()
        if (primary == null) {
            main.addView(SportsUi.card(context, "ladder:empty").apply {
                addView(SportsUi.heading(context, "$league standings", 20f))
                addView(SportsUi.space(context, 12))
                addView(SportsUi.label(context, "Standings unavailable. Try another sport or refresh later.", 14f, SportsUi.MUTED))
            })
        } else main.addView(table(primary, compact = false), LinearLayout.LayoutParams(-1, -2))
        body.addView(scroll(main, "ladders:main-scroll"), LinearLayout.LayoutParams(0, -1, 3.2f).apply { marginEnd = context.sdp(16) })
        val aside = SportsUi.column(context)
        aside.addView(SportsUi.card(context, "ladder:key").apply {
            addView(SportsUi.heading(context, "Table key", 18f)); addView(SportsUi.space(context, 10))
            val explanations = linkedMapOf("P" to "Played", "GP" to "Games played", "W" to "Wins", "D" to "Draws", "L" to "Losses",
                "GD" to "Goal difference", "Pts" to "Points", "PCT" to "Win percentage", "GB" to "Games behind", "%" to "Percentage")
            val columns = primary?.columns.orEmpty()
            val relevant = explanations.filterKeys { key -> columns.any { it.equals(key, true) } }
            if (relevant.isEmpty()) addView(SportsUi.label(context, "Columns use the source's standings labels.", 12f, SportsUi.MUTED))
            relevant.forEach { (abbr, text) -> addView(SportsUi.label(context, "$abbr  $text", 12f, SportsUi.MUTED).apply { setPadding(0, 0, 0, context.sdp(6)) }) }
            if (primary?.rows?.any { it.form.isNotEmpty() } == true) addView(SportsUi.label(context, "Form: W win · D draw · L loss", 11f, SportsUi.MUTED))
        })
        aside.addView(SportsUi.space(context, 12))
        tables.drop(1).forEach { group ->
            aside.addView(table(group, compact = true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.sdp(12) })
        }
        primary?.let { t ->
            aside.addView(SportsUi.label(context, if (t.rows.isEmpty()) "Source unavailable" else "Source: ${t.source}", 11f, SportsUi.MUTED))
            if (t.updatedAt > 0) aside.addView(SportsUi.label(context, "Updated ${DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.of("Australia/Melbourne")).format(Instant.ofEpochMilli(t.updatedAt))}", 10f, SportsUi.MUTED))
        }
        body.addView(scroll(aside, "ladders:side-scroll"), LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun table(data: LadderTable, compact: Boolean): View = SportsUi.card(context, "ladder:${data.league}:${data.title}").apply {
        addView(SportsUi.heading(context, data.title, if (compact) 17f else 20f))
        data.note?.takeIf { it.isNotBlank() }?.let { addView(SportsUi.label(context, it, 11f, SportsUi.MUTED).apply { setPadding(0, context.sdp(6), 0, context.sdp(4)) }) }
        addView(SportsUi.space(context, 9))
        if (data.rows.isEmpty()) addView(SportsUi.label(context, "No standings available", 13f, SportsUi.MUTED))
        else if (compact) {
            data.rows.forEachIndexed { index, row ->
                val line = focusRow("ladder:${data.league}:${data.title}:${row.id}")
                line.addView(SportsUi.label(context, "${index + 1}", 11f, SportsUi.MUTED), LinearLayout.LayoutParams(context.sdp(18), -2))
                line.addView(SportsUi.label(context, row.name, 12f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f))
                val record = record(data, row)
                line.addView(SportsUi.label(context, record, 11f, SportsUi.MUTED).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.sdp(5) })
                line.contentDescription = "${data.title}, ${index + 1}, ${row.name}, $record"
                addView(line, LinearLayout.LayoutParams(-1, context.sdp(26)))
            }
        } else {
            val form = data.rows.any { it.form.isNotEmpty() }
            val header = SportsUi.row(context)
            header.addView(cell("#", SportsUi.MUTED), LinearLayout.LayoutParams(context.sdp(20), -2))
            header.addView(SportsUi.label(context, "Club", 11f, SportsUi.MUTED), LinearLayout.LayoutParams(context.sdp(if (form) 120 else 145), -2))
            data.columns.forEach { header.addView(cell(it, SportsUi.MUTED), LinearLayout.LayoutParams(0, -2, 1f)) }
            if (form) header.addView(cell("Last ${data.rows.maxOfOrNull { it.form.size } ?: 5}", SportsUi.MUTED), LinearLayout.LayoutParams(context.sdp(84), -2))
            addView(header, LinearLayout.LayoutParams(-1, context.sdp(24)))
            data.rows.forEachIndexed { index, row ->
                val line = focusRow("ladder:${data.league}:${data.title}:${row.id}")
                line.addView(cell("${index + 1}"), LinearLayout.LayoutParams(context.sdp(20), -1))
                line.addView(SportsUi.label(context, row.name, 12f).apply { gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(context.sdp(if (form) 120 else 145), -1))
                data.columns.indices.forEach { column ->
                    line.addView(cell(row.values.getOrNull(column) ?: "—"), LinearLayout.LayoutParams(0, -1, 1f))
                }
                if (form) line.addView(form(row.form), LinearLayout.LayoutParams(context.sdp(84), -1))
                line.contentDescription = "${index + 1}, ${row.name}. " + data.columns.mapIndexed { i, label -> "$label ${row.values.getOrNull(i) ?: "unavailable"}" }.joinToString(", ")
                addView(line, LinearLayout.LayoutParams(-1, context.sdp(21)))
            }
        }
    }

    private fun focusRow(tag: String) = SportsUi.row(context).apply {
        id = View.generateViewId(); this.tag = tag; isFocusable = true
        setPadding(context.sdp(2), 0, context.sdp(2), 0)
        setOnFocusChangeListener { v, f -> v.background = if (f) SportsUi.shape(context, SportsUi.RAISED, 5, SportsUi.LIME) else null }
    }
    private fun cell(text: String, color: Int = SportsUi.TEXT) = SportsUi.label(context, text, 11f, color).apply {
        gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private fun form(values: List<String>): View = SportsUi.row(context).apply {
        gravity = Gravity.CENTER
        values.takeLast(5).forEach { value ->
            addView(SportsUi.label(context, value.take(1), 9f, SportsUi.TEXT, true).apply {
                gravity = Gravity.CENTER; background = SportsUi.shape(context, SportsUi.RAISED, 3, if (value.equals("L", true)) SportsUi.LIVE else null)
            }, LinearLayout.LayoutParams(context.sdp(14), context.sdp(16)).apply { marginEnd = context.sdp(2) })
        }
    }
    private fun record(table: LadderTable, row: LadderRow): String {
        val w = table.columns.indexOfFirst { it.equals("W", true) }
        val l = table.columns.indexOfFirst { it.equals("L", true) }
        return if (w >= 0 && l >= 0) "${row.values.getOrNull(w) ?: "—"}–${row.values.getOrNull(l) ?: "—"}"
        else row.values.takeLast(1).joinToString()
    }
    private fun scroll(child: View, focusTag: String) = ScrollView(context).apply {
        id = View.generateViewId(); tag = focusTag; isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        isFillViewport = true; isVerticalScrollBarEnabled = false; addView(child, ViewGroup.LayoutParams(-1, -2))
    }
}
