package com.majendra.tvlauncher.diagnostics

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicit, process-local opt-in. No titles, view text, tags, URLs or account data are inspected. */
class RemoteNavigationDiagnostics private constructor(context: Context) {
    private val session = RemoteCaptureSession(SystemClock::uptimeMillis)
    private val file = File(context.applicationContext.filesDir, "remote-navigation.txt")
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "remote-diagnostics-io").apply { isDaemon = true } }
    private val queued = AtomicBoolean(false)
    private val revision = AtomicLong()
    private val handler = Handler(Looper.getMainLooper())
    private val expiry = Runnable { session.isRecording(); persist() }

    init { persist() } // A restart never resumes capture or exposes a previous session.

    fun start() { session.start(); handler.removeCallbacks(expiry); handler.postDelayed(expiry, 10 * 60 * 1000L); persist() }
    fun stop() { session.stop(); handler.removeCallbacks(expiry); persist() }
    fun clear() { session.clear(); handler.removeCallbacks(expiry); persist() }
    fun isRecording() = session.isRecording()
    fun onFocus(focus: FocusSnapshot) { if (session.focus(focus)) persist() }
    fun onKey(event: KeyEvent, focus: FocusSnapshot) {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> RemoteDirection.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> RemoteDirection.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> RemoteDirection.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> RemoteDirection.DOWN
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> RemoteDirection.CENTER
            KeyEvent.KEYCODE_BACK -> RemoteDirection.BACK
            else -> return
        }
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> RemoteAction.DOWN
            KeyEvent.ACTION_UP -> RemoteAction.UP
            else -> return
        }
        if (session.key(direction, action, event.repeatCount, event.downTime, event.eventTime, event.deviceId, focus)) persist()
    }

    fun showControls(context: Context) {
        AlertDialog.Builder(context).setTitle("Remote navigation diagnostics")
            .setItems(arrayOf("Start new 10-minute capture", "Stop capture", "View capture (${session.status()})", "Clear capture")) { _, choice ->
                when (choice) {
                    0 -> AlertDialog.Builder(context).setTitle("Record remote navigation?")
                        .setMessage("Records direction keys, timing and focus positions locally for up to 10 minutes or 500 events. No programme titles, searches or account details are recorded. Starting replaces the previous capture. Capture stops when the launcher process restarts.")
                        .setPositiveButton("Start") { _, _ -> start() }.setNegativeButton("Cancel", null).show()
                    1 -> { stop(); showControls(context) }
                    2 -> showCapture(context)
                    3 -> { clear(); showControls(context) }
                }
            }.setNegativeButton("Close", null).show()
    }

    private fun showCapture(context: Context) {
        val text = TextView(context).apply {
            setPadding(24, 16, 24, 16)
            textSize = 14f
            text = session.status() + "\n\n" + session.lines().joinToString("\n").ifEmpty { "No events captured." }
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(context).apply { addView(text); isFocusable = true }
        AlertDialog.Builder(context).setTitle("Remote capture").setView(scroll).setPositiveButton("Close", null).show()
    }

    // Coalesce bursts; disk work never occurs on the remote/focus callback thread.
    private fun persist() {
        revision.incrementAndGet()
        if (!queued.compareAndSet(false, true)) return
        io.execute {
            var written = -1L
            try {
                do {
                    val version = revision.get()
                    val content = session.status() + "\n" + session.lines().joinToString("\n")
                    try { file.writeText(content.take(200 * 1024), Charsets.UTF_8) } catch (_: java.io.IOException) { /* Capture remains viewable in memory. */ }
                    written = version
                } while (revision.get() != version)
            } finally {
                queued.set(false)
                if (revision.get() != written) persist()
            }
        }
    }

    companion object {
        @Volatile private var instance: RemoteNavigationDiagnostics? = null
        fun get(context: Context): RemoteNavigationDiagnostics = instance ?: synchronized(this) {
            instance ?: RemoteNavigationDiagnostics(context.applicationContext).also { instance = it }
        }

        /** Row is the outer RecyclerView position when nested; no UI labels or item keys are read. */
        fun sampleFocus(view: View?, pane: Pane): FocusSnapshot {
            if (view == null) return FocusSnapshot(Pane.NONE)
            var child: View = view
            var position = -1
            var row = -1
            while (true) {
                val parent = child.parent as? View ?: break
                if (parent is RecyclerView) {
                    val found = parent.getChildAdapterPosition(child)
                    if (position == -1) position = found else row = found
                }
                child = parent
            }
            return FocusSnapshot(pane, row, position)
        }
    }
}
