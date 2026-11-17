package com.majendra.tvlauncher.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.majendra.tvlauncher.data.lightweightLauncher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.*
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.Disposable
import coil.request.ImageRequest
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.providers.StremioMetadataPolicy

/** A remote-friendly preview; the long-press dialog exposes the entire description. */
class FocusedDetailsView(context: Context): LinearLayout(context) {
    private val art = ImageView(context)
    private val heading = Theme.label(context,19f,true).apply { maxLines=2 }
    private val subtitle = Theme.label(context,12f).apply { setTextColor(Theme.accent);maxLines=1 }
    private val summary = Theme.label(context,12f).apply { setTextColor(Theme.muted);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END }
    private var request:Disposable?=null
    private val artworkHandler=Handler(Looper.getMainLooper())
    private var pendingArtwork:Runnable?=null
    private var artworkGeneration=0L
    private var imageKey:String?=null
    private var shownItem:ContentItem?=null
    private var streaming=false
    private val text=LinearLayout(context).apply {orientation=VERTICAL}
    private val streamingArt=FrameLayout(context)
    private val imageShade=View(context).apply {
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background=android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.rgb(18,18,18),Color.TRANSPARENT))
    }
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        setPadding(context.dp(8),context.dp(6),context.dp(8),context.dp(8))
        art.scaleType=ImageView.ScaleType.FIT_CENTER
        addView(art,LayoutParams(context.dp(190),context.dp(106)).apply {rightMargin=context.dp(18)})
        text.addView(heading);text.addView(subtitle);text.addView(summary)
        addView(text,LayoutParams(0,-2,1f));visibility=GONE
    }
    fun setStreamingMode(enabled:Boolean) {
        if(streaming==enabled)return
        streaming=enabled
        removeView(art)
        removeView(streamingArt)
        streamingArt.removeAllViews()
        val params=LayoutParams(context.dp(if(enabled)216 else 190),context.dp(if(enabled)96 else 106))
        if(enabled){
            streamingArt.addView(art,FrameLayout.LayoutParams(-1,-1))
            streamingArt.addView(imageShade,FrameLayout.LayoutParams(-1,-1))
            params.leftMargin=context.dp(20);addView(streamingArt,params)
        }
        else {params.rightMargin=context.dp(18);addView(art,0,params)}
        setPadding(context.dp(if(enabled)14 else 8),context.dp(if(enabled)8 else 6),context.dp(if(enabled)10 else 8),context.dp(8))
        // The screen behind already paints the streaming colour; filling it again here was pure overdraw.
        background=null
        heading.textSize=if(enabled)24f else 19f
        heading.maxLines=if(enabled)1 else 2
        heading.ellipsize=if(enabled)TextUtils.TruncateAt.END else null
        subtitle.setTextColor(if(enabled)Theme.muted else Theme.accent)
        summary.maxLines=if(enabled)2 else 3
        layoutParams?.let {it.height=context.dp(if(enabled)112 else 122);layoutParams=it}
        if(enabled)updateStreamingText(width)
        val item=shownItem;shownItem=null;imageKey=null;show(item)
    }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        if(streaming)updateStreamingText(w)
    }
    private fun updateStreamingText(width:Int) {
        val narrow=width>0&&width<context.dp(560)
        heading.textSize=if(narrow)21f else 24f
        heading.maxLines=if(narrow)2 else 1
        summary.maxLines=if(narrow)1 else 2
    }
    fun show(item:ContentItem?) {
        val nextVisibility=if(item==null)GONE else VISIBLE
        // A focused card can be rebound when its row receives a metadata update.
        // Equal items need no repeated text layout or image work.
        if(item==shownItem&&visibility==nextVisibility)return
        shownItem=item
        if(visibility!=nextVisibility)visibility=nextVisibility
        if(item==null){cancelArtwork();art.setImageDrawable(null);imageKey=null;return}
        imageShade.visibility=if(streaming&&!item.landscapeImageUrl.isNullOrBlank())VISIBLE else GONE
        if(!TextUtils.equals(heading.text,item.title))heading.text=item.title
        val nextSubtitle=listOfNotNull(item.subtitle,RatingLabels.short(item)?.takeIf{item.subtitle?.contains("IMDb")!=true}).joinToString(" · ")
        if(!TextUtils.equals(subtitle.text,nextSubtitle))subtitle.text=nextSubtitle
        val nextSummary=item.description?.takeIf {it.isNotBlank()} ?: if(streaming)
            "OK to open  ·  Hold OK for more" else "Hold OK for details and channel favourites."
        if(!TextUtils.equals(summary.text,nextSummary))summary.text=nextSummary
        val url=artwork(item)
        val key="${item.source}:${item.id}:$url:$streaming"
        if(key!=imageKey) {
            cancelArtwork();imageKey=key;art.setImageDrawable(null)
            val logo=item.landscapeImageUrl.isNullOrBlank()&&item.imageUrl.isNullOrBlank()
            art.scaleType=if(streaming&&!item.landscapeImageUrl.isNullOrBlank())ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            // In streaming sections the screen behind is this same colour, so the art needs no fill of its own.
            art.setBackgroundColor(if(streaming)Color.TRANSPARENT else if(logo)Color.rgb(235,239,244)else Color.rgb(22,31,42))
            // Decode at preview size, not the source poster's full resolution, on every focus move.
            if(url!=null) {
                val width=context.dp(if(streaming)216 else 190)
                val height=context.dp(if(streaming)96 else 106)
                val generation=artworkGeneration
                // Wait until focus rests on a card: while a row is being crossed, each card otherwise started
                // (and at once cancelled) its own download, decode and texture upload.
                val load=if(context.lightweightLauncher) Runnable {
                    pendingArtwork=null
                    if(generation==artworkGeneration && isAttachedToWindow) {
                        request=context.imageLoader.enqueue(imageRequest(context,item,url).size(width,height)
                            .target(onSuccess={ drawable ->
                                if(generation==artworkGeneration && isAttachedToWindow)art.setImageDrawable(drawable)
                            }).build())
                    }
                } else Runnable {
                    pendingArtwork=null
                    if(generation==artworkGeneration)
                        request=context.imageLoader.enqueue(imageRequest(context,item,url).size(width,height).target(art).build())
                }
                pendingArtwork=load
                artworkHandler.postDelayed(load,if(context.lightweightLauncher)120L else PREVIEW_ART_DELAY_MILLIS)
            }
        }
    }
    fun compact(value:Boolean) {
        if(streaming)return
        val width=context.dp(if(value)150 else 190)
        val height=context.dp(if(value)84 else 106)
        val params=art.layoutParams as LayoutParams
        if(params.width!=width||params.height!=height)art.layoutParams=params.apply {this.width=width;this.height=height}
        val lines=if(value)2 else 3
        if(summary.maxLines!=lines)summary.maxLines=lines
    }
    private fun cancelArtwork() {
        artworkGeneration++
        pendingArtwork?.let(artworkHandler::removeCallbacks)
        pendingArtwork=null
        request?.dispose();request=null
    }
    fun release(){cancelArtwork();imageKey=null;art.setImageDrawable(null)}
    override fun onDetachedFromWindow() {
        if(context.lightweightLauncher)release()
        super.onDetachedFromWindow()
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if(context.lightweightLauncher) {
            // A pre-attachment request may have been skipped; attachment starts a fresh generation.
            val item=shownItem
            shownItem=null;imageKey=null
            show(item)
        }
    }
    companion object {
        /** Longer than a held D-pad's repeat interval, short enough that a deliberate stop still feels immediate. */
        private const val PREVIEW_ART_DELAY_MILLIS=150L
        private fun artwork(item:ContentItem):String? = (item.landscapeImageUrl?:item.imageUrl?:item.metadata["fallbackArtwork"])
            ?.takeIf {it.isNotBlank() && (item.metadata["accountAuthorised"]!="true" || StremioMetadataPolicy.publicUrl(it))}
        private fun imageRequest(context:Context,item:ContentItem,url:String)=ImageRequest.Builder(context)
            .data(url).crossfade(false).apply {
                if(item.metadata["accountAuthorised"]=="true")diskCachePolicy(CachePolicy.DISABLED)
                if(android.net.Uri.parse(url).host?.endsWith("xmltv.net")==true)transformations(GuideLogoWatermarkMask)
            }
        fun dialog(context:Context,item:ContentItem,favourite:Boolean,canFavourite:Boolean,onOpen:()->Unit,onFavourite:()->Unit,openLabel:String="Open",onWatched:(()->Unit)?=null,watched:Boolean=false,
                   neutral:Pair<String,()->Unit>?=null,negative:Pair<String,()->Unit>?=null) {
            val body=LinearLayout(context).apply {orientation=VERTICAL;setPadding(context.dp(20),0,context.dp(20),0)}
            val image=ImageView(context).apply {scaleType=ImageView.ScaleType.FIT_CENTER;setBackgroundColor(Color.rgb(235,239,244))}
            var loading:Disposable?=null
            artwork(item)?.let {url->body.addView(image,LayoutParams(-1,context.dp(150)));loading=context.imageLoader.enqueue(imageRequest(context,item,url).target(image).build())}
            val scroll=ScrollView(context).apply {isFocusable=true;isFillViewport=false}
            scroll.addView(Theme.label(context,16f).apply {text=listOfNotNull(item.subtitle?.takeIf{it.isNotBlank()},RatingLabels.detail(item),item.description?.takeIf{it.isNotBlank()}?:"No further description is available.").joinToString("\n\n");setPadding(0,context.dp(12),0,context.dp(12))})
            body.addView(scroll,LayoutParams(-1,context.dp(160)))
            val builder=AlertDialog.Builder(context).setTitle(item.title).setView(body)
                .setPositiveButton(openLabel){_,_->onOpen()}
            if(negative!=null)builder.setNegativeButton(negative.first){_,_->negative.second()}else builder.setNegativeButton("Close",null)
            if(neutral!=null)builder.setNeutralButton(neutral.first){_,_->neutral.second()}
            else if(onWatched!=null)builder.setNeutralButton(if(watched)"Show again" else "Already watched"){_,_->onWatched()}
            else if(canFavourite)builder.setNeutralButton(if(favourite)"Remove favourite" else "Favourite channel"){_,_->onFavourite()}
            builder.create().apply {setOnDismissListener{loading?.dispose()};show()}
        }
    }
}
