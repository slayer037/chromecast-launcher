package com.majendra.tvlauncher.navigation

/** Retains one decision per HOME press, including repeats and its matching release. */
class HomeKeyPolicy {
    private data class Press(val deviceId: Int, val downTime: Long)
    private val decisions = mutableMapOf<Press, Boolean>()

    fun down(deviceId: Int, downTime: Long, repeatCount: Int,
             allowed: () -> Boolean, launch: () -> Boolean): Boolean {
        val press = Press(deviceId, downTime)
        decisions[press]?.let { return it }
        // A service binding midway through a held key must never consume its release.
        if (repeatCount != 0) return false
        // Discard a missing release from this device; keep independent remote streams intact.
        decisions.keys.removeAll { it.deviceId == deviceId }
        val consumed = allowed() && launch()
        decisions[press] = consumed
        return consumed
    }

    fun up(deviceId: Int, downTime: Long): Boolean =
        decisions.remove(Press(deviceId, downTime)) ?: false

    companion object {
        fun eligible(enabled: Boolean, ownsHome: Boolean, recoveryBypass: Boolean,
                     keyguardLocked: Boolean): Boolean =
            enabled && ownsHome && !recoveryBypass && !keyguardLocked
    }
}
