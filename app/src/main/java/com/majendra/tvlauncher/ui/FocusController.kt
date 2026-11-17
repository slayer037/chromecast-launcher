package com.majendra.tvlauncher.ui

import android.view.View
import android.content.Context
import android.widget.LinearLayout

/** Focus belongs to an actual view subtree, rather than a screen-coordinate region. */
object FocusController {
    fun isWithin(root: View, view: View?): Boolean {
        var current = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

}

/** Up/Down stay in their pane; Left/Right retain the normal explicit pane handoff. */
class FocusBoundaryLayout(context: Context) : LinearLayout(context) {
    var sidebarBoundary: View? = null

    override fun focusSearch(focused: View, direction: Int): View? {
        val candidate = super.focusSearch(focused, direction)
        val sidebar = sidebarBoundary ?: return candidate
        fun zone(view: View?) = when {
            view == null || !FocusController.isWithin(this, view) -> FocusZone.OUTSIDE
            FocusController.isWithin(sidebar, view) -> FocusZone.SIDEBAR
            else -> FocusZone.CONTENT
        }
        return if (FocusBoundaryPolicy.allow(
                direction == View.FOCUS_UP || direction == View.FOCUS_DOWN,
                zone(focused), zone(candidate))) candidate else focused
    }
}
