package com.majendra.tvlauncher.kids

import android.content.Context
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.FeedResult
import com.majendra.tvlauncher.data.FeedStatus
import com.majendra.tvlauncher.ui.KidsTheme
import com.majendra.tvlauncher.ui.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Movie or show night: pick the kind, then Player One and Player Two each choose one, then a fair draw.
 * Session-only nominees. No children's profiles or viewing history are persisted.
 */
class KidsMoviePicker(
    context: Context,
    private val scope: CoroutineScope,
    private val onBrowse: (KidsPickKind) -> Unit,
    private val onPlayWinner: (ContentItem) -> Unit
) : LinearLayout(context) {
    var onLeft: (() -> Unit)? = null
    /** Null until the children pick Movie or Show. */
    var kind: KidsPickKind? = null
        private set
    private val nominees = arrayOfNulls<ContentItem>(2)
    private val names = listOf("Player One", "Player Two")
    private var choosing: Int? = null
    private var drawJob: Job? = null
    private var generation = 0L
    private var drawDialog: Dialog? = null
    private var reel: KidsMovieReel? = null
    private val random = SecureRandom()
    private val heading = KidsTheme.label(context, 17f).apply { maxLines = 1 }
    private val hint = KidsTheme.label(context, 13f, false)
    private val kindButtons = KidsPickKind.entries.map { option ->
        action("${option.emoji}  ${option.noun.replaceFirstChar(Char::uppercase)}", KidsTheme.yellow, 20f) { start(option) }
    }
    private val childButtons = names.mapIndexed { index, _ ->
        action("", if (index == 0) KidsTheme.playerOne else KidsTheme.playerTwo) {
            val current = kind ?: return@action
            cancelPendingDraw()
            choosing = index
            updateLabels()
            onBrowse(current)
        }.apply {
            val bear = KidsTheme.mascot(context, if (index == 0) KidsTheme.playerOne else KidsTheme.playerTwo)
                .apply { setBounds(0, 0, context.dp(30), context.dp(30)) }
            setCompoundDrawables(bear, null, null, null)
            compoundDrawablePadding = context.dp(4)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
    }
    private val draw = action("🎲  Pick for us!", KidsTheme.yellow) { drawWinner() }
    private val back = action("↺") { reset() }.apply { contentDescription = "Start again" }
    private val pickingViews = childButtons + draw + back

    val isChoosing: Boolean get() = choosing != null

    init {
        orientation = VERTICAL
        background = KidsTheme.shape(context)
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(10))
        // Heading and hint share one line so the picker leaves room for the posters below it.
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(heading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            hint.maxLines = 1
            hint.ellipsize = TextUtils.TruncateAt.END
            hint.gravity = Gravity.END
            addView(hint, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = context.dp(12) })
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            clipChildren = false
            // Two-line child buttons otherwise drop to the one-line buttons' baseline and get clipped.
            isBaselineAligned = false
            kindButtons.forEachIndexed { index, button ->
                addView(button, LayoutParams(0, context.dp(52), 1f).apply { if (index == 0) marginEnd = context.dp(8) })
            }
            pickingViews.forEach { button ->
                val weight = when (button) { in childButtons -> 1.4f; back -> 0.35f; else -> 1f }
                addView(button, LayoutParams(0, context.dp(52), weight).apply { if (button != back) marginEnd = context.dp(8) })
            }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(6)
        })
        (kindButtons.take(1) + childButtons.take(1)).forEach { first ->
            first.setOnKeyListener { _, code, event ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && onLeft != null) {
                    if (event.action == KeyEvent.ACTION_DOWN) onLeft?.invoke()
                    true
                } else false
            }
        }
        updateLabels()
    }

    fun update(feed: FeedResult) {
        // Initial loading and progressive partials must not erase a valid nomination.
        if (feed.status !in setOf(FeedStatus.FRESH, FeedStatus.CACHED, FeedStatus.NO_CONTENT)) return
        val current = kind ?: return updateLabels()
        val eligible = feed.rows.flatMap { it.items }.filter { KidsPickPolicy.eligible(it, current) }
            .associateBy { it.metadata["kidsGroupId"] ?: it.id }
        var changed = false
        nominees.indices.forEach { index ->
            val previous = nominees[index]
            if (previous != null) {
                nominees[index] = eligible[previous.metadata["kidsGroupId"] ?: previous.id]
                changed = changed || nominees[index] == null
            }
        }
        if (changed) cancelPendingDraw()
        updateLabels()
    }

    /** Returns true only when an eligible card was captured for the current child. */
    fun choose(item: ContentItem): Boolean {
        val child = choosing ?: return false
        val current = kind ?: return false
        if (!KidsPickPolicy.eligible(item, current)) return false
        cancelPendingDraw()
        nominees[child] = item
        choosing = if (nominees[1 - child] == null) 1 - child else null
        updateLabels()
        if (choosing == null) draw.requestFocus()
        return true
    }

    fun cancelPendingDraw() {
        generation++
        drawJob?.cancel()
        drawJob = null
        reel?.stop(); reel = null
        val dialog = drawDialog
        drawDialog = null
        dialog?.dismiss()
        updateLabels()
    }

    /** Movie or Show chosen: Player One goes first and the rows jump to the first choice. */
    private fun start(option: KidsPickKind) {
        cancelPendingDraw()
        nominees.fill(null)
        kind = option
        choosing = 0
        handOverFocus(childButtons.first())
        updateLabels()
        onBrowse(option)
    }

    private fun reset() {
        cancelPendingDraw()
        nominees.fill(null)
        kind = null
        choosing = null
        handOverFocus(kindButtons.first())
        updateLabels()
    }

    /** Hiding a focused button lets Android jump focus to the sidebar (opening another section), so move it first. */
    private fun handOverFocus(target: TextView) {
        target.visibility = View.VISIBLE
        target.requestFocus()
    }

    private fun drawWinner() {
        val current = kind ?: return
        if (drawJob?.isActive == true || drawDialog != null) return
        val winnerIndex = random.nextInt(2)
        val winner = KidsPickPolicy.winner(nominees[0], nominees[1], winnerIndex, current) ?: return
        choosing = null
        val expected = ++generation
        // Keep the originating control enabled: disabling a focused TV control
        // moves focus into the sidebar before the dialog can take the window.
        // The modal window and guard above prevent a duplicate draw.
        hint.text = "Rolling your two ${current.noun} choices…"
        val animation = KidsMovieReel(context, listOf(nominees[0]!!, nominees[1]!!), winnerIndex, current.noun)
        val dialog = Dialog(context).apply { requestWindowFeature(Window.FEATURE_NO_TITLE) }
        val body = LinearLayout(context).apply {
            orientation = VERTICAL
            background = KidsTheme.shape(context, KidsTheme.background)
            setPadding(context.dp(20), context.dp(15), context.dp(20), context.dp(16))
            addView(KidsTheme.label(context, 25f).apply {
                text = "${current.emoji}  ${current.night}!"; gravity = Gravity.CENTER
            })
            addView(KidsTheme.label(context, 14f).apply {
                text = "Two choices. Equal chances. One ${current.noun} together."; gravity = Gravity.CENTER
            }, LayoutParams(-1, -2).apply { bottomMargin = context.dp(8) })
            addView(animation, LayoutParams(-1, context.dp(285)))
            addView(KidsTheme.label(context, 13f, false).apply {
                text = "Press Back to cancel"; gravity = Gravity.CENTER
            })
        }
        dialog.setContentView(body)
        dialog.setCancelable(true)
        dialog.setOnCancelListener { cancelPendingDraw() }
        dialog.setOnDismissListener { if (drawDialog === dialog) cancelPendingDraw() }
        drawDialog = dialog; reel = animation
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(context.dp(680), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.8f)
        }
        animation.start()
        drawJob = scope.launch {
            delay(4300)
            if (expected != generation || !isAttachedToWindow) return@launch
            drawJob = null
            reel?.stop(); reel = null
            drawDialog = null; dialog.dismiss()
            updateLabels()
            onPlayWinner(winner.copy(metadata = winner.metadata + ("autoPlay" to "true")))
        }
    }

    private fun updateLabels() {
        val current = kind
        kindButtons.forEach { it.visibility = if (current == null) View.VISIBLE else View.GONE }
        pickingViews.forEach { it.visibility = if (current == null) View.GONE else View.VISIBLE }
        heading.text = current?.let { "${it.emoji}  ${it.night}" } ?: "What are we watching?"
        if (current == null) {
            hint.text = "Choose a movie or a show"
            return
        }
        childButtons.forEachIndexed { index, button ->
            button.text = "${names[index]}\n${nominees[index]?.title ?: if (choosing == index) "Picking…" else "Choose a ${current.noun}"}"
            button.contentDescription = button.text
        }
        draw.isEnabled = nominees.all { it?.let { item -> KidsPickPolicy.eligible(item, current) } == true }
        draw.alpha = if (draw.isEnabled) 1f else 0.6f
        if (drawJob?.isActive != true) {
            val waiting = names.filterIndexed { index, _ -> nominees[index] == null }
            hint.text = choosing?.let { "${names[it]}, pick your ${current.noun}!" }
                ?: when (waiting.size) {
                    0 -> "Two great choices! Press the dice."
                    1 -> "${waiting.single()} still to pick"
                    else -> "One pick each, then a surprise!"
                }
        }
    }

    private fun action(label: String, color: Int = KidsTheme.softBlue, size: Float = 13f, clicked: () -> Unit): TextView =
        KidsTheme.label(context, size).apply {
            text = label
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(context.dp(1).toFloat(), 1f)
            // Bottom padding clears the 4dp shadow layer in KidsTheme.shape.
            setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(8))
            isFocusable = true
            isClickable = true
            background = KidsTheme.shape(context, color)
            setOnFocusChangeListener { view, focused -> KidsTheme.focus(view, focused, color) }
            setOnClickListener { clicked() }
        }

    override fun onDetachedFromWindow() {
        cancelPendingDraw()
        super.onDetachedFromWindow()
    }
}
