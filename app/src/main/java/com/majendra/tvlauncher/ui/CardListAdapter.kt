package com.majendra.tvlauncher.ui

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.majendra.tvlauncher.data.ContentItem

/** A row's cards: stable ids and refreshes that keep the focused card and scroll position in place. */
abstract class CardListAdapter<H : RecyclerView.ViewHolder>(protected var items: List<ContentItem>) : RecyclerView.Adapter<H>() {
    private val stableIds = mutableMapOf<String, Long>()
    private var nextId = 0L
    internal var navigationGeneration = ContentNavigationGeneration()
    private val recovery = FocusRecoveryPolicy()
    init { setHasStableIds(true) }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recovery.cancel()
        super.onDetachedFromRecyclerView(recyclerView)
    }
    protected fun key(item: ContentItem) = "${item.source}:${item.id}"
    override fun getItemId(position: Int) = stableIds.getOrPut(key(items[position])) { nextId++ }
    override fun getItemCount() = items.size

    fun submit(value: List<ContentItem>, list: RecyclerView) {
        if (items == value) return
        val old = items
        val structureChanged = CardUpdatePolicy.requiresAnchorRestore(old, value)
        val ticket = recovery.changed(structureChanged, navigationGeneration.value)
        val parked = recovery.parkedAnchor(navigationGeneration.value, list.isFocused)
        val focused = list.focusedChild?.let { list.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION } ?: parked?.position ?: RecyclerView.NO_POSITION
        val focusKey = if (parked != null) parked.key else old.getOrNull(focused)?.let(::key)
        if (ticket != null && focusKey != null) recovery.remember(focusKey, focused, navigationGeneration.value)
        val manager = list.layoutManager as LinearLayoutManager
        val anchor = manager.findFirstVisibleItemPosition()
        val anchorKey = old.getOrNull(anchor)?.let(::key)
        val offset = manager.findViewByPosition(anchor)?.let { it.left - list.paddingLeft } ?: 0
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = value.size
            override fun areItemsTheSame(a: Int, b: Int) = key(old[a]) == key(value[b])
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == value[b]
        })
        // Removing the focused card would otherwise send focus to the sidebar (see FocusHold).
        if (focusKey != null && value.isNotEmpty() && value.none { key(it) == focusKey }) FocusHold.hold(list)
        items = value
        diff.dispatchUpdatesTo(this)
        val retainedAnchor = value.indexOfFirst { key(it) == anchorKey }
        // Metadata-only refreshes retain the same positions; forcing a scroll schedules an
        // unnecessary layout while the user is moving focus through the row.
        if (structureChanged && retainedAnchor >= 0) manager.scrollToPositionWithOffset(retainedAnchor, offset)
        if (ticket != null && focusKey != null && value.isNotEmpty()) {
            val retained = value.indexOfFirst { key(it) == focusKey }
            val target = if (retained >= 0) retained else focused.coerceIn(value.indices)
            // Metadata-only updates need no repair. A structural repair must yield to newer navigation.
            fun allowed() = recovery.allows(ticket, navigationGeneration.value,
                list.adapter === this && list.hasFocus() && list.hasWindowFocus())
            list.post {
                if (allowed() && list.findViewHolderForAdapterPosition(target)?.itemView?.hasFocus() != true) {
                    if (list.findViewHolderForAdapterPosition(target) == null) manager.scrollToPosition(target)
                    list.post { if (allowed() && list.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus() == true)
                        recovery.completed() }
                }
            }
        }
    }
}
