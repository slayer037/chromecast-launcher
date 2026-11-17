package com.majendra.tvlauncher.ui

enum class LeftPressAction { MOVE, STAY, EXIT }

/** A fresh Left at the first card returns to the menu; arrival and held repeats do not. */
class RowExitPressPolicy {
    private var lastPress: Pair<Int, Long>? = null
    private var exitPress: Pair<Int, Long>? = null

    fun down(firstCardRow: String?, deviceId: Int, downTime: Long, repeatCount: Int): LeftPressAction {
        val press = deviceId to downTime
        if (exitPress == press) return LeftPressAction.STAY
        if (repeatCount != 0) return if (firstCardRow == null) LeftPressAction.MOVE else LeftPressAction.STAY
        if (lastPress == press) return LeftPressAction.STAY
        lastPress = press
        if (firstCardRow == null) return LeftPressAction.MOVE
        exitPress = press
        return LeftPressAction.EXIT
    }

    fun up(deviceId: Int, downTime: Long): Boolean {
        if (exitPress != (deviceId to downTime)) return false
        exitPress = null
        return true
    }
}

/** Repeat events from one held remote button do not become additional sidebar steps. */
class SidebarPressPolicy {
    private var lastPress: Pair<Int, Long>? = null

    fun accept(keyCode: Int, downTime: Long, repeatCount: Int): Boolean {
        if (repeatCount != 0) return false
        val press = keyCode to downTime
        if (lastPress == press) return false
        lastPress = press
        return true
    }

    fun target(current: Int, size: Int, step: Int): Int? =
        if (current !in 0 until size || step !in setOf(-1, 1)) null
        else (current + step).coerceIn(0, size - 1)
}
