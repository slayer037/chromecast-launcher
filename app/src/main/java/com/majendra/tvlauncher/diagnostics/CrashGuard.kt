package com.majendra.tvlauncher.diagnostics

import android.content.Context

/**
 * This launcher is the only HOME the TV opens, so a build or a saved file that crashes it on every start would
 * otherwise loop. Two crashes close together put the next start into safe mode: a plain screen that touches no
 * feeds, stores or artwork and offers TV settings, the installed apps and another try. The mini PC watchdog
 * (scripts/tv-home-watchdog.sh) is the outer net; it acts at three crashes, which safe mode should prevent.
 */
object CrashGuardPolicy {
    const val WINDOW_MILLIS = 5 * 60_000L
    const val THRESHOLD = 2
    private const val KEPT = 8

    /** Wall-clock times, because they must survive the process. A clock set backwards simply forgets them. */
    fun recent(crashes: List<Long>, now: Long): List<Long> = crashes.filter { now - it in 0..WINDOW_MILLIS }
    fun record(crashes: List<Long>, now: Long): List<Long> = (recent(crashes, now) + now).takeLast(KEPT)
    fun safeMode(crashes: List<Long>, now: Long): Boolean = recent(crashes, now).size >= THRESHOLD
    fun encode(crashes: List<Long>): String = crashes.joinToString(",")
    fun decode(saved: String?): List<Long> = saved.orEmpty().split(",").mapNotNull { it.trim().toLongOrNull() }
}

class CrashGuard(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("crash_guard", Context.MODE_PRIVATE)
    private fun crashes() = CrashGuardPolicy.decode(prefs.getString(KEY, null))

    /** Decided once per process, so every part of one start agrees on it; only [clear] ends it early. */
    @Volatile var safeMode: Boolean = try { CrashGuardPolicy.safeMode(crashes(), System.currentTimeMillis()) } catch (_: RuntimeException) { false }
        private set

    /** Chains to the platform handler, which still reports the crash and ends the process. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // commit, not apply: the process is about to die.
            try { prefs.edit().putString(KEY, CrashGuardPolicy.encode(CrashGuardPolicy.record(crashes(), System.currentTimeMillis()))).commit() }
            catch (_: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The viewer asked for another try: forget the crashes so the next start is a normal one. */
    fun clear() { prefs.edit().remove(KEY).commit(); safeMode = false }

    private companion object { const val KEY = "crashes" }
}
