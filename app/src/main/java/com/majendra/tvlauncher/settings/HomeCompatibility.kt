package com.majendra.tvlauncher.settings

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AlertDialog
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import com.majendra.tvlauncher.navigation.HomeCompatibilityService
import com.majendra.tvlauncher.navigation.HomeBootPolicy
import com.majendra.tvlauncher.navigation.HomeCurtainPolicy

/** Never changes HOME role or enables an accessibility service itself. */
class HomeCompatibility(private val context: Context) {
    private val prefs = context.getSharedPreferences("home_compatibility", Context.MODE_PRIVATE)
    val enabled get() = prefs.getBoolean("enabled", false)
    val recoveryBypass get() = prefs.getBoolean("recovery_bypass", false)
    val currentBootCount: Int get() = try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
        catch (_: SecurityException) { -1 }
    fun recordPendingBoot(boot: Int): Boolean = synchronized(bootLock) {
        boot >= 0 && prefs.edit().putInt("pending_boot", boot).commit()
    }
    fun claimPendingBoot(serviceBound: Boolean, elapsedMs: Long): Boolean = synchronized(bootLock) {
        val boot = currentBootCount
        if (!HomeBootPolicy.shouldLaunch(enabled, holdsHomeRole, recoveryBypass, serviceBound,
                elapsedMs, boot, prefs.getInt("pending_boot", -1), prefs.getInt("handled_boot", -1))) false
        else prefs.edit().putInt("handled_boot", boot).remove("pending_boot").commit()
    }
    /** Durable before the early launch so a rebind or restart never repeats it; handled_boot waits for [markBootHandled]. */
    fun claimEarlyBoot(serviceBound: Boolean, elapsedMs: Long): Boolean = synchronized(bootLock) {
        val boot = currentBootCount
        HomeBootPolicy.shouldLaunchEarly(enabled, holdsHomeRole, recoveryBypass, serviceBound, elapsedMs, boot,
            prefs.getInt("early_boot", -1), prefs.getInt("handled_boot", -1)) &&
            prefs.edit().putInt("early_boot", boot).commit()
    }
    val earlyBootAttempted: Boolean get() = currentBootCount.let { it >= 0 && prefs.getInt("early_boot", -1) == it }
    /** The early launch was seen on screen, or was suppressed for good: BOOT_COMPLETED must not launch again. */
    fun markBootHandled(): Boolean = synchronized(bootLock) {
        val boot = currentBootCount
        boot >= 0 && prefs.edit().putInt("handled_boot", boot).remove("pending_boot").commit()
    }
    val holdsHomeRole: Boolean get() = Build.VERSION.SDK_INT >= 29 &&
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
    val serviceEnabled: Boolean get() {
        val expected = ComponentName(context, HomeCompatibilityService::class.java)
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == expected
        }
    }
    fun bypassForRecovery() { prefs.edit().putBoolean("recovery_bypass", true).commit() }
    fun ownLauncherActive() { if (recoveryBypass) prefs.edit().putBoolean("recovery_bypass", false).commit() }
    private fun enable(value: Boolean) { prefs.edit().putBoolean("enabled", value).commit() }
    fun show() {
        val name = context.getString(com.majendra.tvlauncher.R.string.app_name)
        // Consent text: say exactly what the cover does, and only in the build that uses it.
        val curtain = context.resources.getBoolean(com.majendra.tvlauncher.R.bool.home_curtain_enabled)
        val cover = if (!curtain) "It cannot prevent the TV firmware from starting Google TV during boot."
            else "It cannot prevent the TV firmware from starting Google TV at boot or when the TV wakes, so while $name takes over it draws a plain black cover over the whole screen: for at most ${HomeCurtainPolicy.BOOT_TIMEOUT_MS / 1000} seconds at boot and ${HomeCurtainPolicy.WAKE_TIMEOUT_MS / 1000.0} seconds after wake, and while the screen is off. The cover shows nothing, reads nothing and passes every key through. Google TV can still appear briefly at boot before Android has started this service. If a launcher was on screen when the TV went to sleep, $name is reopened when it wakes. None of this happens while original Google TV is open through recovery."
        val state = "Workaround: ${if (enabled) "enabled" else "disabled"}\n" +
            "Android accessibility service: ${if (serviceEnabled) "enabled" else "disabled"}\n" +
            "${context.getString(com.majendra.tvlauncher.R.string.app_name)} is selected HOME: ${if (holdsHomeRole) "yes" else "no"}\n" +
            "Recovery bypass: ${if (recoveryBypass) "active" else "inactive"}"
        AlertDialog.Builder(context).setTitle("Chromecast Launcher Home compatibility")
            .setMessage("Some TV firmware opens Google TV despite the selected HOME app. This optional service handles only the Home button to open ${context.getString(com.majendra.tvlauncher.R.string.app_name)} directly while it is selected HOME and the TV is unlocked. All other keys pass through immediately without collection or logging. Android requires key-filtering consent for this feature. Google TV Home window detection remains a fallback. The service can also open ${context.getString(com.majendra.tvlauncher.R.string.app_name)} once within two minutes of Android starting, as soon as Android has started the service. It also returns Back to this launcher from a channel it opened. Kayo profile selection stays manual. $cover\n\n$state")
            .setPositiveButton(if (enabled) "Disable workaround" else "Enable workaround") { _, _ ->
                if (enabled) { enable(false); Toast.makeText(context, "Home workaround disabled", Toast.LENGTH_SHORT).show() }
                else AlertDialog.Builder(context).setTitle("Enable Home compatibility?")
                    .setMessage("Enable the preference, then turn on ${context.getString(com.majendra.tvlauncher.R.string.app_name)} Home compatibility in Android accessibility settings. Android will request consent to filter key events. This service handles only Home; it never collects or logs other keys or reads screen content. It only acts while ${context.getString(com.majendra.tvlauncher.R.string.app_name)} holds the HOME role and the TV is unlocked. Opening original Google TV pauses new Home requests until you return here.${if (curtain) " At boot and wake it briefly covers the screen in black while $name opens." else ""}")
                    .setPositiveButton("Continue to Android settings") { _, _ -> enable(true); openAccessibilitySettings() }
                    .setNegativeButton("Cancel", null).show()
            }
            .setNeutralButton("Android accessibility settings") { _, _ -> openAccessibilitySettings() }
            .setNegativeButton("Close", null).show()
    }
    private fun openAccessibilitySettings() {
        try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "Accessibility settings are unavailable", Toast.LENGTH_LONG).show() }
    }
    companion object {
        private val bootLock = Any()
        const val GOOGLE_PACKAGE = "com.google.android.apps.tv.launcherx"
        const val GOOGLE_HOME = "$GOOGLE_PACKAGE.home.HomeActivity"
        fun googleHomeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setComponent(ComponentName(GOOGLE_PACKAGE, GOOGLE_HOME)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
