package com.majendra.tvlauncher.settings

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast

/**
 * Direct shortcuts to the TV's own settings screens. Google TV's home was the usual way in to these; with it
 * disabled this list is that way in, so it must not depend on anything else in the launcher working.
 */
object SystemSettings {
    data class Entry(val label: String, val action: String, val packageUri: String? = null)

    /** Every action was checked against the TCL's settings app; ones a TV lacks are hidden by [available]. */
    val entries = listOf(
        Entry("All TV settings", "android.settings.SETTINGS"),
        Entry("TCL dashboard", "com.tcl.dashboard.MAIN"),
        Entry("Network & internet", "android.settings.WIFI_SETTINGS"),
        Entry("Picture", "android.settings.DISPLAY_SETTINGS"),
        Entry("Sound", "android.settings.SOUND_SETTINGS"),
        Entry("Apps", "android.settings.MANAGE_APPLICATIONS_SETTINGS"),
        Entry("Pair a remote or accessory", "com.google.android.intent.action.CONNECT_INPUT"),
        Entry("Add an account", "android.settings.ADD_ACCOUNT_SETTINGS"),
        Entry("Accessibility", "android.settings.ACCESSIBILITY_SETTINGS"),
        Entry("Date & time", "android.settings.DATE_SETTINGS"),
        Entry("Language", "android.settings.LOCALE_SETTINGS"),
        Entry("Keyboard", "android.settings.INPUT_METHOD_SETTINGS"),
        Entry("Storage & reset", "android.settings.INTERNAL_STORAGE_SETTINGS"),
        Entry("About this TV", "android.settings.DEVICE_INFO_SETTINGS"),
        Entry("Default home app", "android.settings.HOME_SETTINGS"),
        Entry("Developer options", "android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
        // Where Google TV's home is switched back on (Enable) without a computer.
        Entry("Google TV home app", "android.settings.APPLICATION_DETAILS_SETTINGS", HomeCompatibility.GOOGLE_PACKAGE))

    /** Packages that answer a settings action without showing a settings screen: the chooser and the TV stubs. */
    private val placeholders = setOf("android", "com.android.tv.frameworkpackagestubs")

    /** [handler] gives the package that would open an entry, or null when nothing would. */
    fun available(handler: (Entry) -> String?): List<Entry> =
        entries.filter { entry -> handler(entry)?.let { it !in placeholders } == true }

    fun intent(entry: Entry): Intent = Intent(entry.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply { if (entry.packageUri != null) data = Uri.fromParts("package", entry.packageUri, null) }

    fun available(context: Context): List<Entry> = available { entry ->
        try { context.packageManager.resolveActivity(intent(entry), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
        catch (_: RuntimeException) { null }
    }

    fun open(context: Context, entry: Entry): Boolean =
        try { context.startActivity(intent(entry)); true }
        catch (_: android.content.ActivityNotFoundException) { false }
        catch (_: SecurityException) { false }

    fun show(context: Context) {
        val list = available(context)
        if (list.isEmpty()) { open(context, entries.first()); return }
        AlertDialog.Builder(context).setTitle("TV settings").setItems(list.map { it.label }.toTypedArray()) { _, index ->
            if (!open(context, list[index])) Toast.makeText(context, "${list[index].label} is unavailable on this TV", Toast.LENGTH_LONG).show()
        }.setNegativeButton("Close", null).show()
    }

    /** False when Google TV's home is disabled or absent, so nothing should offer to open it. */
    fun googleHomeAvailable(context: Context): Boolean =
        try { context.packageManager.getApplicationInfo(HomeCompatibility.GOOGLE_PACKAGE, 0).enabled }
        catch (_: PackageManager.NameNotFoundException) { false }
}
