package com.majendra.tvlauncher.data

import android.content.Context
import com.majendra.tvlauncher.R

/** Selected by the APK variant; the default variant keeps its existing performance settings. */
val Context.lightweightLauncher: Boolean get() = resources.getBoolean(R.bool.lightweight_launcher)
