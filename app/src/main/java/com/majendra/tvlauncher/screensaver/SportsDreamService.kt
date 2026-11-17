package com.majendra.tvlauncher.screensaver

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.dreams.DreamService
import com.majendra.tvlauncher.LauncherApplication
import com.majendra.tvlauncher.R
import com.majendra.tvlauncher.sportscentre.SportsDreamPolicy
import com.majendra.tvlauncher.sportscentre.SportsDreamView
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Optional Android dream. It never selects itself, wakes a TV or runs while detached. */
class SportsDreamService : DreamService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var refresh: Job? = null
    private var display: SportsDreamView? = null
    private var dreaming = false
    private val tick = object : Runnable {
        override fun run() {
            if (!dreaming) return
            if (!allowed()) { finish(); return }
            display?.tick(); handler.postDelayed(this, 10_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = false
        window?.let { value -> value.attributes = value.attributes.apply { screenBrightness = .10f } }
        display = SportsDreamView(this).also { setContentView(it) }
        if (!allowed()) finish()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        if (!allowed()) { finish(); return }
        dreaming = true
        handler.post(tick)
        refresh?.cancel()
        refresh = scope.launch {
            while (isActive && dreaming) {
                try { (application as LauncherApplication).sportsData.dashboard().let { if (dreaming) display?.bind(it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the last snapshot, which the view labels saved once old. */ }
                delay(60_000)
            }
        }
    }

    private fun allowed() = SportsDreamPolicy.allowed(resources.getBoolean(R.bool.bedroom_quiet_hours), Instant.now(),
        getSystemService(PowerManager::class.java)?.isInteractive == true,
        getSystemService(AudioManager::class.java)?.isMusicActive == true)

    private fun stop() { dreaming = false; refresh?.cancel(); refresh = null; handler.removeCallbacks(tick) }
    override fun onDreamingStopped() { stop(); super.onDreamingStopped() }
    override fun onDetachedFromWindow() { stop(); display = null; super.onDetachedFromWindow() }
    override fun onDestroy() { stop(); scope.cancel(); super.onDestroy() }
}
