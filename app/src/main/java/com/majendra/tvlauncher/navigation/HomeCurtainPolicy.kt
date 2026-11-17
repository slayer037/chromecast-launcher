package com.majendra.tvlauncher.navigation

import com.majendra.tvlauncher.settings.HomeCompatibility

/**
 * Timing for the black cover that hides Google TV's home while the launcher takes over at boot and wake.
 * Pure state; every time is SystemClock.uptimeMillis. The service owns the window and removes it at [removeAt].
 *
 * Why it cannot strand a lit screen: BOOT and WAKING each set a hard deadline on entry and [removeAt] is never
 * later than it, whatever windows are reported. ASLEEP has no deadline because the screen is off; it ends at
 * SCREEN_ON, and the service also polls the display so a lost SCREEN_ON still ends it.
 */
class HomeCurtainPolicy {
    enum class Phase { HIDDEN, BOOT, ASLEEP, WAKING }
    enum class Window { OURS, GOOGLE, OTHER }

    var phase = Phase.HIDDEN; private set
    private var deadline = NEVER
    private var holdUntil = 0L // Earliest removal after our own wake launch, while Google's trampoline may still act.
    private var arrivedAt = NEVER // When the destination's window was reported; NEVER while Google's launcher is in front.

    fun boot(now: Long) = enter(Phase.BOOT, now + BOOT_TIMEOUT_MS)
    fun screenOff() = enter(Phase.ASLEEP, NEVER)
    fun hide() = enter(Phase.HIDDEN, NEVER)
    private fun enter(next: Phase, until: Long) { phase = next; deadline = until; holdUntil = 0L; arrivedAt = NEVER }

    /** False for a duplicate SCREEN_ON or one with no SCREEN_OFF before it; those change nothing. */
    fun screenOn(now: Long, launched: Boolean): Boolean {
        if (phase != Phase.ASLEEP) return false
        phase = Phase.WAKING; deadline = now + WAKE_TIMEOUT_MS
        holdUntil = if (launched) now + WAKE_SETTLE_MS else 0L
        if (arrivedAt != NEVER) arrivedAt = now // Reported while SCREEN_ON was still queued: its frame is only now visible.
        return true
    }

    /** An activity window was reported. At boot only our own launcher is a destination. */
    fun window(now: Long, window: Window) {
        if (phase == Phase.HIDDEN || (phase == Phase.BOOT && window == Window.OTHER)) return
        arrivedAt = if (window == Window.GOOGLE) NEVER else now
    }

    fun removeAt(): Long = when {
        phase == Phase.HIDDEN || phase == Phase.ASLEEP -> NEVER
        arrivedAt == NEVER -> deadline
        else -> minOf(deadline, maxOf(arrivedAt + FIRST_FRAME_MS, holdUntil))
    }

    companion object {
        const val NEVER = Long.MAX_VALUE
        /** The launcher's cold start measured 2.5 s (rc49, 10 November 2026) on a quieter system; three times that. */
        const val BOOT_TIMEOUT_MS = 8_000L
        /** The redirected launcher resumed at most 1.57 s after SCREEN_ON in those measurements; about a second more. */
        const val WAKE_TIMEOUT_MS = 2_500L
        /**
         * After our own launch at SCREEN_ON, Google's home may still arrive on top: it resumed 0.48 s after SCREEN_ON
         * and its window event came before the 1.57 s redirect. Hold past that. Unverified; see HOME_COMPATIBILITY.md.
         */
        const val WAKE_SETTLE_MS = 1_800L
        /** A window event is sent when the window takes focus, about when its first frame is queued. */
        const val FIRST_FRAME_MS = 300L
        /** How often the service re-checks the cover; while ASLEEP this is the backstop for a lost SCREEN_ON. */
        const val POLL_MS = 500L
        private const val GOOGLE_DISPATCH = "${HomeCompatibility.GOOGLE_PACKAGE}.coreservices.bootmode.DispatchActivity"

        fun eligible(curtain: Boolean, enabled: Boolean, ownsHome: Boolean, recoveryBypass: Boolean): Boolean =
            curtain && enabled && ownsHome && !recoveryBypass
        fun window(packageName: String, ownPackage: String): Window = when (packageName) {
            ownPackage -> Window.OURS
            HomeCompatibility.GOOGLE_PACKAGE -> Window.GOOGLE
            else -> Window.OTHER
        }
        /** Google starts this over everything at SCREEN_OFF; it is not what the viewer was watching. */
        fun isTrampoline(packageName: String, className: String): Boolean =
            packageName == HomeCompatibility.GOOGLE_PACKAGE && className == GOOGLE_DISPATCH
        /** Only a launcher in front at SCREEN_OFF is replaced at wake; an app the TV was put to sleep in is left alone. */
        fun launcherInFront(packageName: String?, className: String?, ownPackage: String, ownLauncher: String): Boolean =
            packageName == HomeCompatibility.GOOGLE_PACKAGE || (packageName == ownPackage && className == ownLauncher)
        /** At SCREEN_ON: not over an app that arrived during the sleep, such as an input or cast app that woke the TV. */
        fun shouldLaunchAtWake(launcherBeforeSleep: Boolean, foregroundPackage: String?, ownPackage: String): Boolean =
            launcherBeforeSleep && foregroundPackage != null && window(foregroundPackage, ownPackage) != Window.OTHER
    }
}
