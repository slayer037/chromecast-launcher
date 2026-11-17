package com.majendra.tvlauncher.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.majendra.tvlauncher.diagnostics.DiagnosticLog
import org.json.JSONObject

/**
 * Diagnostics written on every feed refresh. `events.json` and `feed-<service>.json` are only reachable through
 * `run-as`, which needs a debuggable build, and each event rewrites the whole events file. Debuggable builds keep both
 * exactly as before; release builds keep the same line in logcat (tag CHROMECAST_LAUNCHER) and do no file work.
 */
internal object FeedDiagnostics {
    @Volatile private var debuggable: Boolean? = null

    fun exportable(context: Context): Boolean = debuggable
        ?: ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0).also { debuggable = it }

    fun event(context: Context, type: String, fields: Map<String, Any?> = emptyMap()) {
        if (exportable(context)) { DiagnosticLog.event(context, type, fields); return }
        val entry = JSONObject().put("at", System.currentTimeMillis()).put("type", type)
        fields.forEach { (key, value) -> entry.put(key, value ?: JSONObject.NULL) }
        Log.i("CHROMECAST_LAUNCHER", entry.toString())
    }
}
