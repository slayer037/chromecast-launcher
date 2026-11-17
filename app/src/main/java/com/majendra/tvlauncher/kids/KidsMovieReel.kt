package com.majendra.tvlauncher.kids

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ImageRequest
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.providers.StremioMetadataPolicy
import com.majendra.tvlauncher.ui.KidsTheme
import kotlin.math.cos
import kotlin.math.sin

/** A real two-nominee reel: its final position is fixed by the caller's fair draw. */
class KidsMovieReel(context: Context, private val movies: List<ContentItem>, private val winner: Int,
                    private val noun: String = "movie") : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val posters = arrayOfNulls<Bitmap>(2)
    private val requests = mutableListOf<Disposable>()
    private var animator: ValueAnimator? = null
    private var celebration: ValueAnimator? = null
    private var celebrationStart: Runnable? = null
    private var position = 0f
    private var sparkle = 0f
    private var stopped = false
    private val bears = listOf(KidsTheme.mascot(context, KidsTheme.playerOne), KidsTheme.mascot(context, KidsTheme.playerTwo))

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Choosing between Player One's ${movies[0].title} and Player Two's ${movies[1].title}. Equal chances."
        movies.forEachIndexed { index, movie ->
            movie.imageUrl?.takeIf(StremioMetadataPolicy::publicUrl)?.let { url ->
                requests += context.imageLoader.enqueue(ImageRequest.Builder(context).data(url)
                    .size(240, 360).crossfade(false).diskCachePolicy(CachePolicy.DISABLED)
                    .target(onSuccess = { drawable ->
                        if (!stopped) { posters[index] = drawable.toBitmap(240, 360); invalidate() }
                    }).build())
            }
        }
    }

    fun start() {
        if (stopped) return
        animator = ValueAnimator.ofFloat(0f, (20 + winner).toFloat()).apply {
            duration = 3200
            interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
        val reveal = Runnable {
            if (!stopped) {
                contentDescription = "Tonight's $noun is ${movies[winner].title}. Opening it now."
                announceForAccessibility(contentDescription)
                celebration = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 900
                    addUpdateListener { sparkle = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        }
        celebrationStart = reveal
        postDelayed(reveal, 3200)
    }

    fun stop() {
        stopped = true
        celebrationStart?.let { removeCallbacks(it) }; celebrationStart = null
        animator?.cancel(); celebration?.cancel()
        requests.forEach { it.dispose() }; requests.clear()
        posters.fill(null)
    }

    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val save = canvas.save()
        canvas.scale(density, density)
        val w = width / density
        val h = height / density
        val cardWidth = 138f
        val cardHeight = (h - 76f).coerceAtMost(215f)
        val step = cardWidth + 22f
        val center = w / 2f
        val first = position.toInt() - 3
        for (slot in first..first + 7) {
            val nominee = Math.floorMod(slot, 2)
            val x = center + (slot - position) * step - cardWidth / 2
            if (x > w || x + cardWidth < 0) continue
            val rect = RectF(x, 14f, x + cardWidth, 14f + cardHeight)
            paint.style = Paint.Style.FILL; paint.color = KidsTheme.navy
            canvas.drawRoundRect(RectF(rect.left, rect.top + 5, rect.right, rect.bottom + 5), 15f, 15f, paint)
            paint.color = if (nominee == 0) KidsTheme.playerOne else KidsTheme.playerTwo
            canvas.drawRoundRect(rect, 15f, 15f, paint)
            val imageRect = RectF(rect.left + 5, rect.top + 5, rect.right - 5, rect.bottom - 34)
            val clip = canvas.save()
            canvas.clipRect(imageRect)
            posters[nominee]?.let { canvas.drawBitmap(it, null, imageRect, paint) }
            canvas.restoreToCount(clip)
            paint.color = KidsTheme.navy
            canvas.drawRect(imageRect.left, imageRect.bottom - 37f, imageRect.right, imageRect.bottom, paint)
            paint.color = KidsTheme.white; paint.textSize = 12f
            paint.typeface = android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            val movieTitle = TextUtils.ellipsize(movies[nominee].title, TextPaint(paint), imageRect.width() - 10,
                TextUtils.TruncateAt.END).toString()
            canvas.drawText(movieTitle, x + cardWidth / 2, imageRect.bottom - 14f, paint)
            paint.color = KidsTheme.navy; paint.textSize = 14f
            paint.typeface = android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(if (nominee == 0) "PLAYER ONE'S PICK" else "PLAYER TWO'S PICK", x + cardWidth / 2, rect.bottom - 12, paint)
        }
        paint.color = KidsTheme.yellow; paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f
        canvas.drawRoundRect(RectF(center - cardWidth / 2 - 5, 8f, center + cardWidth / 2 + 5, cardHeight + 20), 18f, 18f, paint)
        paint.style = Paint.Style.FILL
        val arrow = Path().apply { moveTo(center - 11, 0f); lineTo(center + 11, 0f); lineTo(center, 12f); close() }
        canvas.drawPath(arrow, paint)
        bears.forEachIndexed { index, bear ->
            val bounce = if (sparkle > 0) sin(sparkle * Math.PI * 4).toFloat() * 8 else 0f
            val x = if (index == 0) 12f else w - 80f
            bear.setBounds(x.toInt(), (h - 75 - bounce).toInt(), (x + 68).toInt(), (h - 7 - bounce).toInt())
            bear.draw(canvas)
        }
        paint.textAlign = Paint.Align.CENTER; paint.textSize = 17f; paint.color = KidsTheme.navy
        val message = if (sparkle > 0) "Winner: ${movies[winner].title}" else "Whose $noun will it be?"
        canvas.drawText(TextUtils.ellipsize(message, TextPaint(paint), w - 185,
            TextUtils.TruncateAt.END).toString(), center, h - 24, paint)
        if (sparkle > 0) repeat(16) { index ->
            val angle = index * Math.PI * 2 / 16
            val radius = 70f + sparkle * 100
            val x = center + cos(angle).toFloat() * radius * 1.6f
            val y = cardHeight / 2 + sin(angle).toFloat() * radius
            val star = Path()
            repeat(10) { point ->
                val a = point * Math.PI / 5 - Math.PI / 2
                val r = if (point % 2 == 0) 7f else 3f
                val px = x + cos(a).toFloat() * r; val py = y + sin(a).toFloat() * r
                if (point == 0) star.moveTo(px, py) else star.lineTo(px, py)
            }
            star.close(); paint.color = if (index % 2 == 0) KidsTheme.yellow else KidsTheme.playerTwo
            canvas.drawPath(star, paint)
        }
        canvas.restoreToCount(save)
    }
}
