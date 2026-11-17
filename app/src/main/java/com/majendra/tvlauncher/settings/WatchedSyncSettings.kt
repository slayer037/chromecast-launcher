package com.majendra.tvlauncher.settings
import android.content.Context
import android.app.AlertDialog
object WatchedSyncSettings {
    fun show(context: Context) {
        AlertDialog.Builder(context).setTitle("Local watched list").setMessage("Watched choices stay on this device. Cloud sync and phone access are unavailable in this edition.").setPositiveButton("Close", null).show()
    }
}
