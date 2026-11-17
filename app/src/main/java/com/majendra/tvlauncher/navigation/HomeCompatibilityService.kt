package com.majendra.tvlauncher.navigation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.majendra.tvlauncher.MainActivity
import com.majendra.tvlauncher.R
import com.majendra.tvlauncher.settings.HomeCompatibility
import com.majendra.tvlauncher.diagnostics.DiagnosticLog
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

object HomeRedirectPolicy {
    fun shouldRedirect(windowEvent: Boolean, packageName: String?, className: String?,
                       enabled: Boolean, ownsHome: Boolean, recoveryBypass: Boolean): Boolean =
        windowEvent && packageName == HomeCompatibility.GOOGLE_PACKAGE &&
            className == HomeCompatibility.GOOGLE_HOME && enabled && ownsHome && !recoveryBypass
}

/**
 * HOME and remapped remote app-button routing, the exact Google Home window fallback, Back from a launcher-opened
 * TV channel, and picking the user on Kayo's profile screen. Window events are read for package and activity names
 * only; screenshot and gesture capabilities are disabled in this edition.
 *
 * The firmware starts Google's home at boot and wake whatever this app does, so the service also opens the
 * launcher as soon as it binds after boot and at SCREEN_ON, and keeps a black cover ([HomeCurtain]) over the
 * screen meanwhile. Everything here runs on the main thread; [HomeCurtainPolicy] holds the cover's timing.
 */
class HomeCompatibilityService : AccessibilityService() {
    private var lastRedirect = -1000L
    private var foregroundPackage: String? = null
    private var foregroundClass: String? = null
    // The latest activity that is not Google's SCREEN_OFF trampoline: what the viewer was really in.
    private var userPackage: String? = null
    private var userClass: String? = null
    private val takeover = HomeCurtainPolicy()
    private val curtain = HomeCurtain(this)
    private var curtainEnabled = false
    private var bootCovered = false // The boot cover is used once per binding, so a failing boot costs one timeout.
    private var bootUnconfirmed = false // Early boot launch sent; the launcher's window has not been reported yet.
    private var launcherBeforeSleep = false
    private var screenReceiver: BroadcastReceiver? = null
    private var backPress: Long? = null // Down time of a Back press this service took, so its release is taken too.
    private val main = Handler(Looper.getMainLooper())
    private var kayoWatchUntil = 0L
    private var kayoTaps = 0
    private val activities = HashMap<String, Boolean>()
    private var bound = false
    private val homeKeys = HomeKeyPolicy()
    private val appButtons = RemoteButtonPolicy()
    private val eventWriter = Executors.newSingleThreadExecutor()
    // Kayo screenshot copies only; its thread exits when idle and it is never shut down, so a late callback is accepted.
    private val screenshots = ThreadPoolExecutor(0, 1, 5L, TimeUnit.SECONDS, LinkedBlockingQueue())
    /**
     * Every launch from this service uses the HOME intent, so it lands in the launcher's one home task. A plain
     * explicit intent is a standard-type start, which cannot reuse a home-type task and so made a second
     * MainActivity. Android types a start as HOME only when the caller named no component (system callers aside),
     * hence the package scope; the explicit component remains the fallback if that would not resolve to us.
     */
    private val homeIntent: Intent by lazy {
        val launcher = ComponentName(this, MainActivity::class.java)
        val scoped = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = try { packageManager.resolveActivity(scoped, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo }
            catch (_: RuntimeException) { null }
        if (resolved?.packageName == launcher.packageName && resolved.name == launcher.className) scoped
        else scoped.setPackage(null).setComponent(launcher)
    }
    private fun launcherIntent(section: String? = null) =
        Intent(homeIntent).apply { if (section != null) putExtra(MainActivity.EXTRA_SECTION, section) }
    private fun openLauncher(section: String? = null): Boolean =
        try { startActivity(launcherIntent(section)); true }
        catch (_: android.content.ActivityNotFoundException) { false }
        catch (_: SecurityException) { false } // Firmware may restrict background starts.
    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            notificationTimeout = 0
        }
        bound = true
        active = WeakReference(this)
        curtainEnabled = resources.getBoolean(R.bool.home_curtain_enabled)
        if (curtainEnabled) watchScreen()
        consumePendingBoot()
    }
    /**
     * At most one automatic launch per boot. Early: as soon as the service binds, without waiting for
     * BOOT_COMPLETED. Late: when BOOT_COMPLETED has reached [HomeBootReceiver] and nothing confirmed the early one.
     * Either claim is durable before launching, so failures, rebinds and duplicate broadcasts never repeat it.
     */
    private fun consumePendingBoot() {
        val config = HomeCompatibility(this)
        val elapsed = SystemClock.elapsedRealtime()
        val late = config.claimPendingBoot(bound, elapsed)
        if (!late && !config.claimEarlyBoot(bound, elapsed)) return
        if (!automaticLaunchAllowed()) {
            if (!late) config.markBootHandled() // Suppressed, not deferred: the later broadcast must not launch either.
            log("home_boot_suppressed", mapOf("reason" to "bedroom_automatic_launch_guard"))
            return
        }
        if (late && !HomeBootPolicy.shouldRetry(config.earlyBootAttempted, foregroundPackage)) {
            log("home_boot_suppressed", mapOf("reason" to "app_in_front"))
            return
        }
        if (curtainWanted() && !bootCovered && takeover.phase == HomeCurtainPolicy.Phase.HIDDEN) {
            bootCovered = true
            takeover.boot(SystemClock.uptimeMillis()); cover()
        }
        val details = mapOf("bootCount" to config.currentBootCount, "early" to !late, "elapsedMs" to elapsed)
        try {
            startActivity(launcherIntent())
            // An early start can be dropped without an exception, so only the launcher's own window settles it.
            bootUnconfirmed = !late
            log("home_boot_requested", details)
        } catch (_: android.content.ActivityNotFoundException) {
            uncover(); log("home_boot_start_failed", details + ("reason" to "activity_unavailable"))
        } catch (_: SecurityException) {
            uncover(); log("home_boot_start_failed", details + ("reason" to "start_restricted"))
        }
    }
    /**
     * The cover only exists to hide Google's home. Where that package is disabled or absent the firmware opens
     * this launcher directly, and a cover would just be a black pause on every wake. Read each time: it can change.
     */
    private fun curtainWanted(): Boolean = curtainEnabled && try {
        packageManager.getApplicationInfo(HomeCompatibility.GOOGLE_PACKAGE, 0).enabled
    } catch (_: PackageManager.NameNotFoundException) { false }
    private fun watchScreen() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> screenOff()
                    Intent.ACTION_SCREEN_ON -> { screenOn(); tickCurtain() }
                }
            }
        }
        // Protected system broadcasts; a screensaver (dream) sends neither.
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
        screenReceiver = receiver
    }
    /** The cover goes up now, while nobody can see it, so there is nothing to race when the screen comes back. */
    private fun screenOff() {
        val config = HomeCompatibility(this)
        if (!HomeCurtainPolicy.eligible(curtainWanted(), config.enabled, config.holdsHomeRole, config.recoveryBypass)) {
            uncover(); return
        }
        launcherBeforeSleep = HomeCurtainPolicy.launcherInFront(userPackage, userClass, packageName, MainActivity::class.java.name)
        takeover.screenOff(); cover()
    }
    private fun screenOn() {
        if (takeover.phase != HomeCurtainPolicy.Phase.ASLEEP) return // Duplicate SCREEN_ON, or nothing was armed for this sleep.
        val config = HomeCompatibility(this)
        if (!HomeCurtainPolicy.eligible(curtainWanted(), config.enabled, config.holdsHomeRole, config.recoveryBypass)) {
            uncover(); return
        }
        // A launcher was in front before the sleep: open ours now instead of waiting for Google's home to appear.
        val launched = HomeCurtainPolicy.shouldLaunchAtWake(launcherBeforeSleep, foregroundPackage, packageName) &&
            automaticLaunchAllowed() && openLauncher()
        takeover.screenOn(SystemClock.uptimeMillis(), launched)
        if (launched) log("home_wake_requested", emptyMap())
    }
    private fun cover() {
        if (!curtain.show()) log("home_curtain_failed", emptyMap())
        main.removeCallbacks(curtainTick); main.postDelayed(curtainTick, HomeCurtainPolicy.POLL_MS)
    }
    private fun uncover() { takeover.hide(); curtain.hide(); main.removeCallbacks(curtainTick) }
    private val curtainTick: Runnable = Runnable { tickCurtain() }
    /**
     * The only place the cover's timing is acted on. While the policy is not HIDDEN this is always pending on the
     * main handler, at most POLL_MS ahead, so the cover comes down at [HomeCurtainPolicy.removeAt] whatever else
     * happens; and a display that is on while the policy still says ASLEEP is treated as the missing SCREEN_ON.
     */
    private fun tickCurtain() {
        main.removeCallbacks(curtainTick)
        if (takeover.phase == HomeCurtainPolicy.Phase.ASLEEP &&
            getSystemService(PowerManager::class.java)?.isInteractive != false) screenOn()
        if (takeover.phase == HomeCurtainPolicy.Phase.HIDDEN) { curtain.hide(); return }
        val wait = takeover.removeAt() - SystemClock.uptimeMillis()
        if (wait <= 0L) uncover() else main.postDelayed(curtainTick, minOf(wait, HomeCurtainPolicy.POLL_MS))
    }
    private fun clearBound() {
        bound = false
        if (active?.get() === this) active = null
        uncover() // The cover never outlives the binding.
        screenReceiver?.let { try { unregisterReceiver(it) } catch (_: IllegalArgumentException) {} }
        screenReceiver = null
    }
    override fun onUnbind(intent: Intent?): Boolean { clearBound(); return super.onUnbind(intent) }
    override fun onDestroy() { clearBound(); main.removeCallbacks(kayoCheck); eventWriter.shutdown(); super.onDestroy() }
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (RemoteButtonPolicy.sectionFor(event.keyCode) != null) return onAppButton(event)
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return onBack(event)
        // Do not copy, retain, inspect preferences for, or log any other key.
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> homeKeys.down(event.deviceId, event.downTime, event.repeatCount,
                allowed = {
                    val config = HomeCompatibility(this)
                    val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
                    HomeKeyPolicy.eligible(config.enabled, config.holdsHomeRole, config.recoveryBypass, locked)
                },
                launch = {
                    // Logged off the input callback; service teardown must not affect key routing.
                    openLauncher().also { if (it) log("home_key_requested", mapOf("uptimeMs" to SystemClock.uptimeMillis())) }
                })
            KeyEvent.ACTION_UP -> homeKeys.up(event.deviceId, event.downTime)
            else -> false
        }
    }
    private fun onBack(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) return (backPress == event.downTime).also { if (it) backPress = null }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (backPress == event.downTime) return true
        val armed = resources.getBoolean(R.bool.live_tv_enabled) && LiveBackPolicy.armed(this)
        if (!LiveBackPolicy.shouldReturn(!HomeCompatibility(this).recoveryBypass, armed, foregroundPackage, foregroundClass, event.repeatCount)) {
            if (armed && foregroundPackage == LiveBackPolicy.TV_PACKAGE) log("live_back_passed", mapOf("activity" to foregroundClass))
            return false
        }
        if (!openLauncher()) return false
        backPress = event.downTime
        log("live_back_returned", emptyMap())
        return true
    }
    private fun log(type: String, values: Map<String, Any?>) {
        try { eventWriter.execute { DiagnosticLog.event(this, type, values) } } catch (_: RejectedExecutionException) {}
    }
    /** Manual remote handlers above and below intentionally do not call this guard. */
    private fun automaticLaunchAllowed(): Boolean {
        val bedroomGuard = resources.getBoolean(R.bool.bedroom_quiet_hours)
        if (!bedroomGuard) return true
        return QuietHoursPolicy.allowsAutomaticLaunch(true, Instant.now(),
            getSystemService(PowerManager::class.java)?.isInteractive == true)
    }
    /** Activities only: toasts and popups also change window state but never change what is on screen underneath. */
    private fun isActivity(packageName: String, className: String) = activities.getOrPut("$packageName/$className") {
        if (activities.size > 200) activities.clear()
        // Apps outside the manifest's package queries can't be looked up; for them, framework classes are the popups.
        try { packageManager.getActivityInfo(ComponentName(packageName, className), 0); true }
        catch (_: Exception) { !className.startsWith("android.") }
    }
    private fun onForeground(packageName: String) { /* Profile selection stays manual. */ }
    private val kayoCheck: Runnable = Runnable { checkKayoProfile() }
    private fun checkKayoProfile() { /* No profile automation in the public edition. */ }
    /** Remapped remote app buttons open a launcher section; the original app is never started. */
    private fun onAppButton(event: KeyEvent): Boolean = when (event.action) {
        KeyEvent.ACTION_DOWN -> appButtons.down(event.keyCode, event.deviceId, event.downTime, event.repeatCount,
            allowed = {
                val config = HomeCompatibility(this)
                val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
                RemoteButtonPolicy.eligible(config.enabled, config.recoveryBypass, locked)
            },
            open = { section ->
                try {
                    val app = RemoteButtonPolicy.appPackage(section)?.let {
                        packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it)
                    }
                    // A missing app falls back to the launcher instead of letting the original app open.
                    startActivity((app ?: launcherIntent(section.takeIf { RemoteButtonPolicy.appPackage(it) == null }))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                } catch (_: android.content.ActivityNotFoundException) { false }
                catch (_: SecurityException) { false }
            })
        KeyEvent.ACTION_UP -> appButtons.up(event.keyCode, event.deviceId, event.downTime)
        else -> false
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString(); val cls = event.className?.toString()
        if (pkg != null && cls != null && isActivity(pkg, cls)) {
            onForeground(pkg); foregroundPackage = pkg; foregroundClass = cls
            if (!HomeCurtainPolicy.isTrampoline(pkg, cls)) { userPackage = pkg; userClass = cls }
            if (bootUnconfirmed && pkg == packageName && cls == MainActivity::class.java.name) {
                bootUnconfirmed = false
                HomeCompatibility(this).markBootHandled() // One small write per boot.
            }
            if (takeover.phase != HomeCurtainPolicy.Phase.HIDDEN) {
                takeover.window(SystemClock.uptimeMillis(), HomeCurtainPolicy.window(pkg, packageName)); tickCurtain()
            }
        }
        // Only the Google Home window is redirected.
        if (pkg != HomeCompatibility.GOOGLE_PACKAGE || cls != HomeCompatibility.GOOGLE_HOME) return
        val config = HomeCompatibility(this)
        if (!HomeRedirectPolicy.shouldRedirect(true, pkg, cls, config.enabled, config.holdsHomeRole, config.recoveryBypass) ||
            !automaticLaunchAllowed()) {
            uncover(); return // Google TV is meant to be seen, e.g. the owner opened it through recovery.
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastRedirect < 900L) return
        lastRedirect = now
        if (!openLauncher()) uncover() // Leave Google TV available.
    }
    override fun onInterrupt() = Unit
    companion object {
        private const val KAYO = "au.com.kayosports.tv"
        private var active: WeakReference<HomeCompatibilityService>? = null
        fun consumePendingBootIfBound() { active?.get()?.takeIf { it.bound }?.consumePendingBoot() }
    }
}
