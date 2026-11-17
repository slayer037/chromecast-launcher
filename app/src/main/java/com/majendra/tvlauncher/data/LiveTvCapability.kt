package com.majendra.tvlauncher.data

import android.content.Context
import com.majendra.tvlauncher.R

val Context.liveTvEnabled: Boolean get() = resources.getBoolean(R.bool.live_tv_enabled)
