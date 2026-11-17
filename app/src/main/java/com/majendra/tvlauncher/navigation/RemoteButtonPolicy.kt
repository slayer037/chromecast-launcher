package com.majendra.tvlauncher.navigation

/**
 * TCL remote app buttons (BT_RC833A_B2) arrive as vendor key codes. The ones listed here open a
 * launcher section instead of their app; YouTube and TCL keep the firmware's own behaviour.
 */
class RemoteButtonPolicy {
    private data class Press(val deviceId: Int, val downTime: Long)
    private val decisions = mutableMapOf<Press, Boolean>()

    /** One decision per press; its repeats and release follow it so the original app never opens. */
    fun down(keyCode: Int, deviceId: Int, downTime: Long, repeatCount: Int,
             allowed: () -> Boolean, open: (String) -> Boolean): Boolean {
        val section = sectionFor(keyCode) ?: return false
        val press = Press(deviceId, downTime)
        decisions[press]?.let { return it }
        if (repeatCount != 0) return false
        decisions.keys.removeAll { it.deviceId == deviceId }
        val consumed = allowed() && open(section)
        decisions[press] = consumed
        return consumed
    }

    fun up(keyCode: Int, deviceId: Int, downTime: Long): Boolean =
        sectionFor(keyCode) != null && (decisions.remove(Press(deviceId, downTime)) ?: false)

    companion object {
        const val NETFLIX = 4062
        const val PRIME_VIDEO = 4099
        const val STAN = 4136
        const val DISNEY_PLUS = 4141
        private const val APP = "app:"
        // Stan is unused here, so its button opens the Kayo app itself rather than the Kayo section.
        private val sections = mapOf(NETFLIX to "movies", PRIME_VIDEO to "series", STAN to "${APP}au.com.kayosports.tv", DISNEY_PLUS to "kids")
        fun sectionFor(keyCode: Int): String? = sections[keyCode]
        /** The package to launch when a button targets an app instead of a launcher section. */
        fun appPackage(target: String): String? = target.removePrefix(APP).takeIf { target.startsWith(APP) }
        fun eligible(enabled: Boolean, recoveryBypass: Boolean, keyguardLocked: Boolean) =
            enabled && !recoveryBypass && !keyguardLocked
    }
}
