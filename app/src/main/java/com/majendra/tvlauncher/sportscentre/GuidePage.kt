package com.majendra.tvlauncher.sportscentre

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.majendra.tvlauncher.providers.Guide
import com.majendra.tvlauncher.providers.GuideProgram
import com.majendra.tvlauncher.providers.KayoGuide
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max

/** A programme timeline, not a promise that an individual channel can be deep-linked. */
class GuidePage(private val context: Context) {
    private val zone = ZoneId.of("Australia/Melbourne")
    private val time = DateTimeFormatter.ofPattern("h:mm a")
    private val date = DateTimeFormatter.ofPattern("EEE d MMM")
    private fun stamp(value: Long) = Instant.ofEpochMilli(value).atZone(zone).format(time)

    fun render(guide: Guide?, updatedAt: Long, onOpen: () -> Unit, onBack: () -> Unit): View {
        val now = System.currentTimeMillis()
        val root = SportsUi.column(context)
        val title = SportsUi.row(context)
        title.addView(SportsUi.button(context, "‹ Home", "guide:home", onBack))
        title.addView(SportsUi.heading(context, "Kayo guide", 23f), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = context.sdp(14) })
        title.addView(SportsUi.label(context, Instant.ofEpochMilli(now).atZone(zone).format(date), 12f, SportsUi.MUTED))
        root.addView(title)
        root.addView(SportsUi.space(context, 10))

        val hero = SportsUi.card(context, "guide:selected")
        val overline = SportsUi.label(context, "Select a programme", 12f, SportsUi.MUTED)
        val programmeTitle = SportsUi.heading(context, "Kayo programme guide", 23f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val programmeInfo = SportsUi.label(context, "Programme listings open the Kayo app.", 12f, SportsUi.MUTED).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val heroText = SportsUi.column(context).apply {
            addView(overline); addView(SportsUi.space(context, 5)); addView(programmeTitle)
            addView(SportsUi.space(context, 5)); addView(programmeInfo)
        }
        val heroRow = SportsUi.row(context)
        heroRow.addView(heroText, LinearLayout.LayoutParams(0, -2, 1f))
        heroRow.addView(SportsUi.button(context, "▶  Open in Kayo", "guide:open", onOpen).apply {
            setTextColor(SportsUi.BACKGROUND); background = SportsUi.shape(context, SportsUi.LIME, 14)
            setOnFocusChangeListener { view, focus -> view.background = SportsUi.shape(context, SportsUi.LIME, 14, if (focus) SportsUi.TEXT else null) }
        }, LinearLayout.LayoutParams(context.sdp(160), context.sdp(45)).apply { leftMargin = context.sdp(18) })
        hero.addView(heroRow)
        root.addView(hero)
        root.addView(SportsUi.space(context, 8))
        val age = if (updatedAt > 0) "Guide updated ${Instant.ofEpochMilli(updatedAt).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM h:mm a"))} · Open-EPG" else "Guide update time unavailable"
        root.addView(SportsUi.label(context, age + if (updatedAt > 0 && now - updatedAt > KayoGuide.CACHE_MILLIS) " · saved guide" else "", 11f, SportsUi.MUTED))
        root.addView(SportsUi.space(context, 8))
        if (guide == null || guide.programs.isEmpty()) {
            root.addView(SportsUi.label(context, "Programme guide unavailable. Open Kayo to see its current listings.", 16f, SportsUi.MUTED))
            return root
        }

        val halfHour = 30 * 60_000L
        val windowStart = now / halfHour * halfHour
        val windowEnd = windowStart + 6 * 60 * 60_000L
        val screenDp = context.resources.displayMetrics.widthPixels / context.resources.displayMetrics.density
        val visibleDp = max(420, screenDp.toInt() - 220)
        val timelineWidth = context.sdp(visibleDp * 2)
        val labelWidth = context.sdp(132)
        val rowHeight = context.sdp(50)
        val axisHeight = context.sdp(25)
        fun x(value: Long) = ((value - windowStart).toDouble() / (windowEnd - windowStart) * timelineWidth).toInt()
        val labels = SportsUi.column(context)
        labels.addView(SportsUi.label(context, "Channel", 11f, SportsUi.MUTED), LinearLayout.LayoutParams(labelWidth, axisHeight))
        val timeline = SportsUi.column(context)
        val axis = FrameLayout(context)
        for (i in 0..11) axis.addView(SportsUi.label(context, stamp(windowStart + i * halfHour), 11f, SportsUi.MUTED),
            FrameLayout.LayoutParams(context.sdp(90), axisHeight).apply { leftMargin = x(windowStart + i * halfHour) })
        timeline.addView(axis, LinearLayout.LayoutParams(timelineWidth, axisHeight))
        val focusRows = mutableListOf<List<Pair<GuideProgram, View>>>()
        var initial: Pair<KayoGuide.Channel, GuideProgram>? = null
        KayoGuide.channels.forEach { channel ->
            val channelNumber = guide.channels.firstOrNull { it.id == channel.guideId }?.number?.takeIf { it.isNotBlank() }
                ?: numbers[channel.id].orEmpty()
            val nameRow = SportsUi.row(context)
            nameRow.addView(SportsUi.label(context, channelNumber, 14f, SportsUi.LIME, true, true), LinearLayout.LayoutParams(context.sdp(38), -2))
            nameRow.addView(SportsUi.label(context, channel.name, 11f, SportsUi.MUTED).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
            labels.addView(nameRow, LinearLayout.LayoutParams(labelWidth, rowHeight))
            val line = FrameLayout(context)
            val programmes = guide.programs.filter { it.channel == channel.guideId && it.start != null && it.end != null && it.end > it.start && it.end > windowStart && it.start < windowEnd }
                .sortedBy { it.start }.take(35)
            val focusables = programmes.map { p ->
                val button = SportsUi.column(context).apply {
                    id = View.generateViewId(); tag = "guide:${p.channel}:${p.start}"
                    isFocusable = true; isClickable = true
                    setPadding(context.sdp(8), context.sdp(5), context.sdp(8), context.sdp(4))
                    background = SportsUi.shape(context, if (p.isNow(now)) SportsUi.RAISED else SportsUi.SURFACE, 6)
                    addView(SportsUi.label(context, p.title, 12f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    addView(SportsUi.label(context, "${stamp(p.start!!)} – ${stamp(p.end!!)}", 9f, SportsUi.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                    setOnClickListener { onOpen() }
                    setOnFocusChangeListener { view, focus ->
                        view.background = SportsUi.shape(context, if (focus || p.isNow(now)) SportsUi.RAISED else SportsUi.SURFACE, 6, if (focus) SportsUi.LIME else null)
                        if (focus) {
                            overline.text = "${if (p.isNow(System.currentTimeMillis())) "On now" else "Scheduled"} · $channelNumber ${channel.name} · ${stamp(p.start!!)} – ${stamp(p.end!!)}"
                            programmeTitle.text = p.title
                            programmeInfo.text = description(p)
                        }
                    }
                }
                val startX = x(maxOf(p.start!!, windowStart))
                val endX = x(minOf(p.end!!, windowEnd))
                line.addView(button, FrameLayout.LayoutParams(max(context.sdp(24), endX - startX - context.sdp(3)), rowHeight - context.sdp(5)).apply { leftMargin = startX })
                if (initial == null && p.isNow(now)) initial = channel to p
                p to button
            }
            if (programmes.isEmpty()) line.addView(SportsUi.label(context, "No programme listing", 12f, SportsUi.MUTED), FrameLayout.LayoutParams(-1, rowHeight).apply { gravity = Gravity.CENTER_VERTICAL })
            timeline.addView(line, LinearLayout.LayoutParams(timelineWidth, rowHeight))
            focusRows += focusables
        }
        focusRows.forEachIndexed { rowIndex, row -> row.forEachIndexed { index, pair ->
            val (p, view) = pair
            row.getOrNull(index - 1)?.second?.let { view.nextFocusLeftId = it.id }
            row.getOrNull(index + 1)?.second?.let { view.nextFocusRightId = it.id }
            focusRows.getOrNull(rowIndex - 1)?.minByOrNull { abs((it.first.start ?: 0) - (p.start ?: 0)) }?.second?.let { view.nextFocusUpId = it.id }
            focusRows.getOrNull(rowIndex + 1)?.minByOrNull { abs((it.first.start ?: 0) - (p.start ?: 0)) }?.second?.let { view.nextFocusDownId = it.id }
        } }
        initial?.let { (channel, p) ->
            overline.text = "On now · ${numbers[channel.id].orEmpty()} ${channel.name} · ${stamp(p.start!!)} – ${stamp(p.end!!)}"
            programmeTitle.text = p.title; programmeInfo.text = description(p)
        }
        val overlay = FrameLayout(context)
        overlay.addView(timeline, FrameLayout.LayoutParams(timelineWidth, axisHeight + rowHeight * KayoGuide.channels.size))
        overlay.addView(object : View(context) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SportsUi.LIVE; strokeWidth = context.sdp(1).toFloat() }
            override fun onDraw(canvas: Canvas) {
                val nowX = x(System.currentTimeMillis()).toFloat()
                if (nowX in 0f..width.toFloat()) canvas.drawLine(nowX, axisHeight.toFloat(), nowX, height.toFloat(), paint)
            }
        }, FrameLayout.LayoutParams(timelineWidth, axisHeight + rowHeight * KayoGuide.channels.size))
        val horizontal = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false
            addView(overlay, ViewGroup.LayoutParams(timelineWidth, -2))
        }
        val grid = SportsUi.row(context).apply { gravity = Gravity.TOP; addView(labels); addView(horizontal, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(ScrollView(context).apply { isFillViewport = false; addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    companion object {
        private fun description(programme: GuideProgram) = programme.description?.replace("AustraliaThe ", "Australia. The ")?.takeIf { it.isNotBlank() }
            ?: "Opens the Kayo app. Choose the channel there."
        private val numbers = mapOf("FSN" to "500", "FS1" to "501", "SP2" to "502", "FS3" to "503", "FAF" to "504", "SPS" to "506", "FSS" to "507", "ESP" to "ESPN", "ES2" to "ESPN2")
    }
}
