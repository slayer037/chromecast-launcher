package com.majendra.tvlauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.Disposable
import coil.request.ImageRequest
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.lightweightLauncher

/**
 * Sports Centre's scoreboard cards, drawn from item metadata ("card"): a game (both teams, scores and the clock), an
 * event (a race and its podium), a programme (what's on a sport channel) or a headline. Live cards are tinted red.
 */
class ScoreCardAdapter(
    items: List<ContentItem>, private val onItem: (ContentItem) -> Unit, private val onLeft: () -> Unit,
    private val onFocused: (ContentItem) -> Unit, private val onDetails: (ContentItem) -> Unit
) : CardListAdapter<ScoreCardAdapter.Holder>(items) {
    override fun getItemViewType(position: Int) = CardStyle.SCORE.ordinal

    class Team(val row: LinearLayout, val logo: ImageView, val name: TextView, val score: TextView)
    class Holder(root: LinearLayout, val chip: TextView, val clock: TextView, val home: Team, val away: Team, val art: ImageView,
                 val title: TextView, val lines: TextView, val headline: LinearLayout, val footer: TextView, val progress: ProgressBar) :
        RecyclerView.ViewHolder(root) {
        val requests = mutableListOf<Disposable>()
        var live = false
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val root = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(c.dp(CardStyle.SCORE.widthDp), ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = c.dp(12) }
            setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(8))
            isFocusable = true; isClickable = true; clipToOutline = true
        }
        val header = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val chip = Theme.label(c, 11f, true).apply { letterSpacing = .08f; setTextColor(Theme.muted); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val clock = Theme.label(c, 12f, true).apply { maxLines = 1; gravity = Gravity.END }
        header.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = c.dp(8) })
        header.addView(clock, LinearLayout.LayoutParams(-2, -2))
        root.addView(header, LinearLayout.LayoutParams(-1, c.dp(18)))
        val home = team(c); val away = team(c)
        root.addView(away.row, LinearLayout.LayoutParams(-1, c.dp(36)).apply { topMargin = c.dp(6) })
        root.addView(home.row, LinearLayout.LayoutParams(-1, c.dp(36)))
        // Events, programmes and headlines: an optional picture beside the title, then extra lines.
        val headline = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val art = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val text = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val title = Theme.label(c, 16f, true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        val lines = Theme.label(c, 12f).apply { setTextColor(Theme.muted); maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, c.dp(3), 0, 0) }
        text.addView(title, LinearLayout.LayoutParams(-1, -2)); text.addView(lines, LinearLayout.LayoutParams(-1, -2))
        headline.addView(art, LinearLayout.LayoutParams(c.dp(56), c.dp(56)).apply { marginEnd = c.dp(10) })
        headline.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(headline, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = c.dp(6) })
        val footer = Theme.label(c, 12f).apply { setTextColor(Theme.accent); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        root.addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = c.dp(4) })
        val progress = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; isIndeterminate = false; minimumHeight = 0
            progressDrawable = LayerDrawable(arrayOf(ColorDrawable(Theme.selected), ClipDrawable(ColorDrawable(Theme.accent), Gravity.START, ClipDrawable.HORIZONTAL)))
                .apply { setId(0, android.R.id.background); setId(1, android.R.id.progress) }
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, c.dp(3)).apply { topMargin = c.dp(5) })
        return Holder(root, chip, clock, home, away, art, title, lines, headline, footer, progress)
    }

    private fun team(c: Context): Team {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val name = Theme.label(c, 17f, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val score = Theme.label(c, 22f, true).apply { maxLines = 1; gravity = Gravity.END; ellipsize = TextUtils.TruncateAt.START }
        row.addView(logo, LinearLayout.LayoutParams(c.dp(28), c.dp(28)).apply { marginEnd = c.dp(10) })
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(score, LinearLayout.LayoutParams(-2, -2).apply { marginStart = c.dp(8) })
        return Team(row, logo, name, score)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val m = item.metadata
        val kind = m["card"] ?: "programme"
        val state = m["scoreState"]
        holder.requests.forEach { it.dispose() }; holder.requests.clear()
        holder.live = item.isLive || (kind == "programme" && m["onAir"] == "true")
        style(holder, holder.itemView.hasFocus())
        holder.chip.text = (if (kind == "game" || kind == "event") m["league"] else m["chip"] ?: m["sport"]).orEmpty().uppercase()
        holder.clock.text = when {
            state == "live" -> "●  " + (m["clock"] ?: "LIVE")
            state == "final" -> "FINAL"
            else -> m["clock"].orEmpty()
        }
        holder.clock.setTextColor(when { holder.live -> Theme.live; state == "final" -> Theme.muted; else -> Theme.accent })
        val game = kind == "game"
        holder.home.row.visibility = if (game) View.VISIBLE else View.GONE
        holder.away.row.visibility = if (game) View.VISIBLE else View.GONE
        holder.headline.visibility = if (game) View.GONE else View.VISIBLE
        if (game) {
            val homeWins = m["homeWinner"] == "true"; val awayWins = m["awayWinner"] == "true"
            bindTeam(holder, holder.home, m["homeName"], m["homeLogo"], m["homeScore"], lead(m["homeScore"], m["awayScore"], homeWins, awayWins))
            bindTeam(holder, holder.away, m["awayName"], m["awayLogo"], m["awayScore"], lead(m["awayScore"], m["homeScore"], awayWins, homeWins))
        } else {
            holder.title.text = item.title
            holder.title.maxLines = when (kind) { "headline" -> 3; "event" -> 1; else -> 2 }
            holder.lines.text = when (kind) { "event" -> m["lines"]; "programme" -> item.description?.lineSequence()?.firstOrNull(); else -> null }.orEmpty()
            holder.lines.visibility = if (holder.lines.text.isNullOrBlank()) View.GONE else View.VISIBLE
            val picture = if (kind == "headline") item.imageUrl else item.imageUrl ?: m["fallbackArtwork"]
            holder.art.visibility = if (picture.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.art.scaleType = if (kind == "headline") ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            (holder.art.layoutParams as LinearLayout.LayoutParams).width = holder.itemView.context.dp(if (kind == "headline") 88 else 56)
            load(holder, holder.art, picture)
        }
        holder.footer.text = when (kind) { "headline" -> m["clock"]; "programme" -> m["footer"]; else -> m["footer"] }.orEmpty()
        holder.footer.setTextColor(if (m["watch"] == "true" || kind == "programme") Theme.accent else Theme.muted)
        holder.footer.visibility = if (holder.footer.text.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
        if (kind == "headline") holder.clock.text = ""
        val progress = item.progress?.takeIf { it.isFinite() }
        holder.progress.visibility = if (progress != null) View.VISIBLE else View.INVISIBLE
        holder.progress.progress = ((progress ?: 0f).coerceIn(0f, 1f) * 1000).toInt()
        holder.itemView.contentDescription = listOfNotNull(item.title, item.subtitle, m["footer"]).joinToString(". ")
        holder.itemView.setOnFocusChangeListener { view, focused ->
            style(holder, focused)
            if (focused) onFocused(item)
        }
        if (holder.itemView.hasFocus()) onFocused(item)
        holder.itemView.setOnClickListener { onItem(item) }
        holder.itemView.setOnLongClickListener { onDetails(item); true }
        holder.itemView.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && holder.bindingAdapterPosition == 0) { if (event.action == KeyEvent.ACTION_DOWN) onLeft(); true } else false
        }
    }

    /** The side ahead (or the winner) reads bright; the other is dimmed. Level or not started: both bright. */
    private fun lead(mine: String?, theirs: String?, won: Boolean, lost: Boolean): Boolean {
        if (won || lost) return won
        val a = mine?.trim()?.toDoubleOrNull(); val b = theirs?.trim()?.toDoubleOrNull()
        return a == null || b == null || a >= b
    }

    private fun bindTeam(holder: Holder, team: Team, name: String?, logo: String?, score: String?, ahead: Boolean) {
        team.name.text = name.orEmpty()
        team.score.text = score.orEmpty()
        // Cricket scores are long ("339 & 244/9"); keep them on the line.
        team.score.textSize = if ((score?.length ?: 0) > 5) 14f else 22f
        val colour = if (ahead) Theme.text else Theme.muted
        team.name.setTextColor(colour); team.score.setTextColor(colour)
        team.name.typeface = Typeface.create("sans-serif", if (ahead) Typeface.BOLD else Typeface.NORMAL)
        team.logo.visibility = if (logo.isNullOrBlank()) View.INVISIBLE else View.VISIBLE
        load(holder, team.logo, logo)
    }

    private fun load(holder: Holder, view: ImageView, url: String?) {
        view.setImageDrawable(null)
        if (url.isNullOrBlank() || !url.startsWith("https://")) return
        val size = view.context.dp(88)
        holder.requests += view.context.imageLoader.enqueue(ImageRequest.Builder(view.context).data(url).size(size, size).crossfade(false).target(view).build())
    }

    private fun style(holder: Holder, focused: Boolean) {
        val c = holder.itemView.context
        holder.itemView.background = Theme.shape(c, when { focused -> Theme.selected; holder.live -> LIVE_TINT; else -> Theme.surface }, focused, 12)
        holder.itemView.elevation = c.dp(if (focused && !c.lightweightLauncher) 6 else 0).toFloat()
    }

    override fun onViewRecycled(holder: Holder) { holder.requests.forEach { it.dispose() }; holder.requests.clear(); super.onViewRecycled(holder) }

    private companion object { val LIVE_TINT = Color.rgb(58, 30, 36) }
}
