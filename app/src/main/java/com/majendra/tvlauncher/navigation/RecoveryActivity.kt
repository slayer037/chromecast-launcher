package com.majendra.tvlauncher.navigation

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.majendra.tvlauncher.settings.HomeCompatibility

/** Explicit recovery entry point; never changes the system HOME role. */
class RecoveryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HomeCompatibility(this).bypassForRecovery()
        try { startActivity(HomeCompatibility.googleHomeIntent()) }
        catch (_: android.content.ActivityNotFoundException) { Toast.makeText(this, "Original Google TV is unavailable", Toast.LENGTH_LONG).show() }
        catch (_: SecurityException) { Toast.makeText(this, "Android could not open Google TV", Toast.LENGTH_LONG).show() }
        finish()
    }
}
