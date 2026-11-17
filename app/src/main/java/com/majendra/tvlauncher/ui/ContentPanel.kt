package com.majendra.tvlauncher.ui

import android.content.Context
import android.view.KeyEvent
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.FeedResult
import com.majendra.tvlauncher.data.lightweightLauncher

/** [onFocused] receives the id of the row the card sits in as well as the card. */
class ContentPanel(context:Context,onItem:(ContentItem)->Unit,onLeft:()->Unit,onFocused:(String,ContentItem)->Unit,onDetails:(ContentItem)->Unit):FrameLayout(context) {
    private var pendingFocus=false
    private var focusGeneration=0L
    private var remembered:String?=null
    private var rememberedRow:String?=null
    private val navigationGeneration=ContentNavigationGeneration()
    private val rows=ContentRowAdapter(onItem,onLeft,{row,item->acceptFocused(row,item,onFocused)},onDetails,navigationGeneration)
    private val list=RecyclerView(context).apply {
        id=android.view.View.generateViewId();layoutManager=RowSnapLayoutManager(context);adapter=rows
        clipToPadding=false;setPadding(context.dp(4),context.dp(4),0,context.dp(24))
        descendantFocusability=FOCUS_AFTER_DESCENDANTS;isFocusable=false;itemAnimator=null;setItemViewCacheSize(if(context.lightweightLauncher)2 else 4)
        contentDescription="Content rows"
    }
    init {addView(list,LayoutParams(-1,-1));list.addOnLayoutChangeListener {_,_,_,_,_,_,_,_,_->if(pendingFocus)list.post{tryFocusContent()}}}
    fun setStreamingMode(enabled:Boolean) {
        // The screen behind already paints the streaming colour edge to edge; a second fill here was pure overdraw.
        rows.setStreamingMode(enabled)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        // Room below the last row so every row, including the final ones, can snap to the top.
        val bottom=maxOf(context.dp(24),h-context.dp(280))
        if(bottom!=list.paddingBottom)post{list.setPadding(list.paddingLeft,list.paddingTop,list.paddingRight,bottom)}
    }
    fun render(result:FeedResult){if(pendingFocus)focusGeneration++;rows.submit(result.rows);if(pendingFocus)list.post{tryFocusContent()}}
    fun cancelPendingFocus(){pendingFocus=false;focusGeneration++;navigationGeneration.advance()}
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(event.action==KeyEvent.ACTION_DOWN && event.keyCode in setOf(
                KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
            // A delayed layout/refresh must never pull focus back after the user has moved on.
            cancelPendingFocus()
        }
        return super.dispatchKeyEvent(event)
    }
    /** Adapter position, rather than the first visible card, defines the left edge. */
    fun focusedFirstCardRow():String? {
        val row=list.focusedChild?:return null
        val holder=list.getChildViewHolder(row) as? ContentRowAdapter.Holder?:return null
        val card=holder.cards.focusedChild?:return null
        return holder.rowId?.takeIf {holder.cards.getChildAdapterPosition(card)==0}
    }
    private fun acceptFocused(row:String,item:ContentItem,onFocused:(String,ContentItem)->Unit) {
        if(pendingFocus) {
            val position=rows.contentPosition(remembered,rememberedRow)
            val target=position?.let(rows::itemAt)
            if(position==null||target==null){cancelPendingFocus();return}
            // RecyclerView can briefly focus another card while laying out the saved target.
            // Do not let that transient focus replace the remembered choice or cancel restoration.
            // The same title in another row is also not the target.
            if(target.id!=item.id || target.source!=item.source || rows.rowIdAt(position.first)!=row){list.post{tryFocusContent()};return}
            pendingFocus=false
        }
        onFocused(row,item)
    }
    fun focusContent(itemId:String?=null,rowId:String?=null):Boolean {
        remembered=itemId;rememberedRow=rowId;pendingFocus=true;focusGeneration++
        val target=rows.contentPosition(remembered,rememberedRow)?:return false
        if(tryFocusContent())return true
        list.scrollToPosition(target.first);list.post{tryFocusContent()};return true
    }
    private fun tryFocusContent():Boolean {
        if(!pendingFocus)return false
        val target=rows.contentPosition(remembered,rememberedRow)?:return false
        val holder=list.findViewHolderForAdapterPosition(target.first) as? ContentRowAdapter.Holder
        val generation=focusGeneration
        if(holder?.focusItem(target.second){pendingFocus&&focusGeneration==generation}==true){pendingFocus=false;return true}
        return false
    }
}

/** Aligns the focused row's heading with the top of the pane so no row sits half-scrolled under the controls above. */
private class RowSnapLayoutManager(context:Context):LinearLayoutManager(context) {
    override fun requestChildRectangleOnScreen(parent:RecyclerView,child:android.view.View,rect:android.graphics.Rect,immediate:Boolean,focusedChildVisible:Boolean):Boolean {
        val dy=getDecoratedTop(child)-paddingTop
        if(dy==0)return false
        parent.stopScroll()
        // Complete one row alignment before another directional press can select its next destination.
        parent.scrollBy(0,dy)
        return true
    }
}

object ContentFocusPolicy {
    /**
     * Row and card to restore. The same film appears in several genre rows, so the remembered row wins;
     * a focus saved before rows were recorded ([row] null), or whose row has gone, falls back to the first
     * row holding the item, then to the same channel's current programme, then to the first card.
     */
    fun position(rows:List<com.majendra.tvlauncher.data.ContentRow>,item:String?,row:String?=null):Pair<Int,Int>? {
        if(item!=null) {
            val preferred=if(row==null)-1 else rows.indexOfFirst {it.id==row}
            if(preferred>=0)rows[preferred].items.indexOfFirst {it.id==item}.takeIf {it>=0}?.let {return preferred to it}
            rows.forEachIndexed { index, value -> value.items.indexOfFirst { it.id==item }.takeIf {it>=0}?.let { return index to it } }
            if(preferred>=0)rows[preferred].items.indexOfFirst {matchesChannel(it,item)}.takeIf {it>=0}?.let {return preferred to it}
            rows.forEachIndexed { index, value -> value.items.indexOfFirst { matchesChannel(it,item) }.takeIf {it>=0}?.let { return index to it } }
        }
        return rows.indexOfFirst { it.items.isNotEmpty() }.takeIf {it>=0}?.let {it to 0}
    }
    fun matchesChannel(item:ContentItem,remembered:String):Boolean {
        if(item.source !in setOf("kayo","live","7plus","9now","abc","sbs","10"))return false
        if(item.metadata["channelId"].isNullOrBlank() && item.metadata["channelName"].isNullOrBlank())return false
        return remembered.substringAfterLast(':').toLongOrNull()!=null &&
            item.id.substringAfterLast(':').toLongOrNull()!=null &&
            item.id.substringBeforeLast(':')==remembered.substringBeforeLast(':')
    }
}
