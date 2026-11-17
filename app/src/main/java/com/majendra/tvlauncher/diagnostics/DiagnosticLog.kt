package com.majendra.tvlauncher.diagnostics

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object DiagnosticLog {
    // Callers include the UI thread at startup and at every app handoff: they only queue. One thread owns the file.
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "diagnostic-log").apply { isDaemon = true } }
    private var journal: EventJournal? = null // Writer thread only.

    fun event(context: Context, type: String, fields: Map<String, Any?> = emptyMap()) {
        val entry = JSONObject().put("at", System.currentTimeMillis()).put("type", type)
        fields.forEach { (key, value) -> entry.put(key, value ?: JSONObject.NULL) }
        val text = entry.toString()
        Log.i("CHROMECAST_LAUNCHER", text)
        val app = context.applicationContext ?: context
        writer.execute {
            try { (journal ?: EventJournal(File(app.filesDir, "events.json")).also { journal = it }).append(text) }
            catch (_: Exception) { /* Diagnostics never affect the launcher. */ }
        }
    }
}
