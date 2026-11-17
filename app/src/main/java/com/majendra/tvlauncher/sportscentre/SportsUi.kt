package com.majendra.tvlauncher.sportscentre

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

fun Context.sdp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

/** Sports-only tokens; other launcher sections keep their existing theme. */
object SportsUi {
    val BACKGROUND = Color.rgb(9, 12, 14)
    val SURFACE = Color.rgb(20, 25, 29)
    val RAISED = Color.rgb(29, 36, 42)
    val TEXT = Color.rgb(239, 242, 244)
    val MUTED = Color.rgb(151, 163, 173)
    val LIME = Color.rgb(226, 255, 71)
    val LIVE = Color.rgb(255, 112, 77)
    val RULE = Color.rgb(43, 52, 60)

    fun shape(context: Context, color: Int = SURFACE, radius: Int = 14, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = context.sdp(radius).toFloat(); setColor(color)
        if (stroke != null) setStroke(context.sdp(2), stroke)
    }
    fun label(context: Context, text: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false,
        condensed: Boolean = false) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (condensed) "sans-serif-condensed" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }
    fun heading(context: Context, text: String, size: Float = 21f) = label(context, text, size, TEXT, true, true)
    fun row(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun column(context: Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun space(context: Context, height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(1, context.sdp(height)) }
    fun card(context: Context, tag: String, onClick: (() -> Unit)? = null) = column(context).apply {
        id = View.generateViewId(); this.tag = tag
        setPadding(context.sdp(14), context.sdp(12), context.sdp(14), context.sdp(12))
        background = shape(context)
        if (onClick != null) {
            isFocusable = true; isClickable = true
            setOnFocusChangeListener { view, focus -> view.background = shape(context, if (focus) RAISED else SURFACE, stroke = if (focus) LIME else null) }
            setOnClickListener { onClick() }
        }
    }
    fun button(context: Context, text: String, tag: String, onClick: () -> Unit) = label(context, text, 14f).apply {
        id = View.generateViewId(); this.tag = tag; contentDescription = text
        isFocusable = true; isClickable = true; gravity = Gravity.CENTER
        minHeight = context.sdp(38); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setPadding(context.sdp(16), context.sdp(8), context.sdp(16), context.sdp(8))
        background = shape(context, SURFACE, 22)
        setOnFocusChangeListener { view, focus -> view.background = shape(context, if (focus) RAISED else SURFACE, 22, if (focus) LIME else null) }
        setOnClickListener { onClick() }
    }
}
