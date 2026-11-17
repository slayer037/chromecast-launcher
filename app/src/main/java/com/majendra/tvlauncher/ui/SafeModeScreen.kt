package com.majendra.tvlauncher.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.majendra.tvlauncher.settings.SystemSettings

/**
 * What the launcher shows instead of itself after repeated crashes (see CrashGuard). Framework widgets only, and
 * nothing here reads a feed, a store or an image, so it works when the rest of the launcher does not.
 */
object SafeModeScreen {
    fun build(activity: Activity, retry: () -> Unit, clearAndRetry: () -> Unit): View {
        val pad = (activity.resources.displayMetrics.density * 48).toInt()
        fun text(value: String, size: Float) = TextView(activity).apply {
            text = value; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setPadding(0, 0, 0, pad / 3)
        }
        fun button(label: String, action: () -> Unit) = Button(activity).apply {
            text = label; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(pad * 9, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = pad / 6 }
        }
        val first = button("Try the launcher again", retry)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.BLACK); setPadding(pad, pad, pad, pad)
            addView(text("The launcher stopped unexpectedly", 28f))
            addView(text("It closed more than once in a few minutes, so it has opened this simple screen instead. " +
                "Your apps, settings, watched list and accounts are untouched.", 18f))
            addView(first)
            addView(button("Open an app") { showApps(activity) })
            addView(button("TV settings") { SystemSettings.show(activity) })
            addView(button("Clear saved feeds and artwork, then try again", clearAndRetry))
            post { first.requestFocus() }
        }
    }

    /** Every app with a TV launcher entry, straight from the package manager. */
    private fun showApps(activity: Activity) {
        val packages = activity.packageManager
        val apps = try {
            packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
                .filter { it.activityInfo.packageName != activity.packageName }
                .map { it.loadLabel(packages).toString() to it.activityInfo.packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
        } catch (_: RuntimeException) { emptyList() }
        if (apps.isEmpty()) { Toast.makeText(activity, "No apps were found", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(activity).setTitle("Open an app").setItems(apps.map { it.first }.toTypedArray()) { _, index ->
            val launch = packages.getLeanbackLaunchIntentForPackage(apps[index].second) ?: packages.getLaunchIntentForPackage(apps[index].second)
            try { if (launch != null) activity.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: RuntimeException) { Toast.makeText(activity, "${apps[index].first} could not be opened", Toast.LENGTH_LONG).show() }
        }.setNegativeButton("Close", null).show()
    }
}
