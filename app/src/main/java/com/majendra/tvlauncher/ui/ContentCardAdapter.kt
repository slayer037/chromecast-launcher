package com.majendra.tvlauncher.ui

import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.imageLoader
import coil.request.CachePolicy
import com.majendra.tvlauncher.providers.StremioMetadataPolicy
import coil.request.Disposable
import coil.request.ImageRequest
import coil.size.Scale
import android.net.Uri
import okhttp3.Headers
import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.lightweightLauncher

class ContentCardAdapter(
    items: List<ContentItem>, private val style: CardStyle,
    private val onItem: (ContentItem) -> Unit, private val onLeft: () -> Unit,
    private val onFocused: (ContentItem) -> Unit, private val onDetails: (ContentItem) -> Unit
) : CardListAdapter<ContentCardAdapter.Holder>(items) {
    // Rows share one view pool, so each card size needs its own view type.
    override fun getItemViewType(position: Int) = style.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val width = context.dp(style.widthDp)
        val artHeight = context.dp(style.artDp)
        val root = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                rightMargin = context.dp(if (style == CardStyle.CHANNEL) 8 else 12)
            }
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(6))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true

            clipToOutline = style != CardStyle.POSTER
            Theme.focus(this, false, focusedElevationDp = if (style == CardStyle.POSTER) 0 else 6)
            setOnFocusChangeListener { view, focus ->
                Theme.focus(view, focus, focusedElevationDp = if (style == CardStyle.POSTER) 0 else 6)
            }
        }
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.rgb(22, 31, 42)) }
        if (style == CardStyle.STREMIO_CHANNEL || style == CardStyle.STREMIO_EVENT) {
            frame.background = Theme.shape(context, Theme.sidebar, radius = 6)
            frame.clipToOutline = true
        }
        if (style.pictureOnly) {
            // The rounded picture is the card; the even padding is where the focus ring shows.
            root.setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
            frame.background = pictureFace(context)
            frame.clipToOutline = true
        }
        val unavailable = Theme.label(context, 13f).apply {
            text = "Artwork unavailable"
            setTextColor(Theme.muted)
            gravity = Gravity.CENTER
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val badge = Theme.label(context, 11f, true).apply {
            text = "LIVE"
            setTextColor(Theme.live)
            setPadding(context.dp(7), context.dp(3), context.dp(7), context.dp(3))
            background = Theme.shape(context, Theme.background, radius = 4)
        }
        val starBadge = Theme.label(context, 16f, true).apply {
            text = "★"
            setTextColor(Theme.accent)
            setPadding(context.dp(6), context.dp(3), context.dp(6), context.dp(3))
            background = Theme.shape(context, Theme.background, radius = 4)
            visibility = View.GONE
            isFocusable = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(unavailable, FrameLayout.LayoutParams(-1, -1))
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        frame.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(context.dp(6), context.dp(6), 0, 0) })
        frame.addView(starBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, context.dp(6), context.dp(6), 0) })
        root.addView(frame, LinearLayout.LayoutParams(-1, artHeight))
        val title = Theme.label(context, 15f, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(7), context.dp(7), context.dp(7), 0)
        }
        root.addView(title, LinearLayout.LayoutParams(-1, context.dp(44)))
        val subtitle = Theme.label(context, 12f).apply {
            setTextColor(Theme.muted)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(7), 0, context.dp(7), 0)
        }
        root.addView(subtitle, LinearLayout.LayoutParams(-1, context.dp(18)))
        val description = Theme.label(context, 11f).apply {
            setTextColor(Theme.muted)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(context.dp(7), context.dp(2), context.dp(7), 0)
        }
        root.addView(description, LinearLayout.LayoutParams(-1, context.dp(34)))
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = false
            setPadding(0, 0, 0, 0)
            minimumHeight = 0
            // Fill the actual 3dp track; TV framework drawables may have larger vertical insets.
            progressDrawable = LayerDrawable(arrayOf(
                ColorDrawable(Theme.selected),
                ClipDrawable(ColorDrawable(Theme.accent), Gravity.START, ClipDrawable.HORIZONTAL)
            )).apply {
                setId(0, android.R.id.background)
                setId(1, android.R.id.progress)
            }
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, context.dp(3)).apply { setMargins(context.dp(7), context.dp(3), context.dp(7), 0) })
        return Holder(root, frame, image, unavailable, badge, starBadge, title, subtitle, description, progress, width, artHeight)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.kids = item.source == "kids"
        holder.style = style
        if (holder.kids) focusKidsCard(holder, holder.itemView.hasFocus())
        else if (streamingStyle()) focusStreamingCard(holder.itemView, holder.itemView.hasFocus())
        else {
            Theme.focus(holder.itemView, holder.itemView.hasFocus(), focusedElevationDp = if (style == CardStyle.POSTER) 0 else 6)
            holder.itemView.animate().cancel(); holder.itemView.scaleX = 1f; holder.itemView.scaleY = 1f // Pooled from Kids Corner.
        }
        val context = holder.itemView.context
        val hero = holder.kids && item.metadata["type"] == "channel"
        val upNext = item.metadata["upNext"]?.takeIf { hero && style != CardStyle.KIDS_LIVE && it.isNotBlank() }
        // Everything in this block depends only on style, kids and hero. Repeating it on every bind cost two
        // forced layout requests (setMaxLines) and a new badge drawable per card for no visible change.
        if (holder.configuredStyle != style ||
            holder.configuredKids != holder.kids || holder.configuredHero != hero) {
            if (!style.pictureOnly && style != CardStyle.STREMIO_CHANNEL && style != CardStyle.STREMIO_EVENT)
                holder.frame.setBackgroundColor(if (holder.kids) KidsTheme.softBlue else if(streamingStyle())Color.rgb(18,18,18)else Color.rgb(22, 31, 42))
            holder.title.setTextColor(if (holder.kids) KidsTheme.navy else Theme.text)
            holder.subtitle.setTextColor(if (holder.kids) KidsTheme.navy else Theme.muted)
            holder.description.setTextColor(if (holder.kids) KidsTheme.navy else Theme.muted)
            holder.badge.setTextColor(if (holder.kids) KidsTheme.navy else Theme.live)
            holder.badge.background = if (holder.kids) KidsTheme.shape(holder.itemView.context, KidsTheme.yellow, radius = 6)
                else Theme.shape(holder.itemView.context, Theme.background, radius = 4)
            // The kids shape reserves 4dp at the bottom for its shadow; keep the label inside the face.
            holder.badge.setPadding(context.dp(7), context.dp(if (holder.kids) 4 else 3), context.dp(7), context.dp(if (holder.kids) 7 else 3))
            // ABC Kids Live is a double-width hero card with an "Up next" line; other services keep their layout.
            // The small kids live tile leaves "Up next" to the focused preview.
            val cardWidth = if (hero && style == CardStyle.LANDSCAPE) holder.width * 2 + context.dp(12) else holder.width
            (holder.itemView.layoutParams as RecyclerView.LayoutParams).let { if (it.width != cardWidth) { it.width = cardWidth; holder.itemView.requestLayout() } }
            val kidsLandscape = holder.kids && style == CardStyle.LANDSCAPE
            holder.title.visibility = if (style.titleDp == 0) View.GONE else View.VISIBLE
            holder.title.textSize = style.titleSp
            holder.title.maxLines = style.titleLines
            holder.title.typeface = android.graphics.Typeface.create(if (holder.kids) "sans-serif-rounded" else "sans-serif", android.graphics.Typeface.BOLD)
            holder.title.setPadding(context.dp(if (style == CardStyle.CHANNEL) 5 else 7), context.dp(if (style == CardStyle.CHANNEL || streamingStyle()) 4 else 7), context.dp(5), 0)
            holder.title.layoutParams.height = context.dp(if (kidsLandscape) 50 else style.titleDp)
            // Kids posters show only a big name: young children read pictures, not years or ratings.
            holder.subtitle.visibility = if (style.subtitleSp == 0f) View.GONE else View.VISIBLE
            holder.subtitle.textSize = if (kidsLandscape) 14f else style.subtitleSp
            holder.subtitle.layoutParams.height = context.dp(if (kidsLandscape) 22 else if (style == CardStyle.CHANNEL) 15 else 18)
            holder.subtitle.setPadding(context.dp(if (style == CardStyle.CHANNEL) 5 else 7), 0, context.dp(5), 0)
            holder.description.textSize = 13f
            holder.description.maxLines = 1
            holder.description.layoutParams.height = context.dp(20)
            holder.configuredStyle = style
            holder.configuredKids = holder.kids
            holder.configuredHero = hero
        }
        if (!TextUtils.equals(holder.title.text, item.title)) holder.title.text = item.title
        val subtitle = if(streamingStyle()) StreamingBrowsePolicy.subtitle(item) else item.subtitle
        val nextSubtitle = listOfNotNull(subtitle,RatingLabels.short(item)?.takeIf{!streamingStyle()&&item.subtitle?.contains("IMDb")!=true}).joinToString(" · ")
        if (!TextUtils.equals(holder.subtitle.text, nextSubtitle)) holder.subtitle.text = nextSubtitle
        // Full copy lives in the focused preview and details dialog.
        if (!TextUtils.equals(holder.description.text, upNext.orEmpty())) holder.description.text = upNext.orEmpty()
        holder.description.visibility = if (upNext != null) View.VISIBLE else View.GONE
        val stremioSport = style == CardStyle.STREMIO_CHANNEL || style == CardStyle.STREMIO_EVENT
        val showLive = item.isLive && !stremioSport // A catalogue entry does not establish that a stream is live.
        holder.itemView.contentDescription = StarBadgePolicy.description(item, showLive)
        holder.badge.visibility = if (showLive) View.VISIBLE else View.GONE
        holder.starBadge.visibility = if (StarBadgePolicy.visible(item)) View.VISIBLE else View.GONE
        val progress = item.progress?.takeIf { it.isFinite() }
        holder.progress.visibility = if (style.pictureOnly || stremioSport) View.GONE else if (progress != null) View.VISIBLE else View.INVISIBLE
        holder.progress.progress = ((progress ?: 0f).coerceIn(0f, 1f) * 1000).toInt()
        val posters = style == CardStyle.POSTER || style == CardStyle.KIDS_POSTER || style == CardStyle.WATCHING_POSTER || style == CardStyle.STREAMING_POSTER
        val rawImageUrl = if (posters) item.imageUrl ?: item.landscapeImageUrl else item.landscapeImageUrl ?: item.imageUrl
        holder.privateArtwork = item.metadata["accountAuthorised"] == "true"
        val imageUrl = rawImageUrl?.takeIf { !holder.privateArtwork || StremioMetadataPolicy.publicUrl(it) }
        val fallback = item.metadata["fallbackArtwork"]?.takeIf {
            it.isNotBlank() && it != imageUrl && Uri.parse(it).scheme == "https"
        }
        val textFallback=item.metadata["channelName"]?.takeIf { it.isNotBlank() }
            ?: item.title.takeIf { !item.metadata["inputId"].isNullOrBlank() }
        val artworkKind = item.metadata["artworkKind"] ?: if (item.metadata["logo"] == "true") "logo" else null
        if (!holder.imageBound || holder.boundItemKey != key(item) || holder.boundImageUrl != imageUrl || holder.boundFallbackUrl != fallback || holder.fallbackLabel!=textFallback || holder.boundArtworkKind != artworkKind) {
            holder.imageBound = true
            holder.boundItemKey = key(item)
            holder.boundImageUrl = imageUrl
            holder.boundFallbackUrl = fallback
            holder.boundArtworkKind = artworkKind
            holder.fallbackLabel=textFallback
            val generation = ++holder.imageGeneration
            holder.artRequest?.dispose()
            holder.artRequest = null
            holder.image.setImageDrawable(null)
            holder.image.setBackgroundColor(Color.TRANSPARENT)
            holder.image.scaleType = ImageView.ScaleType.CENTER_CROP
            holder.unavailable.text = "Loading artwork"
            holder.unavailable.textSize = 13f
            holder.unavailable.setTextColor(if (holder.kids) KidsTheme.navy else Theme.muted)
            holder.unavailable.visibility = View.VISIBLE
            // Logos are fitted; Stremio fixture posters retain the full image on the existing dark TV palette.
            if (!imageUrl.isNullOrBlank()) loadArtwork(holder, imageUrl, item.metadata["logo"] == "true", fallback, generation)
            else if (fallback != null) loadArtwork(holder, fallback, true, null, generation)
            else showTextFallback(holder,generation)
        }
        holder.itemView.setOnFocusChangeListener { view, focused ->
            if (holder.kids) focusKidsCard(holder, focused)
            else if(streamingStyle()) focusStreamingCard(view, focused)
            else Theme.focus(view, focused, focusedElevationDp = if (style == CardStyle.POSTER) 0 else 6)
            if(focused)onFocused(item)
        }
        if(holder.itemView.hasFocus())onFocused(item)
        holder.itemView.setOnClickListener { onItem(item) }
        holder.itemView.setOnLongClickListener { onDetails(item);true }
        holder.itemView.setOnKeyListener { _, code, event ->
            if (code == KeyEvent.KEYCODE_DPAD_LEFT && holder.bindingAdapterPosition == 0) {
                if (event.action == KeyEvent.ACTION_DOWN) onLeft()
                true
            } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT && itemCount > 0 && holder.bindingAdapterPosition == itemCount - 1) {
                true // Stay in this row at its end; framework focus search otherwise jumps to another row.
            } else false
        }
    }

    private fun streamingStyle() = style == CardStyle.STREAMING_POSTER || style == CardStyle.STREAMING_CONTINUE

    private fun focusStreamingCard(view:View,focused:Boolean) {
        view.animate().cancel();view.scaleX=1f;view.scaleY=1f
        val face=(view.background as? GradientDrawable) ?: GradientDrawable().also {view.background=it}
        face.cornerRadius=view.context.dp(7).toFloat()
        face.setColor(if(focused)Color.rgb(34,34,34)else Color.rgb(18,18,18))
        face.setStroke(view.context.dp(if(focused)3 else 1),if(focused)Theme.text else Color.TRANSPARENT)
        view.elevation=0f
    }

    private fun focusKidsCard(holder: Holder, focused: Boolean) {
        val view = holder.itemView
        // Picture-only posters get just a yellow ring when chosen; other kids cards keep their white face.
        // Child labels stay navy: use a yellow face rather than the button theme's navy face.
        // Each card keeps its two faces: building three drawables on every focus move showed up as dropped frames.
        val face = if (focused) holder.kidsFocusedFace ?: (if (style.pictureOnly) pictureFace(view.context, KidsTheme.yellow, 18)
                else KidsTheme.shape(view.context, KidsTheme.yellow)).also { holder.kidsFocusedFace = it }
            else if (style.pictureOnly) null
            else holder.kidsPlainFace ?: KidsTheme.shape(view.context, KidsTheme.white).also { holder.kidsPlainFace = it }
        if (view.background !== face) view.background = face
        if(view.context.lightweightLauncher) {
            // Chromecast variant uses the yellow focus ring without animated zoom or a shadow layer.
            view.animate().cancel();view.scaleX=1f;view.scaleY=1f;view.elevation=0f
            return
        }
        view.elevation = view.context.dp(if (focused) 6 else 0).toFloat()
        // A clear grow on the chosen picture tells a young child exactly where they are.
        val scale = if (focused) 1.07f else 1f
        view.animate().scaleX(scale).scaleY(scale).setDuration(120).start()
    }

    private fun pictureFace(context: android.content.Context, color: Int = KidsTheme.softBlue, radius: Int = 14) =
        android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = context.dp(radius).toFloat() }

    private fun showTextFallback(holder:Holder,generation:Long) {
        if(holder.imageGeneration!=generation)return
        holder.image.setImageDrawable(null)
        holder.image.setBackgroundColor(Color.TRANSPARENT)
        holder.unavailable.apply {
            text=holder.fallbackLabel ?: "Artwork unavailable"
            textSize=if(holder.fallbackLabel==null)13f else if(holder.style==CardStyle.CHANNEL)16f
                else if(holder.style==CardStyle.STREMIO_CHANNEL || holder.style==CardStyle.STREMIO_EVENT)20f else 30f
            setTextColor(if(holder.kids)KidsTheme.navy else if(holder.fallbackLabel!=null)Theme.accent else Theme.muted)
            setPadding(context.dp(12),0,context.dp(12),0)
            maxLines=2
            ellipsize=TextUtils.TruncateAt.END
            visibility=View.VISIBLE
        }
    }

    private fun loadArtwork(holder: Holder, url: String, logo: Boolean, fallback: String?, generation: Long) {
        if (holder.imageGeneration != generation) return
        val stremioSport = holder.style == CardStyle.STREMIO_CHANNEL || holder.style == CardStyle.STREMIO_EVENT
        val fit = logo || holder.style == CardStyle.WATCHING_POSTER || holder.style == CardStyle.STREAMING_POSTER || (stremioSport && (holder.style == CardStyle.STREMIO_EVENT || holder.boundArtworkKind == "poster"))
        holder.image.scaleType = if (fit) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        holder.image.setBackgroundColor(if (stremioSport) Color.TRANSPARENT else if(logo)Color.rgb(235,239,244)else Color.TRANSPARENT)
        val inset = holder.image.context.dp(if (holder.style == CardStyle.STREMIO_CHANNEL && logo) 12 else 0)
        holder.image.setPadding(inset, inset, inset, inset)
        val request = ImageRequest.Builder(holder.image.context)
            .data(url)
            .size(holder.itemView.layoutParams?.width?.takeIf { it > 0 } ?: holder.width, holder.artHeight)
            .scale(if (fit) Scale.FIT else Scale.FILL)
            .crossfade(false)
            .target(
                onSuccess = { drawable ->
                    if (holder.imageGeneration == generation) {
                        holder.image.setImageDrawable(drawable)
                        holder.unavailable.visibility = View.GONE
                    }
                },
                onError = {
                    if (holder.imageGeneration == generation) {
                        if (fallback != null) {
                            // One fallback only; queue after the failed request has completed.
                            holder.image.post { loadArtwork(holder, fallback, true, null, generation) }
                        } else {
                            showTextFallback(holder,generation)
                        }
                    }
                }
            )
        if(holder.privateArtwork) request.diskCachePolicy(CachePolicy.DISABLED)
        val host = Uri.parse(url).host?.lowercase().orEmpty()
        if (host == "xmltv.net" || host.endsWith(".xmltv.net")) {
            request.headers(Headers.Builder().set("User-Agent", "ChromecastLauncher/1.0").build())
            if (logo) request.transformations(GuideLogoWatermarkMask)
        }
        holder.artRequest = holder.image.context.imageLoader.enqueue(request.build())
    }

    override fun onViewRecycled(holder: Holder) {
        holder.imageGeneration++
        holder.artRequest?.dispose()
        holder.artRequest = null
        holder.imageBound = false
        holder.image.setImageDrawable(null)
        holder.starBadge.visibility = View.GONE
        super.onViewRecycled(holder)
    }

    class Holder(
        root: View, val frame: FrameLayout, val image: ImageView, val unavailable: TextView, val badge: TextView, val starBadge: TextView,
        val title: TextView, val subtitle: TextView, val description: TextView, val progress: ProgressBar,
        val width: Int, val artHeight: Int
    ) : RecyclerView.ViewHolder(root) {
        var configuredStyle: CardStyle? = null
        var configuredKids = false
        var configuredHero = false
        var kids = false
        var style = CardStyle.LANDSCAPE
        var privateArtwork = false
        var boundImageUrl: String? = null
        var boundFallbackUrl: String? = null
        var boundArtworkKind: String? = null
        var boundItemKey: String? = null
        var fallbackLabel: String? = null
        var imageGeneration = 0L
        var artRequest: Disposable? = null
        var imageBound = false
        var kidsFocusedFace: android.graphics.drawable.Drawable? = null
        var kidsPlainFace: android.graphics.drawable.Drawable? = null
    }
}
