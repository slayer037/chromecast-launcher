package com.majendra.tvlauncher.navigation

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * The black cover itself: one opaque full-screen accessibility overlay. It is never focusable or touchable, so
 * keys and focus go to whatever is underneath, and it shows and reads nothing. [HomeCurtainPolicy] decides when.
 * Main thread only. Android removes the window with the service's token if the process dies.
 */
class HomeCurtain(private val service: AccessibilityService) {
    private var view: View? = null

    /** False when Android refused the window; the caller carries on without a cover. */
    fun show(): Boolean {
        if (view != null) return true
        val cover = View(service).apply {
            setBackgroundColor(Color.BLACK)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE).apply {
            title = "HomeCurtain"
            windowAnimations = 0
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        // BadTokenException once the service's token is gone, or any OEM refusal: no cover, nothing else changes.
        return try { service.getSystemService(WindowManager::class.java).addView(cover, params); view = cover; true }
        catch (_: RuntimeException) { false }
    }

    /** Safe when nothing was added. The reference is dropped first so a failed removal is never retried forever. */
    fun hide() {
        val cover = view ?: return
        view = null
        try { service.getSystemService(WindowManager::class.java).removeViewImmediate(cover) }
        catch (_: RuntimeException) { /* Not attached any more: the token and its windows already went with the binding. */ }
    }
}
