package com.majendra.tvlauncher.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import com.majendra.tvlauncher.data.lightweightLauncher

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

object Theme {
    val background = Color.rgb(12, 16, 22)
    val sidebar = Color.rgb(16, 23, 32)
    val surface = Color.rgb(32, 42, 54)
    val selected = Color.rgb(41, 63, 85)
    val text = Color.rgb(240, 243, 246)
    val muted = Color.rgb(169, 182, 196)
    val accent = Color.rgb(142, 201, 255)
    val live = Color.rgb(255, 140, 128)

    fun shape(context: Context, color: Int = surface, focused: Boolean = false, radius: Int = 10) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = context.dp(radius).toFloat()
            setStroke(context.dp(if (focused) 3 else 1), if (focused) accent else Color.TRANSPARENT)
        }

    fun label(context: Context, size: Float = 16f, strong: Boolean = false) = TextView(context).apply {
        setTextColor(Theme.text)
        textSize = size
        typeface = Typeface.create("sans-serif", if (strong) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }

    fun focus(view: View, focused: Boolean, focusedElevationDp: Int = 6) {
        // Keep the background attached while focus moves, avoiding a new drawable
        // allocation and background replacement for every gain/loss.
        val background = view.background as? FocusBackground
            ?: FocusBackground(view.context).also { view.background = it }
        background.showFocus(focused)
        val elevation = view.context.dp(if (focused && !view.context.lightweightLauncher) focusedElevationDp else 0).toFloat()
        if (view.elevation != elevation) view.elevation = elevation
    }

    private class FocusBackground(context: Context) : GradientDrawable() {
        private val plainStroke = context.dp(1)
        private val focusStroke = context.dp(3)
        private var focused = false

        init {
            cornerRadius = context.dp(10).toFloat()
            setColor(surface)
            setStroke(plainStroke, Color.TRANSPARENT)
        }

        fun showFocus(value: Boolean) {
            if (focused == value) return
            focused = value
            setColor(if (value) selected else surface)
            setStroke(if (value) focusStroke else plainStroke, if (value) accent else Color.TRANSPARENT)
        }
    }
}
