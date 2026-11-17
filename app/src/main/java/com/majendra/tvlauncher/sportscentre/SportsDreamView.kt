package com.majendra.tvlauncher.sportscentre

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.majendra.tvlauncher.data.ContentItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

/** Text-only ambient display: no video, logo downloads or independent network activity. */
class SportsDreamView(context: Context) : View(context) {
    private val zone = ZoneId.of("Australia/Melbourne")
    private val clock = DateTimeFormatter.ofPattern("h:mm")
    private val calendar = DateTimeFormatter.ofPattern("EEEE d MMMM · 'Melbourne'")
    private val startTime = DateTimeFormatter.ofPattern("EEE h:mm a")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var snapshot: SportsSnapshot? = null
    private var now = System.currentTimeMillis()
    private val white = Color.rgb(218, 225, 229)
    private val grey = Color.rgb(131, 144, 154)

    init { setBackgroundColor(Color.BLACK); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun bind(value: SportsSnapshot) { snapshot = value; tick() }
    fun tick() { now = System.currentTimeMillis(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = min(width / 1920f, height / 1080f)
        if (scale <= 0) return
        canvas.save()
        canvas.translate((width - 1920 * scale) / 2, (height - 1080 * scale) / 2)
        canvas.scale(scale, scale)
        // Small periodic movement and hero rotation reduce static-pixel exposure.
        val drift = ((now / 60_000L) % 5).toInt() - 2
        canvas.translate(drift * 4f, -drift * 3f)
        val local = Instant.ofEpochMilli(now).atZone(zone)
        text(canvas, local.format(clock), 155f, 260f, 180f, white, condensed = true)
        text(canvas, local.format(calendar), 160f, 325f, 30f, grey)
        val games = SportsDreamPolicy.games(snapshot?.games.orEmpty())
        val live = games.filter { it.metadata["scoreState"] == "live" }
        val saved = snapshot?.let { now - it.updatedAt > 120_000L || it.feed.message.contains("Saved scores", true) } == true
        text(canvas, if (saved) "Saved sports update" else if (live.isEmpty()) "Sports Centre" else "${live.size} ${if (live.size == 1) "game" else "games"} live", 1760f, 130f, 25f, grey, align = Paint.Align.RIGHT)
        games.firstOrNull { it.metadata["scoreState"] == "upcoming" }?.let {
            text(canvas, "Next: ${shortName(it)} · ${it.startTime?.let { value -> Instant.ofEpochMilli(value).atZone(zone).format(startTime) }.orEmpty()}",
                1760f, 170f, 23f, grey, maxWidth = 900f, align = Paint.Align.RIGHT)
        }
        val heroes = live.ifEmpty { games.take(4) }
        val hero = heroes.getOrNull(((now / 30_000L) % heroes.size.coerceAtLeast(1)).toInt())
        if (hero == null) {
            text(canvas, "SPORTS CENTRE", 160f, 520f, 29f, grey, true)
            text(canvas, "No scores available right now", 160f, 690f, 78f, white, true, maxWidth = 1600f)
            contentDescription = "${local.format(clock)}. No scores available right now."
        } else {
            val state = hero.metadata["scoreState"].orEmpty()
            val caption = listOfNotNull(if (saved) "SAVED" else when (state) { "live" -> "LIVE"; "final" -> "FINAL"; else -> "UPCOMING" },
                hero.metadata["league"], hero.metadata["clock"]).joinToString(" · ")
            text(canvas, caption, 160f, 495f, 29f, if (state == "live" && !saved) SportsUi.LIVE else grey, true)
            val away = hero.metadata["awayName"]
            val home = hero.metadata["homeName"]
            val score = if (away != null && home != null) "$away ${hero.metadata["awayScore"] ?: "—"} – ${hero.metadata["homeScore"] ?: "—"} $home" else hero.title
            text(canvas, score, 160f, 695f, 112f, white, true, maxWidth = 1600f)
            if (saved) text(canvas, "Saved update · refresh unavailable", 160f, 755f, 23f, grey)
            contentDescription = "${local.format(clock)}. $caption. $score"
        }
        paint.color = Color.rgb(30, 37, 42); paint.strokeWidth = 1f
        canvas.drawLine(160f, 860f, 1760f, 860f, paint)
        games.filter { it.id != hero?.id }.take(4).forEachIndexed { index, item ->
            val x = 160f + index * 410f
            text(canvas, item.metadata["league"].orEmpty(), x, 912f, 21f, grey)
            text(canvas, shortName(item), x, 956f, 28f, white, maxWidth = 370f)
            text(canvas, item.metadata["clock"].orEmpty(), x, 994f, 21f, grey, maxWidth = 370f)
        }
        canvas.restore()
    }

    private fun shortName(item: ContentItem): String {
        val away = item.metadata["awayName"] ?: return item.title
        val home = item.metadata["homeName"] ?: return item.title
        return if (item.metadata["scoreState"] == "upcoming") "$away v $home"
        else "$away ${item.metadata["awayScore"] ?: "—"} · $home ${item.metadata["homeScore"] ?: "—"}"
    }

    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float, color: Int,
        condensed: Boolean = false, maxWidth: Float = Float.MAX_VALUE, align: Paint.Align = Paint.Align.LEFT) {
        paint.color = color; paint.textAlign = align; paint.textSize = size
        paint.typeface = Typeface.create(if (condensed) "sans-serif-condensed" else "sans-serif", if (condensed) Typeface.BOLD else Typeface.NORMAL)
        if (paint.measureText(value) > maxWidth) paint.textSize = (size * maxWidth / paint.measureText(value)).coerceAtLeast(16f)
        canvas.drawText(value, x, y, paint)
    }
}
