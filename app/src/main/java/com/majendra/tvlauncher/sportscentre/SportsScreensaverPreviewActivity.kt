package com.majendra.tvlauncher.sportscentre

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.majendra.tvlauncher.LauncherApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Manual preview only. Choosing a default remains an explicit Android settings action. */
class SportsScreensaverPreviewActivity : ComponentActivity() {
    private lateinit var display: SportsDreamView
    private val handler = Handler(Looper.getMainLooper())
    private var refresh: Job? = null
    private val tick = object : Runnable { override fun run() { display.tick(); handler.postDelayed(this, 10_000) } }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this)
        display = SportsDreamView(this)
        root.addView(display, FrameLayout.LayoutParams(-1, -1))
        val controls = SportsUi.row(this)
        controls.addView(SportsUi.button(this, "‹ Back", "sports-dream:back") { finish() })
        controls.addView(SportsUi.button(this, "Android screensaver settings", "sports-dream:settings") {
            try { startActivity(Intent(Settings.ACTION_DREAM_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, "Screensaver settings unavailable", Toast.LENGTH_SHORT).show() }
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = sdp(10) })
        root.addView(controls, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = sdp(18); rightMargin = sdp(26) })
        setContentView(root)
        controls.getChildAt(0).requestFocus()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick); handler.post(tick)
        refresh?.cancel()
        refresh = lifecycleScope.launch {
            while (isActive) {
                try { display.bind((application as LauncherApplication).sportsData.dashboard()) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Clock and the last snapshot remain visible. */ }
                delay(60_000)
            }
        }
    }

    override fun onPause() { refresh?.cancel(); refresh = null; handler.removeCallbacks(tick); super.onPause() }
    override fun onDestroy() { refresh?.cancel(); handler.removeCallbacks(tick); super.onDestroy() }
}
