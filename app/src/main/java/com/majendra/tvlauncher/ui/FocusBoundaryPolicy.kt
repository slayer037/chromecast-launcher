package com.majendra.tvlauncher.ui

enum class FocusZone { SIDEBAR, CONTENT, OUTSIDE }

object FocusBoundaryPolicy {
    fun allow(vertical: Boolean, from: FocusZone, to: FocusZone): Boolean =
        !vertical || from == FocusZone.OUTSIDE || from == to
}
