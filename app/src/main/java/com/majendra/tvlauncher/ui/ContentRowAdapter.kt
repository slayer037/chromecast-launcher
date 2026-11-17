package com.majendra.tvlauncher.ui

import android.graphics.Typeface
import android.text.TextUtils
import android.view.ViewGroup
import android.view.View
import android.os.Parcelable
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.data.lightweightLauncher

class ContentRowAdapter(private val onItem: (ContentItem) -> Unit, private val onLeft: () -> Unit, private val onFocused:(String,ContentItem)->Unit, private val onDetails:(ContentItem)->Unit,
    private val navigationGeneration: ContentNavigationGeneration = ContentNavigationGeneration()) : RecyclerView.Adapter<ContentRowAdapter.Holder>() {
    private var rows: List<ContentRow> = emptyList()
    private val stableIds = mutableMapOf<String, Long>()
    private val scrollStates = mutableMapOf<String, Parcelable?>()
    private var nextId = 0L
    private var list: RecyclerView? = null
    private var streaming = false
    private val recovery = FocusRecoveryPolicy()
    // Cards are reused across rows instead of each row inflating its own set while scrolling.
    private val cardPool = RecyclerView.RecycledViewPool().apply { CardStyle.entries.forEach { setMaxRecycledViews(it.ordinal, 24) } }
    private val warming = mutableSetOf<CardStyle>()
    init { setHasStableIds(true) }
    /**
     * Bedroom builds spare cards while the remote is idle. Measured on the Chromecast on 12 November 2026, a move into
     * a row whose cards had to be built in that frame took 130 to 170 ms; with cards waiting it only fills them in.
     * A pooled card holds no artwork, and the pool is only topped up after new rows have drawn from it.
     */
    private fun warmCards(style: CardStyle) {
        val parent = list ?: return
        if (!parent.context.lightweightLauncher || style == CardStyle.SCORE || !warming.add(style)) return
        val spare = ContentCardAdapter(emptyList(), style, {}, {}, {}, {})
        android.os.Looper.myQueue().addIdleHandler {
            val target = list
            // A list that has left its window belongs to a screen being torn down: stop, and let it be collected.
            val more = target != null && target.isAttachedToWindow && cardPool.getRecycledViewCount(style.ordinal) < SPARE_CARDS
            // One card per idle pass, so a key press never waits behind a batch.
            if (more) cardPool.putRecycledView(spare.createViewHolder(target!!, style.ordinal)) else warming.remove(style)
            more
        }
    }
    override fun getItemId(position: Int) = stableIds.getOrPut(rows[position].id) { nextId++ }
    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) { list = recyclerView }
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) { recovery.cancel(); list = null }
    fun setStreamingMode(enabled: Boolean) {
        if (streaming == enabled) return
        streaming = enabled
        if (itemCount > 0) {
            val parent=list
            if(parent?.isComputingLayout==true)parent.post { if(itemCount>0)notifyItemRangeChanged(0,itemCount) }
            else notifyItemRangeChanged(0, itemCount)
        }
    }

    fun submit(value: List<ContentRow>) {
        val nonempty = value.filter { it.items.isNotEmpty() || !it.emptyMessage.isNullOrBlank() }
        if (rows == nonempty) return
        val old = rows
        val parent = list
        val parked = recovery.parkedAnchor(navigationGeneration.value, parent?.isFocused == true)
        val focus = parent?.focusedChild?.let { parent.getChildAdapterPosition(it) }
            ?.takeIf { it != RecyclerView.NO_POSITION } ?: parked?.position ?: RecyclerView.NO_POSITION
        val focusedRow = parked?.key ?: old.getOrNull(focus)?.id
        val removedFocusedRow = focusedRow != null && nonempty.none { it.id == focusedRow && it.items.isNotEmpty() }
        val repair = removedFocusedRow || parked != null
        val structureChanged = old.map { it.id } != nonempty.map { it.id }
        // Framework focus may already be on a real replacement card. An old positional repair
        // cannot survive a later row reorder once there is no parked focus left to repair.
        if (!repair && structureChanged) recovery.cancel()
        val ticket = recovery.changed(repair, navigationGeneration.value)
        if (ticket != null && focusedRow != null) recovery.remember(focusedRow, focus, navigationGeneration.value)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = nonempty.size
            override fun areItemsTheSame(a: Int, b: Int) = old[a].id == nonempty[b].id
            override fun areContentsTheSame(a: Int, b: Int) = old[a] == nonempty[b]
        })
        // Removing the focused row would otherwise send focus to the sidebar (see FocusHold).
        if (parent != null && focusedRow != null && nonempty.any { it.items.isNotEmpty() } &&
            nonempty.none { it.id == focusedRow && it.items.isNotEmpty() }) FocusHold.hold(parent)
        rows = nonempty
        diff.dispatchUpdatesTo(this)
        if(parent?.hasFocus()!=true && old.firstOrNull()?.id!=nonempty.firstOrNull()?.id)
            (parent?.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(0,0)
        if (repair) {
            val candidates=nonempty.indices.filter { nonempty[it].items.isNotEmpty() }
            if (candidates.isEmpty()) onLeft()
            else {
                val target = candidates.firstOrNull { nonempty[it].id == focusedRow }
                    ?: candidates.minBy { kotlin.math.abs(it-focus) }
                parent?.scrollToPosition(target)
                // The row may need one more layout pass after the scroll before its cards exist.
                if (parent != null && ticket != null) {
                    fun allowed() = recovery.allows(ticket, navigationGeneration.value,
                        parent.adapter === this && parent.hasFocus() && parent.hasWindowFocus())
                    fun restore(): Boolean = allowed() &&
                        ((parent.findViewHolderForAdapterPosition(target) as? Holder)?.focusFirst() == true).also { if (it) recovery.completed() }
                    parent.post { if (allowed() && !restore()) parent.post { restore() } }
                }
            }
        }
    }

    override fun getItemCount() = rows.size
    fun firstContentPosition(): Int = rows.indexOfFirst { it.items.isNotEmpty() }
    fun itemAt(position:Pair<Int,Int>):ContentItem? = rows.getOrNull(position.first)?.items?.getOrNull(position.second)
    fun rowIdAt(row:Int):String? = rows.getOrNull(row)?.id
    fun contentItem(remembered:String?,rememberedRow:String?=null):ContentItem? = contentPosition(remembered,rememberedRow)?.let(::itemAt)
    fun contentPosition(remembered:String?,rememberedRow:String?=null):Pair<Int,Int>? = ContentFocusPolicy.position(rows,remembered,rememberedRow)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(0, context.dp(4), 0, context.dp(14))
            isFocusable = false
            clipChildren = false
            clipToPadding = false
        }
        val title = Theme.label(context, 18f, true).apply {
            setPadding(context.dp(4), 0, 0, context.dp(8))
        }
        val cards = RowCards(context).apply {
            layoutManager = CardScrollLayoutManager(context).apply {
                initialPrefetchItemCount = if(context.lightweightLauncher)1 else 5
            }
            setRecycledViewPool(cardPool)
            clipToPadding = false
            clipChildren = false
            // No right padding: rows run to the screen edge so the next card peeks in.
            setPadding(context.dp(4), context.dp(4), 0, context.dp(8))
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            itemAnimator = null
            setItemViewCacheSize(if(context.lightweightLauncher)3 else 5)
        }
        val empty = Theme.label(context,13f).apply {
            setTextColor(Theme.muted)
            setPadding(context.dp(4),context.dp(2),context.dp(16),context.dp(4))
            maxLines=3
            isFocusable=false
            visibility=View.GONE
        }
        root.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(empty,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(cards, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(238)))
        return Holder(root, title, cards,empty)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        val kids = row.items.firstOrNull()?.source == "kids" || row.id.startsWith("kids-")
        // Everything below depends only on these two flags; a rebind with the same flags leaves the views as they are.
        val configure = holder.configuredKids != kids || holder.configuredStreaming != streaming
        if (configure) {
            holder.title.setTextColor(if (kids) KidsTheme.navy else Theme.text)
            holder.title.typeface = Typeface.create(if (kids) "sans-serif-rounded" else "sans-serif", Typeface.BOLD)
            holder.empty.setTextColor(if (kids) KidsTheme.navy else Theme.muted)
            holder.title.textSize = if (kids) 22f else if (streaming) 17f else 18f
            (holder.itemView as LinearLayout).setPadding(0,holder.itemView.context.dp(4),0,holder.itemView.context.dp(if(streaming)8 else 14))
            holder.configuredKids = kids
            holder.configuredStreaming = streaming
        }
        if (!TextUtils.equals(holder.title.text,row.title)) holder.title.text = row.title
        // Filtered results are one long grid: only its first row carries a heading.
        val titled = if (row.title.isEmpty()) View.GONE else View.VISIBLE
        if (holder.title.visibility != titled) holder.title.visibility = titled
        val empty=row.items.isEmpty()
        if (!TextUtils.equals(holder.empty.text,row.emptyMessage.orEmpty())) holder.empty.text=row.emptyMessage.orEmpty()
        holder.empty.visibility=if(empty)View.VISIBLE else View.GONE
        holder.cards.visibility=if(empty)View.GONE else View.VISIBLE
        if(empty) {
            holder.rowId?.let { scrollStates[it]=holder.cards.layoutManager?.onSaveInstanceState() }
            holder.cards.adapter=null
            holder.rowId=row.id
            return
        }
        holder.cards.contentDescription = row.title
        val first=row.items.firstOrNull()
        val style = CardStyle.of(row, streaming)
        // A kids live hero card outside the small live row carries an extra "Up next" line.
        val height = holder.cards.context.dp(style.rowDp + if (kids && style != CardStyle.KIDS_LIVE && first?.metadata?.get("type") == "channel") 20 else 0)
        val layout = holder.cards.layoutParams as LinearLayout.LayoutParams
        if (layout.height != height) {
            layout.height = height
            holder.cards.layoutParams = layout
        }
        if (holder.rowId == row.id && holder.style == style && holder.cards.adapter is CardListAdapter<*>) {
            (holder.cards.adapter as CardListAdapter<*>).submit(row.items, holder.cards)
        } else {
            holder.rowId?.let { scrollStates[it] = holder.cards.layoutManager?.onSaveInstanceState() }
            holder.rowId = row.id
            holder.style = style
            // An adapter serves one row for its whole life (see the rowId check above), so it can name that row.
            val rowId = row.id
            val focused: (ContentItem) -> Unit = { item -> onFocused(rowId, item) }
            holder.cards.adapter = (if (style == CardStyle.SCORE) ScoreCardAdapter(row.items, onItem, onLeft, focused, onDetails)
                else ContentCardAdapter(row.items, style, onItem, onLeft, focused, onDetails)).also {
                    it.navigationGeneration = navigationGeneration
                }
            holder.cards.layoutManager?.onRestoreInstanceState(scrollStates[row.id])
        }
        warmCards(style)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.rowId?.let { scrollStates[it] = holder.cards.layoutManager?.onSaveInstanceState() }
        super.onViewRecycled(holder)
    }

    /**
     * Up/Down into a row lands on the fully visible card nearest the current column, so the
     * row never slides sideways to reveal a half-visible edge card.
     */
    class RowCards(context: android.content.Context) : RecyclerView(context) {
        override fun addFocusables(views: java.util.ArrayList<View>, direction: Int, focusableMode: Int) {
            val target = nearestVisibleCard(direction) ?: return super.addFocusables(views, direction, focusableMode)
            views.add(target)
        }
        // A fast press can reach a row that was not laid out when focus was searched; the default
        // then focuses the last attached card (FOCUS_UP walks children backwards) and slides the row.
        override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: android.graphics.Rect?): Boolean =
            nearestVisibleCard(direction)?.requestFocus() == true || super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
        private val coordinates = IntArray(2)
        private fun nearestVisibleCard(direction: Int): View? {
            if (direction != View.FOCUS_UP && direction != View.FOCUS_DOWN) return null
            val focused = rootView?.findFocus() ?: return null
            val manager = layoutManager as? LinearLayoutManager ?: return null
            if (isAncestorOf(focused)) return null
            val first = manager.findFirstCompletelyVisibleItemPosition()
            val last = manager.findLastCompletelyVisibleItemPosition()
            if (first == NO_POSITION || last == NO_POSITION) return null
            val column = centreX(focused)
            var nearest: View? = null
            var distance = Int.MAX_VALUE
            for (position in first..last) {
                val card = manager.findViewByPosition(position) ?: continue
                if (!card.isFocusable) continue
                val candidate = kotlin.math.abs(centreX(card) - column)
                if (candidate < distance) { nearest = card; distance = candidate }
            }
            return nearest
        }
        private fun isAncestorOf(view: View): Boolean {
            var parent = view.parent
            while (parent != null) { if (parent === this) return true; parent = parent.parent }
            return false
        }
        private fun centreX(view: View): Int {
            view.getLocationOnScreen(coordinates)
            return coordinates[0] + view.width / 2
        }
    }

    class Holder(root: LinearLayout, val title: TextView, val cards: RecyclerView, val empty:TextView) : RecyclerView.ViewHolder(root) {
        var configuredKids: Boolean? = null
        var configuredStreaming: Boolean? = null
        var rowId: String? = null
        var style: CardStyle? = null
        fun focusItem(position:Int,stillPending:()->Boolean):Boolean {
            val view=cards.findViewHolderForAdapterPosition(position)?.itemView
            if(view!=null)return view.requestFocus()
            cards.scrollToPosition(position)
            cards.post { if(stillPending())cards.findViewHolderForAdapterPosition(position)?.itemView?.requestFocus() }
            return false
        }
        fun focusFirst(): Boolean = cards.visibility==View.VISIBLE && (cards.getChildAt(0)?.requestFocus() ?: false)
    }

    private companion object {
        /** A row's worth of posters across the screen, plus the few its own cache keeps. */
        const val SPARE_CARDS = 12
    }
}

/** Bedroom favours immediate D-pad response over animated horizontal travel. */
private class CardScrollLayoutManager(context: android.content.Context) :
    LinearLayoutManager(context, RecyclerView.HORIZONTAL, false) {
    private val immediateFocusScroll = context.lightweightLauncher

    override fun requestChildRectangleOnScreen(parent: RecyclerView, child: View,
        rect: android.graphics.Rect, immediate: Boolean, focusedChildVisible: Boolean): Boolean =
        super.requestChildRectangleOnScreen(parent, child, rect,
            immediate || immediateFocusScroll, focusedChildVisible)
}
