package com.majendra.tvlauncher.navigation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.majendra.tvlauncher.diagnostics.DiagnosticLog
import com.majendra.tvlauncher.settings.HomeCompatibility

/**
 * One automatic launch per boot. The service tries as soon as it binds ([shouldLaunchEarly], once per boot count);
 * handled_boot is written when the launcher's window confirms it. If that never happens, BOOT_COMPLETED still gets
 * its single claimed launch ([shouldLaunch]), which is all there was before.
 */
object HomeBootPolicy {
    private const val BOOT_WINDOW_MS = 120_000L
    fun shouldLaunch(enabled: Boolean, ownsHome: Boolean, recoveryBypass: Boolean,
                     serviceBound: Boolean, elapsedMs: Long, currentBoot: Int,
                     pendingBoot: Int, handledBoot: Int): Boolean =
        enabled && ownsHome && !recoveryBypass && serviceBound &&
            elapsedMs in 0L..BOOT_WINDOW_MS && currentBoot >= 0 && pendingBoot == currentBoot &&
            handledBoot != currentBoot
    fun shouldLaunchEarly(enabled: Boolean, ownsHome: Boolean, recoveryBypass: Boolean,
                          serviceBound: Boolean, elapsedMs: Long, currentBoot: Int,
                          earlyBoot: Int, handledBoot: Int): Boolean =
        enabled && ownsHome && !recoveryBypass && serviceBound &&
            elapsedMs in 0L..BOOT_WINDOW_MS && currentBoot >= 0 && earlyBoot != currentBoot &&
            handledBoot != currentBoot
    /** After an early attempt, the broadcast launch only replaces Google's launcher (or an unknown foreground). */
    fun shouldRetry(earlyAttempted: Boolean, foregroundPackage: String?): Boolean =
        !earlyAttempted || foregroundPackage == null || foregroundPackage == HomeCompatibility.GOOGLE_PACKAGE
}

/**
 * BOOT_COMPLETED is a protected Android broadcast; no other actions are accepted. It reached this app about 9 s
 * after the service's process started (rc49), so it is now only the fallback for the service's early launch.
 */
class HomeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val config = HomeCompatibility(context)
        val boot = config.currentBootCount
        if (boot < 0 || !config.recordPendingBoot(boot)) return
        DiagnosticLog.event(context, "home_boot_received", mapOf("bootCount" to boot,
            "elapsedMs" to SystemClock.elapsedRealtime()))
        HomeCompatibilityService.consumePendingBootIfBound()
    }
}
