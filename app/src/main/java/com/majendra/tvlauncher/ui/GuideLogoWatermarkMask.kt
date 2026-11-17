package com.majendra.tvlauncher.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import coil.size.Size
import coil.transform.Transformation

/**
 * The Melbourne guide's channel logos carry a small "XMLTV.net" credit in the bottom-right corner.
 * Paint that corner with the logo's own background so tiles show just the logo; the guide remains
 * credited in the project documentation.
 */
object GuideLogoWatermarkMask : Transformation {
    override val cacheKey = "guide-logo-watermark-mask-v5"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val output = input.copy(Bitmap.Config.ARGB_8888, true)
        val background = output.getPixel(output.width - 2, output.height - 2)
        val paint = Paint().apply { color = background; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
        // Wide logos (such as 9HD) push the credit further left, and it can sit on the bottom edge.
        Canvas(output).drawRect(output.width * 0.45f, output.height * 0.70f, output.width.toFloat(), output.height.toFloat(), paint)
        return output
    }
}
