package com.majendra.tvlauncher.navigation

import android.content.Context

/**
 * Back while watching a channel the launcher opened returns to the launcher (and the card it came from).
 * TCL TV's own Back switches to the previous channel. TCL's menus are separate activities (e.g. the
 * channel list), so Back still closes those normally.
 */
object LiveBackPolicy {
    const val TV_PACKAGE = "com.tcl.tv"
    const val PLAYER = "com.tcl.player.TVActivity"
    fun shouldReturn(allowed: Boolean, armed: Boolean, foregroundPackage: String?, foregroundClass: String?, repeatCount: Int) =
        allowed && armed && repeatCount == 0 && foregroundPackage == TV_PACKAGE && foregroundClass == PLAYER

    private fun prefs(context: Context) = context.getSharedPreferences("live_back", Context.MODE_PRIVATE)
    /** Armed when the launcher hands off to TCL TV; disarmed whenever the launcher is back on screen. */
    fun arm(context: Context, armed: Boolean) = prefs(context).edit().putBoolean("armed", armed).apply()
    fun armed(context: Context) = prefs(context).getBoolean("armed", false)
}
