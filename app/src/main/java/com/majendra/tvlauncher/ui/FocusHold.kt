package com.majendra.tvlauncher.ui

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView

/**
 * When a refresh removes the focused card or row, Android hands focus to the first focusable view
 * in the window: the sidebar's Stremio entry, which then opens Stremio. Parking focus on the list
 * itself across the update keeps it in place until the caller focuses the replacement card.
 */
object FocusHold {
    fun hold(list: RecyclerView) {
        if (list.isFocused) return
        list.isFocusable = true
        list.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        list.requestFocus()
        list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        // Stop being a focus target as soon as anything else (normally the replacement card) takes focus.
        list.viewTreeObserver.addOnGlobalFocusChangeListener(object : ViewTreeObserver.OnGlobalFocusChangeListener {
            override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
                if (newFocus === list) return
                list.isFocusable = false
                if (list.viewTreeObserver.isAlive) list.viewTreeObserver.removeOnGlobalFocusChangeListener(this)
            }
        })
    }
}
