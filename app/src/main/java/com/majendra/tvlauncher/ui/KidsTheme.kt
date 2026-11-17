package com.majendra.tvlauncher.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.TextView

/** Launcher colours, with an explicit high-contrast TV focus treatment. */
object KidsTheme {
    val background = Color.rgb(205, 230, 252)
    val navy = Color.rgb(28, 39, 76)
    val white = Color.WHITE
    val yellow = Color.rgb(255, 219, 70)
    val playerOne = Color.rgb(255, 164, 63)
    val playerTwo = Color.rgb(164, 116, 249)
    val softBlue = Color.rgb(228, 242, 255)

    fun label(context: Context, size: Float = 16f, strong: Boolean = true) = TextView(context).apply {
        textSize = size
        setTextColor(navy)
        typeface = Typeface.create("sans-serif-rounded", if (strong) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }

    fun shape(context: Context, color: Int = white, focused: Boolean = false, radius: Int = 18): Drawable {
        val shadow = context.dp(4)
        val back = GradientDrawable().apply {
            setColor(navy)
            cornerRadius = context.dp(radius).toFloat()
        }
        val face = GradientDrawable().apply {
            setColor(if (focused) navy else color)
            cornerRadius = context.dp(radius).toFloat()
            setStroke(context.dp(if (focused) 3 else 2), if (focused) yellow else navy)
        }
        return LayerDrawable(arrayOf(back, face)).apply {
            setLayerInset(0, 0, shadow, 0, 0)
            setLayerInset(1, 0, 0, 0, shadow)
        }
    }

    fun focus(view: View, focused: Boolean, color: Int = white) {
        view.background = shape(view.context, color, focused)
        if (view is TextView) view.setTextColor(if (focused) white else navy)
    }

    /** Drawn locally so the children's avatars never depend on a font's emoji support. */
    fun mascot(context: Context, color: Int): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun getIntrinsicWidth() = context.dp(36)
        override fun getIntrinsicHeight() = context.dp(36)
        override fun draw(canvas: Canvas) {
            val save = canvas.save()
            canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
            canvas.scale(bounds.width() / 40f, bounds.height() / 40f)
            paint.style = Paint.Style.FILL
            paint.color = navy
            canvas.drawCircle(9f, 9f, 7f, paint)
            canvas.drawCircle(31f, 9f, 7f, paint)
            canvas.drawOval(3f, 6f, 37f, 38f, paint)
            paint.color = color
            canvas.drawCircle(9f, 9f, 4.5f, paint)
            canvas.drawCircle(31f, 9f, 4.5f, paint)
            canvas.drawOval(5f, 8f, 35f, 36f, paint)
            paint.color = Color.rgb(255, 240, 210)
            canvas.drawOval(11f, 20f, 29f, 32f, paint)
            paint.color = navy
            canvas.drawCircle(13f, 18f, 1.8f, paint)
            canvas.drawCircle(27f, 18f, 1.8f, paint)
            canvas.drawOval(17f, 22f, 23f, 26f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.6f
            paint.strokeCap = Paint.Cap.ROUND
            canvas.drawArc(15f, 22f, 25f, 30f, 20f, 140f, false, paint)
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Drawable opacity is not used")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
