package com.majendra.tvlauncher

import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.majendra.tvlauncher.data.*
import com.majendra.tvlauncher.kids.*
import com.majendra.tvlauncher.providers.StremioDiscoveryProvider
import com.majendra.tvlauncher.providers.StremioGenrePolicy
import com.majendra.tvlauncher.providers.StremioMetadataClient
import com.majendra.tvlauncher.providers.cinemeta.CinemetaCatalogue
import com.majendra.tvlauncher.auth.StremioAccountStore
import com.majendra.tvlauncher.ui.*
import com.majendra.tvlauncher.settings.*
import com.majendra.tvlauncher.playback.PlaybackRouter
import com.majendra.tvlauncher.diagnostics.DiagnosticLog
import com.majendra.tvlauncher.diagnostics.RemoteNavigationDiagnostics
import com.majendra.tvlauncher.diagnostics.FocusSnapshot
import com.majendra.tvlauncher.diagnostics.Pane
import com.majendra.tvlauncher.providers.UpcomingEpisodesProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainActivity:ComponentActivity() {
    private val repository get()=(application as LauncherApplication).repository
    private val settings by lazy {LauncherSettings(this)}
    private val choices by lazy {ContentPreferences(this)}
    private val kidsWatched by lazy {KidsWatchedStore(this)}
    private val router by lazy {PlaybackRouter(this)}
    private val scheduler by lazy {FeedScheduler(lifecycleScope)}
    private lateinit var root:LinearLayout
    private lateinit var sidebar:LinearLayout
    private lateinit var sidebarPanel:LinearLayout
    private lateinit var contentSurface:LinearLayout
    private lateinit var sportsPreview:FrameLayout
    private lateinit var heading:LinearLayout
    private lateinit var navigationHint:TextView
    private lateinit var settingsButton:TextView
    private lateinit var title:TextView
    private lateinit var status:TextView
    private lateinit var message:TextView
    private lateinit var permission:TextView
    private lateinit var details:FocusedDetailsView
    private lateinit var panel:ContentPanel
    private lateinit var searchControls:SearchControls
    private lateinit var kidsPicker:KidsMoviePicker
    private val catalogue by lazy {CinemetaCatalogue.shared(this)}
    // Offers come from the whole indexed catalogue; the loaded rows are the fallback.
    private val moviePicker by lazy {MoviePicker(this,lifecycleScope,{lastResult?.takeIf{browsing("movies")}?.let{choices.apply("movies",it)}?.rows.orEmpty()},{play(it)},{if(!choices.isHidden(it))choices.toggleHidden(it);lastResult?.let{render(it)}},
        CatalogueMoviePickerSource(catalogue),{withContext(Dispatchers.IO){choices.excludedTitles("movie",true)}},{metadataClient.enrichItem(it)},
        catalogue.genres("movie"))}
    // An applied filter lasts, like a search, until it is cleared or the launcher restarts. Movies and TV shows only.
    private val filters=mutableMapOf<String,MovieFilter>()
    private val filterWindows=mutableMapOf<String,FilterWindow>()
    private val filterDemand=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private val filterChanged=kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var filterMismatch:Triple<Int,Int,Long>?=null // The last drawn/held card counts reported to the results loop, and for which window.
    private var filterJob:Job?=null
    private var visitSeed=0L // Movies and TV shows reshuffle on each visit, but not on refreshes during one.
    private val rowExitPress=RowExitPressPolicy()
    private val sidebarPress=SidebarPressPolicy()
    private val queries=mutableMapOf<String,String>()
    private val detailAttempts=EnrichmentAttempts()
    private val ratingOverlay=linkedMapOf<String,Map<String,String>>()
    // Each builds an HTTP client: constructed off the UI thread from onCreate, not while the activity is instantiated.
    private val metadataClient by lazy {StremioMetadataClient()}
    private val upcomingEpisodes by lazy {UpcomingEpisodesProvider()}
    private val remoteDiagnostics by lazy { RemoteNavigationDiagnostics.get(this) }
    private val tonightSeeds=mutableMapOf<String,Long>()
    private var upcomingJob:Job?=null
    private var starredJob:Job?=null
    private var detailJob:Job?=null
    private var previewSettle:Job?=null // Chromecast variant: the preview waits for focus to rest on a card.
    private var detailGeneration=0L
    private val sidebarViews=linkedMapOf<String,TextView>()
    private var selected="youtube"
    private var uiJob:Job?=null
    private var renderJob:Job?=null
    private val renderMutex=Mutex()
    private val renderGate=RenderRequestGate()
    private var latestRenderInput:FeedResult?=null
    private var lastFeedRequest:FeedResult?=null
    private var lastFeedContext:RenderContext?=null
    private var lastRenderAt=0L
    private var renderForeground=true
    private var lastProjection:RenderProjection?=null // What is on screen now, for single-title updates.
    private var sectionEnteredAt=0L
    private var progressAt=0L
    private var upcomingShown:Any?=Unit // Unit until the first upcoming-episodes row has been rendered.
    private val unfinishedRefresh=mutableSetOf<String>() // Refreshes this screen stopped waiting for.
    private var sportsShown:com.majendra.tvlauncher.sportscentre.SportsSnapshot?=null
    private val watchedSync by lazy {com.majendra.tvlauncher.sync.WatchedSync.shared(this)}
    private var watchedPoll:Job?=null
    private var watchedUpdates:Job?=null
    private var debounce:Job?=null
    private var lastResult:FeedResult?=null
    private var focusedItem:ContentItem?=null
    private var pendingContentService:String?=null
    private var enterAtStart=false // Sidebar entry starts at the first card; returning from an app restores the last one.
    private var returnToContent=false
    private val clearRecoveryBypass=Runnable {
        if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)&&window.decorView.hasWindowFocus())HomeCompatibility(this).ownLauncherActive()
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // After repeated crashes: a plain screen that starts none of the launcher (see CrashGuard). Every other callback checks safeMode.
        // Another try rebuilds this activity in place, so the launcher is what comes up rather than whatever app was open before.
        val crashGuard=(application as LauncherApplication).crashGuard
        if(crashGuard.safeMode){safeMode=true;setContentView(com.majendra.tvlauncher.ui.SafeModeScreen.build(this,{crashGuard.clear();recreate()},{runCatching{cacheDir.deleteRecursively()};runCatching{java.io.File(filesDir,"feeds").deleteRecursively()};crashGuard.clear();recreate()}));return}
        // Unseal the local stores and build the HTTP clients while the screen is being built, not in its way.
        com.majendra.tvlauncher.sync.WatchedSync.warm(this)
        lifecycleScope.launch(Dispatchers.IO){metadataClient;upcomingEpisodes}
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        selected=savedInstanceState?.getString("service")?.takeIf { id->Services.all.any { it.id==id } && LiveTvPolicy.sectionAllowed(liveTvEnabled,id) }?:settings.lastService()
        buildScreen()
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                startWatchedPolling()
                startUpcomingEpisodes()
                if(selected=="starred")enrichStarred()
                try { awaitCancellation() }
                finally { watchedPoll?.cancel();watchedPoll=null;upcomingJob?.cancel();upcomingJob=null;starredJob?.cancel();starredJob=null }
            }
        }
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true){
            override fun handleOnBackPressed(){cancelContentEntry();if(sidebarViews[selected]?.hasFocus()!=true)focusSidebar()else showExit()}
        })
        sidebarViews[selected]?.requestFocus();select(selected)
        openRequestedSection(intent)
        if(liveTvEnabled&&checkSelfPermission("android.permission.READ_TV_LISTINGS")!=PackageManager.PERMISSION_GRANTED&&savedInstanceState==null)allowListings()
        DiagnosticLog.event(this,"launcher_created",mapOf("version" to "1.0.0-rc11","uptimeMillis" to SystemClock.elapsedRealtime()))
        root.viewTreeObserver.addOnDrawListener(object:ViewTreeObserver.OnDrawListener {
            private var drawn=false
            override fun onDraw(){if(!drawn){drawn=true;root.post {if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnDrawListener(this);DiagnosticLog.event(this@MainActivity,"launcher_first_draw",mapOf("uptimeMillis" to SystemClock.elapsedRealtime()))}}}
        })
    }
    private fun buildScreen() {
        // Not baseline aligned: a horizontal LinearLayout otherwise first measures its weighted child (the whole content
        // pane) with no size limit to find a text baseline. On every layout request that made the rows list build a
        // screenful of rows it then threw away: 200 to 300 ms a time on the Chromecast (measured 12 November 2026).
        root=FocusBoundaryLayout(this).apply {orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false;setBackgroundColor(Theme.background);setPadding(dp(20),dp(20),0,dp(12))} // No right padding: content rows run to the screen edge.
        val left=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));background=Theme.shape(this@MainActivity,Theme.sidebar,false,12)}
        (root as FocusBoundaryLayout).sidebarBoundary=left;sidebarPanel=left
        root.addView(left,LinearLayout.LayoutParams(dp(176),-1).apply {rightMargin=dp(24)})
        left.addView(Theme.label(this,11f,true).apply {text=getString(com.majendra.tvlauncher.R.string.sidebar_brand);setTextColor(Theme.muted);setPadding(dp(10),dp(8),0,dp(14))})
        val scroll=ScrollView(this).apply {isVerticalScrollBarEnabled=false}
        sidebar=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};scroll.addView(sidebar)
        left.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));buildSidebar()
        settingsButton=action("Settings"){showSettings()};left.addView(settingsButton,LinearLayout.LayoutParams(-1,dp(38)))
        val right=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};contentSurface=right;root.addView(right,LinearLayout.LayoutParams(0,-1,1f))
        heading=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        title=Theme.label(this,22f,true);heading.addView(title,LinearLayout.LayoutParams(0,dp(34),1f)) // The sidebar already names the section.
        status=Theme.label(this,11f).apply {gravity=Gravity.END or Gravity.CENTER_VERTICAL;setTextColor(Theme.muted)}
        heading.addView(status,LinearLayout.LayoutParams(dp(180),dp(40)));right.addView(heading,edge(-1,-2))
        message=Theme.label(this,12f).apply {setTextColor(Theme.muted);maxLines=2;setPadding(0,dp(2),0,dp(6))};right.addView(message,edge(-1,-2))
        permission=action(if(liveTvEnabled) "Allow TV listings" else "Allow YouTube recommendations"){allowListings()}.apply {visibility=View.GONE};right.addView(permission,LinearLayout.LayoutParams(dp(180),dp(36)))
        searchControls=SearchControls(this,{submitSearch(it)},{openStremioSearch()},{moviePicker.start()},{refreshMix()},
            {refreshTonight()},{showLocalPreferences()},{showFilter()},{clearFilter()})
        right.addView(searchControls,edge(-1,dp(42)))
        kidsPicker=KidsMoviePicker(this,lifecycleScope,{browseKids(it)},{winner->
            if(selected=="kids"&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))play(winner,true)
        }).apply {onLeft={};visibility=View.GONE} // Back returns from the picker controls to the sidebar.
        right.addView(kidsPicker,edge(-1,-2).apply{bottomMargin=dp(6)})
        details=FocusedDetailsView(this);right.addView(details,edge(-1,dp(122)))
        // A fresh Left at the first card opens the sidebar; vanished rows recover focus through this callback.
        panel=ContentPanel(this,{play(it)},{panel.post{if(currentFocus?.let{f->FocusController.isWithin(contentSurface,f)}!=true){cancelContentEntry();focusSidebar()}}},{row,item->onFocused(row,item)},{showDetails(it)})
        right.addView(panel,LinearLayout.LayoutParams(-1,0,1f))
        navigationHint=Theme.label(this,10f).apply {text=HINT;setTextColor(Theme.muted);setPadding(dp(4),dp(4),0,0)}
        right.addView(navigationHint,edge(-1,dp(20)))
        sportsPreview=FrameLayout(this).apply {visibility=View.GONE;descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS;setPadding(dp(12),dp(12),dp(20),dp(12))}
        right.addView(sportsPreview,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        // The root already paints an opaque background across the screen.
        window.setBackgroundDrawable(null)
        // The sidebar folds away while browsing a section, giving the rows the full width.
        root.viewTreeObserver.addOnGlobalFocusChangeListener {_,now->
            if(now!=null)sidebarPanel.visibility=if(FocusController.isWithin(sidebarPanel,now))View.VISIBLE else View.GONE
            remoteDiagnostics.onFocus(diagnosticFocus(now))
        }
    }
    private fun diagnosticFocus(view:View?=currentFocus):FocusSnapshot {
        if(view==null)return FocusSnapshot(Pane.NONE)
        if(::sidebarPanel.isInitialized&&FocusController.isWithin(sidebarPanel,view))
            return FocusSnapshot(Pane.SIDEBAR,0,(sidebarViews.values.toList()+settingsButton).indexOfFirst{it===view})
        val pane=if(::contentSurface.isInitialized&&FocusController.isWithin(contentSurface,view))Pane.CONTENT else Pane.OTHER
        return RemoteNavigationDiagnostics.sampleFocus(view,pane)
    }
    private fun focusSidebar(){sidebarPanel.visibility=View.VISIBLE;sidebarViews[selected]?.requestFocus()}
    private var safeMode=false
    @android.annotation.SuppressLint("RestrictedApi") // Lint misreads overriding androidx.core's Activity hook as calling it.
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(safeMode)return super.dispatchKeyEvent(event)
        if(remoteDiagnostics.isRecording())remoteDiagnostics.onKey(event,diagnosticFocus())
        if(event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT&&event.action==KeyEvent.ACTION_UP&&
            rowExitPress.up(event.deviceId,event.downTime))return true
        if(event.action==KeyEvent.ACTION_DOWN&&::sidebarPanel.isInitialized) {
            val inSidebar=currentFocus?.let{FocusController.isWithin(sidebarPanel,it)}==true
            when(event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT->{
                    val edge=if(inSidebar)null else panel.focusedFirstCardRow()
                    when(rowExitPress.down(edge,event.deviceId,event.downTime,event.repeatCount)) {
                        LeftPressAction.EXIT->{cancelContentEntry();focusSidebar();return true}
                        LeftPressAction.STAY->return true
                        LeftPressAction.MOVE->Unit
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN->if(inSidebar) {
                    // Own the whole step: framework focus search and held-key repeats cannot skip a section.
                    if(sidebarPress.accept(event.keyCode,event.downTime,event.repeatCount)) {
                        val buttons=sidebarViews.values.toList()+settingsButton
                        val current=buttons.indexOfFirst {it.hasFocus()}
                        val step=if(event.keyCode==KeyEvent.KEYCODE_DPAD_UP)-1 else 1
                        sidebarPress.target(current,buttons.size,step)?.let {buttons[it].requestFocus()}
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    private fun buildSidebar() {
        sidebar.removeAllViews();sidebarViews.clear()
        settings.order().forEach {id->
            val service=Services.get(id)
            val button=action("${service.symbol}   ${service.displayName}"){
                cancelContentEntry();debounce?.cancel()
                if(selected!=id)select(id)
                when(id) {
                    "kayo"->openSportsCentre()
                    "movies","series","starred"->enterContent(id)
                    "kids"->kidsPicker.requestFocus()
                    "catchup"->enterContent(id) // A section of several apps: OK browses them.
                    else->notifyMessage(router.openApp(service.packageName))
                }
            }.apply {
                textSize=14f;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(8),0);contentDescription="Service ${service.displayName}"
                setOnFocusChangeListener {view,focused->
                    view.background=Theme.shape(this@MainActivity,if(focused||selected==id)Theme.selected else Theme.sidebar,focused)
                    if(focused){cancelContentEntry();debounce?.cancel();debounce=lifecycleScope.launch {delay(if(lightweightLauncher)50 else 120);if(selected!=id)select(id)}}
                }
                setOnKeyListener {_,key,event->if(key==KeyEvent.KEYCODE_DPAD_RIGHT&&event.action==KeyEvent.ACTION_DOWN){enterContent(id);true}else false}
            }
            sidebar.addView(button,LinearLayout.LayoutParams(-1,dp(37)).apply {bottomMargin=dp(3)});sidebarViews[id]=button
        }
    }
    /** Text and controls keep a right margin; only the content rows run to the screen edge. */
    private fun edge(width:Int,height:Int)=LinearLayout.LayoutParams(width,height).apply {rightMargin=dp(20)}
    private fun action(label:String,run:()->Unit)=Theme.label(this,13f,true).apply {
        id=View.generateViewId();text=label;contentDescription=label;isFocusable=true;isFocusableInTouchMode=true;isClickable=true;gravity=Gravity.CENTER
        background=Theme.shape(this@MainActivity);setOnFocusChangeListener {v,f->Theme.focus(v,f);if(f)cancelContentEntry()};setOnClickListener {run()}
        setOnKeyListener {view,key,event->if(key==KeyEvent.KEYCODE_DPAD_LEFT&&event.action==KeyEvent.ACTION_DOWN){if(FocusController.isWithin(sidebarPanel,view))sidebarViews[selected]?.requestFocus();true}else false}
    }
    private fun cancelContentEntry(){if(::kidsPicker.isInitialized)kidsPicker.cancelPendingDraw();pendingContentService=null;if(::panel.isInitialized)panel.cancelPendingFocus()}
    private fun openSportsCentre(){cancelContentEntry();debounce?.cancel();if(selected!="kayo")select("kayo");startActivity(Intent(this,com.majendra.tvlauncher.sportscentre.SportsCentreActivity::class.java))}
    private fun enterContent(id:String){if(id=="kayo"){openSportsCentre();return};if(id=="kids"){debounce?.cancel();if(selected!=id)select(id);cancelContentEntry();kidsPicker.requestFocus();return};debounce?.cancel();pendingContentService=id;enterAtStart=true;if(selected!=id)select(id)
        // From the sidebar, always start at the first card. Jumping to a remembered card deep in a row
        // scrolled it to the far end, and could strand focus in the sidebar while that card loaded.
        if(panel.focusContent(null))pendingContentService=null else if(lastResult!=null&&uiJob?.isActive!=true&&renderJob?.isActive!=true)focusEmptyAction()}
    private fun focusEmptyAction(){cancelContentEntry();if(permission.visibility==View.VISIBLE)permission.requestFocus()else if(searchControls.visibility==View.VISIBLE)searchControls.requestFocus()else settingsButton.requestFocus()}
    private fun allowListings(){requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"),17)}
    private fun select(id:String,force:Boolean=false) {
        invalidateRender()
        val changed=selected!=id
        if(changed){upcomingJob?.cancel();upcomingJob=null;starredJob?.cancel();starredJob=null}
        detailJob?.cancel();previewSettle?.cancel();detailGeneration++
        if(changed)kidsPicker.cancelPendingDraw()
        if(changed&&pendingContentService!=id)cancelContentEntry()
        if(changed){visitSeed=System.nanoTime();sectionEnteredAt=System.currentTimeMillis();upcomingShown=Unit}
        // Opening the section is a new visit: its rows may read a different deeper page of each genre. Loads within the visit keep theirs.
        if(changed&&MovieFilterPolicy.type(id)!=null)(repository.adapter(id) as? StremioDiscoveryProvider)?.newVisit()
        progressAt=System.currentTimeMillis()
        selected=id;settings.select(id);title.text=Services.get(id).displayName
        val sports=id=="kayo"
        sportsPreview.visibility=if(sports)View.VISIBLE else View.GONE
        for(view in listOf(heading,details,panel,navigationHint))view.visibility=if(sports)View.GONE else View.VISIBLE
        val kids=id=="kids"
        val streaming=StreamingBrowsePolicy.enabled(id)
        root.setBackgroundColor(if(sports)com.majendra.tvlauncher.sportscentre.SportsUi.BACKGROUND else if(kids)KidsTheme.background else if(streaming)android.graphics.Color.rgb(18,18,18)else Theme.background)
        contentSurface.setPadding(if(kids)dp(10)else 0,0,if(kids)dp(10)else 0,0)
        title.setTextColor(if(kids)KidsTheme.navy else Theme.text)
        status.setTextColor(if(kids)KidsTheme.navy else Theme.muted)
        message.setTextColor(if(kids)KidsTheme.navy else Theme.muted)
        navigationHint.setTextColor(if(kids)KidsTheme.navy else Theme.muted)
        navigationHint.text=if(kids)"◀ ▶  Look around      OK  Watch"else HINT
        val searchable=StreamingBrowsePolicy.searchable(id)
        details.setStreamingMode(streaming)
        if(lightweightLauncher&&!streaming)showPreview(null) // Chromecast variant previews Movies, TV shows and Starred only.
        panel.setStreamingMode(streaming)
        searchControls.setStreamingMode(streaming)
        searchControls.visibility=if(id in setOf("movies","series","youtube","starred"))View.VISIBLE else View.GONE
        searchControls.render(Services.get(id).displayName,queries[id].orEmpty(),when(id){"movies"->SearchControls.Mode.PICKER;"youtube"->SearchControls.Mode.YOUTUBE;"starred"->SearchControls.Mode.STARRED;else->SearchControls.Mode.SEARCH},
            filters[id]?.let(MovieFilterPolicy::summary),MovieFilterPolicy.available(id))
        if(id!="series"||!browsing(id)){upcomingJob?.cancel();upcomingJob=null}
        else startUpcomingEpisodes(force)
        kidsPicker.visibility=if(id=="kids")View.VISIBLE else View.GONE
        // A compact preview leaves room for more rows on screen.
        if(!streaming)details.compact(true)
        details.layoutParams=(details.layoutParams as LinearLayout.LayoutParams).apply{height=dp(if(streaming)112 else 96)}
        if(changed){lastResult=null;latestRenderInput=null;focusedItem=null;showPreview(null);panel.render(FeedResult());message.text="Loading content…";message.visibility=View.VISIBLE;status.text="Loading"}
        sidebarViews.forEach {(key,v)->v.background=Theme.shape(this,if(key==id)Theme.selected else Theme.sidebar,v.hasFocus())}
        uiJob?.cancel();filterJob?.cancel()
        if(sports)render(FeedResult(status=FeedStatus.LOADING))
        uiJob=lifecycleScope.launch {
            if(!settings.feedVisible(id)){render(FeedResult(status=FeedStatus.UNSUPPORTED,message="This feed is hidden in Settings. Sidebar OK still opens ${Services.get(id).displayName}."));return@launch}
            if(id=="starred"){render(withContext(Dispatchers.IO){choices.starredFeed()});enrichStarred();return@launch}
            filters[id]?.let{startFilter(id,it);return@launch}
            val query=queries[id].orEmpty()
            if(searchable&&query.isNotBlank()) {
                render(FeedResult(status=FeedStatus.LOADING,message="Searching…"))
                val result=if(id=="youtube")(repository.adapter(id) as com.majendra.tvlauncher.providers.YouTubeProvider).search(query)
                    else {repository.syncStremioAccount();(repository.adapter(id) as StremioDiscoveryProvider).search(query)}
                if(selected==id&&queries[id]==query)render(result)
                return@launch
            }
            val cached=repository.cached(id)
            // Movies and TV shows are several dozen catalogue requests: reuse the copy in memory or on disk until it is a day old.
            // The manual refresh forces; a refresh this screen walked away from is still joined.
            val held=!force&&id !in unfinishedRefresh&&!RefreshPolicy.fetchOnEntry(id,cached,System.currentTimeMillis())
            if(cached!=null&&(lastResult==null||sports||held))render(cached,feedUpdate=true)
            if(held) {
                // Popular now is the one row read again while the rest is held: one request, once it is an hour old.
                // Beside the history read below, not after it; both end with this job when the section is left.
                launch {
                    val popular=try {repository.refreshPopular(id)}
                        catch(e:CancellationException){throw e}
                        catch(_:Exception){null}
                    if(popular!=null&&selected==id&&browsing(id))render(popular,feedUpdate=true)
                }
                // Held rows, in memory or restored from disk, make no account request of their own. Continue watching
                // comes from the linked account's history, which a restarted launcher has not read yet; the read is
                // reused for five minutes, so a held visit normally costs nothing.
                if(id=="movies"&&browsing(id)) {
                    val before=choices.continueMovies(id,true)
                    try {withContext(Dispatchers.IO){com.majendra.tvlauncher.sync.StremioHistorySource.shared(this@MainActivity).load(emptySet())}}
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){}
                    if(selected==id&&choices.continueMovies(id,true)!=before)(latestRenderInput?:lastResult)?.let{render(it)}
                }
                return@launch
            }
            if(cached==null&&lastResult==null){message.text="Loading content…";message.visibility=View.VISIBLE;status.text="Loading"}
            val updates=launch {repository.updates(id).filterNotNull().collect {if(selected==id)render(it,feedUpdate=true)}}
            try {
                unfinishedRefresh.add(id)
                val result=try {repository.refresh(id,force).await().also{unfinishedRefresh.remove(id)}}
                catch(e:CancellationException){currentCoroutineContext().ensureActive();unfinishedRefresh.remove(id);FeedResult(status=FeedStatus.SOURCE_UNAVAILABLE,message="Content refresh was interrupted. Use Settings to refresh.")}
                catch(_:Exception){unfinishedRefresh.remove(id);FeedResult(status=FeedStatus.SOURCE_UNAVAILABLE,message="Content is unavailable. Sidebar OK still opens the app.")}
                if(selected==id)render(result,feedUpdate=true)
            }finally {updates.cancel()}
        }
    }
    private data class RenderInput(val context:RenderContext,val feed:FeedResult,
        val ratings:Map<String,Map<String,String>>,val liveEnabled:Boolean,val filter:MovieFilter?=null)
    /** [householdRows] are the visible rows built from local watched state, which a title's source rating never reaches. */
    private data class RenderProjection(val result:FeedResult,val unwatched:FeedResult,val visible:FeedResult,
        val householdRows:Set<String> = emptySet())
    /** Normal rows: neither a search nor a filter in this section. */
    private fun browsing(id:String)=queries[id].isNullOrBlank()&&id !in filters
    private fun renderContext()=RenderContext(selected,filters[selected]?.let(MovieFilterPolicy::token)?:queries[selected].orEmpty(),visitSeed,
        tonightSeeds[selected]?:0L,StremioAccountStore(this).generation)
    private fun invalidateRender(){renderGate.invalidate();renderJob?.cancel();renderJob=null;lastFeedRequest=null;lastFeedContext=null;lastProjection=null}
    private fun render(unfiltered:FeedResult,feedUpdate:Boolean=false) {
        if(lightweightLauncher&&!renderForeground)return
        val context=renderContext()
        // The repository replays its latest result to every new collector: the same object in the same context
        // is already on screen (or on its way), so it is not projected again.
        if(feedUpdate&&unfiltered===lastFeedRequest&&context==lastFeedContext)return
        if(feedUpdate){lastFeedRequest=unfiltered;lastFeedContext=context}
        else {lastFeedRequest=null;lastFeedContext=null}
        // A local choice can change while a newer feed is still being projected.
        val feed=if(!feedUpdate&&unfiltered===lastResult)latestRenderInput?:unfiltered else unfiltered
        latestRenderInput=feed
        val input=RenderInput(context,feed,ratingOverlay.toMap(),liveTvEnabled,filters[selected])
        // Both variants project off the UI thread: a section is a thousand or more titles passed through
        // several local watched filters. A newer request supersedes one still in flight, and the gate drops
        // any result whose section, search or account has changed underneath it.
        val ticket=renderGate.next(context)
        renderJob?.cancel()
        // Chromecast variant also spaces out feed updates and never draws while it is not in front.
        val wait=if(lightweightLauncher&&feedUpdate&&lastResult?.rows?.any{it.items.isNotEmpty()}==true)
            (120L-(SystemClock.uptimeMillis()-lastRenderAt)).coerceAtLeast(0L) else 0L
        renderJob=lifecycleScope.launch {
            if(wait>0)delay(wait)
            val projection=withContext(Dispatchers.IO){renderMutex.withLock{ensureActive();projectRender(input)}}
            ensureActive()
            if(lightweightLauncher&&(!renderForeground||!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)))return@launch
            if(!renderGate.accepts(ticket,renderContext()))return@launch
            applyProjection(projection)
            lastRenderAt=SystemClock.uptimeMillis()
        }
    }
    private fun projectRender(input:RenderInput):RenderProjection {
        val section=input.context.section
        val browsing=input.context.query.isBlank()
        val incoming=LiveTvPolicy.filter(input.liveEnabled,input.feed)
        // Drawing a title does not add it to the local watched catalogue. Store only titles selected
        // for watched, watching or starred actions; scrolling past a title does not store it.
        if(section=="kayo")return RenderProjection(incoming,incoming,incoming)
        val filter=input.filter
        if(filter!=null) {
            // Filtered results arrive narrowed and in order. Choices made since the query still apply: Not interested
            // always, Seen unless this filter shows it, and star badges. No shuffle and no personal rows.
            val visible=if(filter.hideSeen)choices.apply(section,incoming,includePersonalRows=false,currentContinue=null) else {
                val dismissed=choices.excludedTitles(MovieFilterPolicy.type(section).orEmpty(),false)
                incoming.copy(rows=incoming.rows.map {row->row.copy(items=row.items.filterNot{"${it.metadata["type"]}:${it.id}" in dismissed}
                    .map {item->if(choices.isStarred(item))item.copy(metadata=item.metadata+("isStarred" to "true"))else item})})
            }
            return RenderProjection(incoming,incoming,visible)
        }
        val result=incoming.copy(rows=incoming.rows.map {row->row.copy(items=row.items.map {item->
            val key="${item.source}:${item.id}:${item.metadata["type"]}"
            input.ratings[key]?.let{item.copy(metadata=item.metadata+it)}?:item
        })})
        val shuffled=if(section in setOf("movies","series")&&browsing)result.copy(rows=result.rows.map{row->
            if(row.id==StremioGenrePolicy.topRowId(section)||row.id=="movies-continue-watching")row else row.copy(items=StremioGenrePolicy.shuffled(row.items,input.context.visitSeed){it.id})})else result
        val unwatched=if(section=="kids")kidsWatched.apply(shuffled)else shuffled
        val tonight=if(section in setOf("movies","series")&&browsing) {
            WatchTonightPolicy.prepend(section,"",unwatched,choices.starredItemsForEnrichment(),
                choices.watchTonightExcludedKeys(),choices.hiddenTitlePredicate(),{choices.isRowVisible(section,it)},input.context.tonightSeed)
        }else unwatched
        val withDetails=tonight.copy(rows=tonight.rows.map {row->if(row.id!=WatchTonightPolicy.ROW_ID)row else row.copy(items=row.items.map {item->
            val key="${item.source}:${item.id}:${item.metadata["type"]}"
            input.ratings[key]?.let{item.copy(metadata=item.metadata+it)}?:item
        })})
        val upcoming=if(section=="series"&&browsing)
            upcomingEpisodes.cachedRow(com.majendra.tvlauncher.sync.WatchedStore.shared(this).watching(),input.context.accountGeneration) else null
        val projected=if(upcoming==null)withDetails else withDetails.copy(rows=listOf(upcoming)+withDetails.rows.filterNot{it.id==upcoming.id})
        val resume=choices.continueMovies(section,browsing)
        val visible=choices.apply(section,projected,includePersonalRows=browsing,currentContinue=resume)
        return RenderProjection(result,unwatched,visible,
            if(resume==null)CardPatchPolicy.householdRows else CardPatchPolicy.householdRows+"movies-continue-watching")
    }
    /**
     * One title's rating arrived. Give it to that title's cards in what is already on screen, exactly as a
     * projection would, and leave every other row and card untouched. False when the screen is not settled.
     */
    private fun patchRating(source:String,id:String,rating:Map<String,String>):Boolean {
        val shown=lastProjection?:return false
        if(renderJob?.isActive==true||selected=="kayo")return false
        applyProjection(RenderProjection(CardPatchPolicy.withMetadata(shown.result,source,id,rating),
            CardPatchPolicy.withMetadata(shown.unwatched,source,id,rating),
            CardPatchPolicy.withMetadata(shown.visible,source,id,rating,shown.householdRows),shown.householdRows))
        return true
    }
    private fun applyProjection(projection:RenderProjection) {
        val result=projection.result
        val unwatched=projection.unwatched
        val visible=projection.visible
        lastResult=result
        lastProjection=projection
        if(selected=="kayo") {
            val incoming=result
            lastResult=incoming
            message.visibility=View.GONE;permission.visibility=View.GONE
            val app=application as LauncherApplication
            val sportsSnapshot=if(incoming.status==FeedStatus.LOADING&&incoming.rows.isEmpty())app.sportsData.latest?:app.sportsData.fromFeed(incoming)else app.sportsData.fromFeed(incoming)
            // The preview is a pure drawing of its snapshot: an equal snapshot would rebuild an identical view tree.
            if(sportsSnapshot==sportsShown&&sportsPreview.childCount>0)return
            sportsShown=sportsSnapshot
            val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
            box.addView(com.majendra.tvlauncher.sportscentre.SportsUi.heading(this,"SPORTS CENTRE",23f))
            box.addView(com.majendra.tvlauncher.sportscentre.SportsUi.label(this,"OK or Right  Open Sports Centre",12f,com.majendra.tvlauncher.sportscentre.SportsUi.MUTED),LinearLayout.LayoutParams(-1,dp(32)))
            val width=(resources.displayMetrics.widthPixels/resources.displayMetrics.density).toInt()-260
            box.addView(com.majendra.tvlauncher.sportscentre.SportsHomePage(this,width).render(sportsSnapshot,"All",{},{openSportsCentre()},{openSportsCentre()},showFilters=false),LinearLayout.LayoutParams(-1,0,1f))
            sportsPreview.removeAllViews();sportsPreview.addView(box,FrameLayout.LayoutParams(-1,-1))
            return
        }
        if(selected=="kids"&&(result.rows.any{it.id=="kids-movies"}||result.status==FeedStatus.NO_CONTENT))kidsPicker.update(visible)
        val hasContent=visible.rows.any {it.items.isNotEmpty()}
        val warning=result.error!=null||result.message.contains("refresh unavailable",ignoreCase=true)
        // Plain-language status; partial failures stay out of the way while content is showing.
        status.text=when(result.status){FeedStatus.LOADING->if(hasContent)"Updating…"else"Loading…";FeedStatus.UNSUPPORTED->"App shortcut";FeedStatus.SOURCE_UNAVAILABLE->"Can't connect";FeedStatus.PERMISSION_DENIED->"Needs permission";FeedStatus.AUTHENTICATION_REQUIRED->"Sign-in needed";FeedStatus.NO_CONTENT->"Nothing here yet";else->if(warning&&hasContent)"Some rows couldn't load"else""}
        // Filtered results say where they stand: loading more, more below, all shown, limited or unreachable.
        val filtered=filterWindows[selected]?.takeIf{selected in filters}
        if(filtered!=null)status.text=FilterPaging.label(filtered.status)
        // Drawing hides titles marked seen or not interested since they were shown. When it has hidden any, the results
        // loop takes them out of its window, so the count, the last rows and "near the end" are those of the screen.
        if(filtered!=null) {
            // Reported once per state of the window: the same counts on a window that has since changed are a new mismatch.
            val counts=Triple(visible.rows.sumOf{it.items.size},filtered.size,filtered.generation)
            if(counts.first!=counts.second&&counts!=filterMismatch)filterChanged.trySend(Unit)
            filterMismatch=counts.takeIf{it.first!=it.second}
        }
        val hidden=!hasContent&&unwatched.rows.any{it.items.isNotEmpty()}
        val watchedEmpty=selected=="kids"&&!hasContent&&!hidden&&result.rows.any{it.items.isNotEmpty()}
        message.text=when {filtered!=null->result.message;hidden->"Your content rows are hidden. Open Settings → Content rows & favourites.";watchedEmpty->"You've watched these titles! Restore watched titles in Settings → Content rows & favourites.";else->result.message.ifBlank{result.error?.message.orEmpty()}}
        message.visibility=if(hasContent)View.GONE else View.VISIBLE // Explanations only when there is nothing to show.
        permission.visibility=if(selected=="youtube"&&(result.status==FeedStatus.PERMISSION_DENIED||checkSelfPermission("android.permission.READ_TV_LISTINGS")!=PackageManager.PERMISSION_GRANTED))View.VISIBLE else View.GONE
        panel.render(visible)
        val items=visible.rows.flatMap{it.items}
        if(!panel.hasFocus()){focusedItem=items.firstOrNull{it.id==choices.lastFocused(selected)}?:items.firstOrNull();showPreview(focusedItem)}
        if(pendingContentService==selected){if(panel.focusContent(if(enterAtStart)null else choices.lastFocused(selected),if(enterAtStart)null else choices.lastFocusedRow(selected)))pendingContentService=null else if(result.status!=FeedStatus.LOADING)focusEmptyAction()}
    }
    private fun showPreview(item:ContentItem?) {
        // Chromecast variant keeps the preview to Movies, TV shows and Starred; its other sections stay without one.
        if(lightweightLauncher&&!StreamingBrowsePolicy.enabled(selected)){details.show(null);details.release();details.visibility=View.GONE;return}
        // Kids Corner has its own preview. Do not update an invisible generic preview,
        // start its artwork requests, or briefly show it and invalidate layout on focus.
        if(selected=="kids"){
            details.show(null)
            return
        }
        details.show(item)
    }
    private fun onFocused(row:String,item:ContentItem){
        val previous=focusedItem
        focusedItem=item;choices.rememberFocus(selected,item,row)
        // Focus near the end of filtered results asks for the next batch. Cards already shown never move.
        val filtered=selected in filters
        if(filtered&&filterWindows[selected]?.nearEnd(row)==true)filterDemand.trySend(Unit)
        if(lightweightLauncher) {
            // Chromecast variant draws nothing while a row is being crossed: the preview follows once focus has rested on a card.
            previewSettle?.cancel()
            if(!StreamingBrowsePolicy.enabled(selected))return
            if(previous!=item){detailJob?.cancel();detailGeneration++}
            val service=selected
            previewSettle=lifecycleScope.launch {
                delay(LIGHTWEIGHT_PREVIEW_SETTLE_MILLIS)
                if(selected==service&&focusedItem==item)describeFocused(item,item,filtered)
            }
            return
        }
        describeFocused(previous,item,filtered)
    }
    /** Shows the focused title in the preview and, where its source left them out, asks for its rating or description. */
    private fun describeFocused(previous:ContentItem?,item:ContentItem,filtered:Boolean){
        showPreview(item)
        if(previous!=item){detailJob?.cancel();detailGeneration++}
        val key="${item.source}:${item.id}:${item.metadata["type"]}"
        // The catalogue index keeps no descriptions: a filtered card asks for its own, from the shared memory cache when it can.
        val describe=filtered&&item.description.isNullOrBlank()
        if(item.source!="stremio"||!(describe||(item.metadata["imdbRating"].isNullOrBlank()&&detailAttempts.allowed(key))))return
        if(detailJob?.isActive==true&&previous==item)return
        detailJob?.cancel();val request=++detailGeneration;val service=selected
        val accountGeneration=StremioAccountStore(this).generation
        detailJob=lifecycleScope.launch {
            delay(180)
            val enriched=metadataClient.enrichItem(item)
            if(request!=detailGeneration||selected!=service||focusedItem?.id!=item.id||StremioAccountStore(this@MainActivity).generation!=accountGeneration)return@launch
            // A request that returned nothing new may only have failed: it gets a few more tries on later visits.
            if(!describe)detailAttempts.finished(key,enriched!=item)
            choices.enrichStarredMetadata(enriched)
            focusedItem=enriched;showPreview(enriched)
            if(describe)return@launch // Only the preview and the details dialog show the description; the cards are unchanged.
            val rating=enriched.metadata.filterKeys{it in setOf("imdbRating","imdbRatingSource","genres")}
            if(rating.isNotEmpty()) {
                ratingOverlay[key]=rating
                if(ratingOverlay.size>256)ratingOverlay.remove(ratingOverlay.keys.first())
            }
            if(rating.isNotEmpty())(latestRenderInput?:lastResult)?.let { old->
                val updated=CardPatchPolicy.withMetadata(old,item.source,item.id,rating)
                // The feed keeps the rating for every later projection. On a settled screen only this title's
                // cards are redrawn; otherwise it goes through render like any other change.
                if(patchRating(item.source,item.id,rating)){latestRenderInput=updated;lastFeedRequest=null;lastFeedContext=null}
                else render(updated)
            }
        }
    }
    private fun showDetails(item:ContentItem,watchingMenu:Boolean=true){
        val full=focusedItem?.takeIf{it.id==item.id&&it.source==item.source}?:item
        if(full.metadata["openDetails"]=="true"){FocusedDetailsView.dialog(this,full,false,false,{},{},openLabel="Done");return}
        if(watchingMenu&&selected!="kids"&&choices.canStar(full)){
            showWatchingActions(full);return
        }
        if(selected!="kids"&&choices.canHide(full)) {
            val hidden=choices.isHidden(full)
            val save=if(choices.canSave(full))(if(choices.isSaved(full))"Remove from My shows" else "Add to My shows") to {
                val adding=!choices.isSaved(full);choices.toggleSaved(full);lastResult?.let{render(it)}
                notifyMessage(if(adding)"Added to My shows, at the start of TV shows." else "Removed from My shows.")
            } else null
            FocusedDetailsView.dialog(this,full,false,false,{play(full)},{},
                neutral=(if(hidden)"Restore Seen title" else "Mark as seen") to {
                    choices.toggleHidden(full);lastResult?.let{render(it)}
                    if(!hidden)notifyMessage("Marked as seen. Restore in Settings → Content rows & favourites.")
                },negative=save)
            return
        }
        FocusedDetailsView.dialog(this,full,choices.isFavorite(full),choices.canFavorite(full),{play(full)},{choices.toggleFavorite(full);lastResult?.let{render(it)}},openLabel=if(selected=="kids"&&kidsPicker.isChoosing)"Choose this ${kidsPicker.kind?.noun?:"one"}"else"Open",
            onWatched=if(selected=="kids"&&kidsWatched.canMarkWatched(full))({kidsWatched.markWatched(full);lastResult?.let{render(it)};notifyMessage("Hidden from Kids Corner. Restore watched titles in Settings → Content rows & favourites.")})else null)
    }
    private fun showWatchingActions(item:ContentItem){
        val current=watchedSync.watchingEntry(item)
        val actions=com.majendra.tvlauncher.sync.WatchingActions.options(selected,item,current)
        val canStar=selected!="kids"&&choices.canStar(item)
        val canDismiss=selected!="kids"&&choices.canDismiss(item)
        if(actions.isEmpty()&&!canStar&&!canDismiss)return
        val title=item.title+(current?.progress?.let{"\nUp next ${it.label()}"}?:"")
        val personalLabels=(if(canStar)listOf(if(choices.isStarred(item))"Unstar" else "Star")else emptyList())+
            (if(canDismiss)listOf(if(choices.isNotInterested(item))"Show recommendations again" else "Not interested")else emptyList())
        val labels=personalLabels+
            (if(actions.isEmpty())listOf("Open","Other options")else actions.map{com.majendra.tvlauncher.sync.WatchingActions.label(it,current)})
        AlertDialog.Builder(this).setTitle(title).setItems(labels.toTypedArray()){_,index->
            if(canStar&&index==0){
                val adding=!choices.isStarred(item)
                if(!choices.toggleStarred(item)){
                    notifyMessage("Starred is full (100 titles). Unstar a title before adding another.")
                    return@setItems
                }
                if(selected=="starred")render(choices.starredFeed())else lastResult?.let{render(it)}
                notifyMessage(if(adding)"Added to Starred." else "Removed from Starred.")
                return@setItems
            }
            if(canDismiss&&index==(if(canStar)1 else 0)){
                val removing=choices.isNotInterested(item)
                choices.toggleNotInterested(item)
                if(choices.isNotInterested(item)==removing){notifyMessage("Not interested could not be changed.");return@setItems}
                refreshLocalContent()
                notifyMessage(if(removing)"Recommendations restored." else "Removed from recommendations. Seen is unchanged.")
                return@setItems
            }
            val actionIndex=index-personalLabels.size
            if(actions.isEmpty()){
                if(actionIndex==0)play(item)else showDetails(item,false)
                return@setItems
            }
            fun changed(success:Boolean,message:String,forceHistory:Boolean=false){
                notifyMessage(if(success)message else "The change could not be saved securely. Try again.")
                if(success)startWatchedPolling(forceHistory)
            }
            when(actions[actionIndex]){
                com.majendra.tvlauncher.sync.WatchingAction.OPEN->play(item)
                com.majendra.tvlauncher.sync.WatchingAction.ADD->changed(watchedSync.setWatching(item,true),"Added to Watching.")
                com.majendra.tvlauncher.sync.WatchingAction.REMOVE->changed(watchedSync.setWatching(item,false),"Removed from Watching.")
                com.majendra.tvlauncher.sync.WatchingAction.SET_EPISODE->showEpisodeEditor(item)
                com.majendra.tvlauncher.sync.WatchingAction.WATCHED_EPISODE->{
                    val success=watchedSync.advanceEpisode(item)
                    if(success)changed(true,"Up next ${watchedSync.watchingEntry(item)?.progress?.label().orEmpty()}.")
                    else notifyMessage("Set the next episode manually before advancing it.")
                }
                com.majendra.tvlauncher.sync.WatchingAction.CLEAR_EPISODE->changed(watchedSync.clearEpisode(item),"Episode progress cleared.")
                com.majendra.tvlauncher.sync.WatchingAction.USE_STREMIO->changed(watchedSync.useStremioProgress(item),"Stremio progress selected.",true)
                com.majendra.tvlauncher.sync.WatchingAction.DETAILS->showDetails(item,false)
            }
        }.setNegativeButton("Close",null).show()
    }
    private fun showEpisodeEditor(item:ContentItem){
        val current=watchedSync.watchingEntry(item)?.takeIf{it.watching}?:return
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(8),dp(24),dp(8))}
        body.addView(TextView(this).apply{text="Choose the next episode to watch. This does not start playback.";textSize=16f})
        val season=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;isSingleLine=true;setSelectAllOnFocus(true);setText((current.progress?.season?:1).toString());contentDescription="Season"}
        val episode=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;isSingleLine=true;setSelectAllOnFocus(true);setText((current.progress?.episode?:1).toString());contentDescription="Next episode"}
        body.addView(TextView(this).apply{text="Season (0–99)"});body.addView(season)
        body.addView(TextView(this).apply{text="Next episode (1–999)"});body.addView(episode)
        val dialog=AlertDialog.Builder(this).setTitle("Next episode · ${item.title}").setView(body)
            .setNegativeButton("Cancel",null).setNeutralButton("Clear",null).setPositiveButton("Save",null).create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val s=season.text.toString().toIntOrNull();val e=episode.text.toString().toIntOrNull()
                if(s==null||s !in 0..99||e==null||e !in 1..999){notifyMessage("Use season 0–99 and episode 1–999.");return@setOnClickListener}
                if(watchedSync.setEpisode(item,s,e)){notifyMessage("Up next S$s E$e.");dialog.dismiss();startWatchedPolling()}
                else notifyMessage("The episode could not be saved securely. Try again.")
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{
                if(watchedSync.clearEpisode(item)){notifyMessage("Episode progress cleared.");dialog.dismiss();startWatchedPolling()}
                else notifyMessage("The episode could not be saved securely. Try again.")
            }
        }
        dialog.show()
    }
    private fun play(item:ContentItem,bypassPicker:Boolean=false){
        if(item.metadata["openDetails"]=="true"){showDetails(item);return}
        // ABC Kids Live always opens, even while a child is choosing a movie.
        if(!bypassPicker&&selected=="kids"&&kidsPicker.isChoosing&&item.metadata["type"]!="channel"){
            if(!kidsPicker.choose(item))notifyMessage(if(kidsPicker.kind==KidsPickKind.SHOW)"Choose a show from the Shows row." else "Choose a movie from the Movies row.")
            return
        }
        lifecycleScope.launch {
            val result=withContext(Dispatchers.IO){repository.adapter(item.source).resolvePlayback(item)}
            val target=when(result){is PlaybackResult.Ready->result.packageName;is PlaybackResult.App->result.packageName;else->null}?:Services.get(item.source).packageName
            com.majendra.tvlauncher.navigation.LiveBackPolicy.arm(this@MainActivity,target==com.majendra.tvlauncher.navigation.LiveBackPolicy.TV_PACKAGE)
            notifyMessage(router.open(result,target,item))
            if(result is PlaybackResult.Unavailable&&item.source=="kids"&&item.metadata["type"]=="channel"&&selected=="kids")select("kids",true)
        }
    }
    private fun browseKids(kind:KidsPickKind){
        cancelContentEntry()
        val first=lastResult?.let{choices.apply("kids",kidsWatched.apply(it))}?.rows?.flatMap{it.items}?.firstOrNull{KidsPickPolicy.eligible(it,kind)}
        if(first!=null)panel.focusContent(first.id)else notifyMessage("No visible unwatched ${kind.noun}s. Check Kids Corner rows and watched titles in Settings.")
    }
    private fun submitSearch(query:String){
        if(!StreamingBrowsePolicy.searchable(selected))return
        queries[selected]=query.take(200);if(query.isNotBlank()){filters.remove(selected);filterWindows.remove(selected)} // A search replaces a filter.
        lastResult=null;latestRenderInput=null;focusedItem=null
        panel.render(FeedResult());showPreview(null);detailAttempts.clear();select(selected,true)
    }
    private fun openStremioSearch(){
        val query=queries[selected].orEmpty().takeIf{it.isNotBlank()}?:return
        notifyMessage(router.open(PlaybackResult.Ready("stremio:///search?search=${android.net.Uri.encode(query)}","com.stremio.one"),"com.stremio.one"))
    }
    private fun notifyMessage(text:String?){if(text!=null)Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun showSettings(){settings.show(this,{buildSidebar();focusSidebar();select(selected,true)},{if(selected=="youtube"&&checkSelfPermission("android.permission.READ_TV_LISTINGS")!=PackageManager.PERMISSION_GRANTED)allowListings()else select(selected,true)},{notifyMessage(router.openApp(HomeCompatibility.GOOGLE_PACKAGE))},onRows={showContentChoices()})}
    private fun showContentChoices(){
        val type=mapOf("movies" to "movie","series" to "series")[selected]
        val options=when {selected=="kids"->arrayOf("Choose visible rows","Restore watched titles (${kidsWatched.count()})");type!=null->arrayOf("Choose visible rows","Restore Seen titles (${choices.hiddenCount(type)})","Starred & Not interested");else->arrayOf("Choose visible rows","Favourite channels","Starred & Not interested")}
        AlertDialog.Builder(this).setTitle("${Services.get(selected).displayName} content").setItems(options){_,which->
            when {which==0->showRows();which==2->showLocalPreferences();selected=="kids"->{kidsWatched.clear();lastResult?.let{render(it)};notifyMessage("Watched titles are back in Kids Corner.")}
                type!=null->{choices.restoreHidden(type);lastResult?.let{render(it)};notifyMessage("Hidden titles are back.")}
                else->showFavourites()}
        }.setNegativeButton("Close",null).show()
    }
    private fun showRows(){
        val generated=when(selected){"series"->listOf(ContentRow("watch-tonight","Watch tonight",emptyList()),ContentRow("watching-shows","Watching",emptyList()),ContentRow("upcoming-episodes","Upcoming episodes",emptyList()),ContentRow("saved-shows","My shows",emptyList()));"movies"->listOf(ContentRow("watch-tonight","Watch tonight",emptyList()),ContentRow("movies-continue-watching","Continue watching",emptyList()));else->emptyList()}
        // Filtered results are one grid, not rows to hide or show.
        val rows=(generated+lastResult?.takeIf{selected !in filters}?.rows.orEmpty().let {values->if(values.any{row->row.items.any{choices.canFavorite(it)}})listOf(ContentRow("favourites","Favourite channels",emptyList()))+values else values}).distinctBy{it.id}
        if(rows.isEmpty()){notifyMessage("Wait for content to load, then choose its rows.");return}
        AlertDialog.Builder(this).setTitle("Visible rows").setMultiChoiceItems(rows.map{it.title}.toTypedArray(),rows.map{choices.isRowVisible(selected,it.id)}.toBooleanArray()){_,index,checked->choices.setRowVisible(selected,rows[index].id,checked)}.setPositiveButton("Done"){_,_->lastResult?.let{render(it)}}.show()
    }
    private fun showFavourites(){
        val items=lastResult?.rows.orEmpty().flatMap{it.items}.filter{choices.canFavorite(it)}.distinctBy{it.metadata["channelId"]?:it.metadata["channelName"]}
        if(items.isEmpty()){notifyMessage("This service has no available TV channels to favourite.");return}
        AlertDialog.Builder(this).setTitle("Favourite channels").setMultiChoiceItems(items.map{it.metadata["channelName"]?:it.title}.toTypedArray(),items.map{choices.isFavorite(it)}.toBooleanArray()){_,index,_->choices.toggleFavorite(items[index])}.setPositiveButton("Done"){_,_->lastResult?.let{render(it)}}.show()
    }
    private fun showExit(){AlertDialog.Builder(this).setTitle("${getString(com.majendra.tvlauncher.R.string.app_name)}").setItems(exitChoices()){_,i->when(exitChoices()[i]){"Open original Google TV"->notifyMessage(router.openApp(HomeCompatibility.GOOGLE_PACKAGE));"TV settings"->com.majendra.tvlauncher.settings.SystemSettings.show(this)}}.show()}
    /** Google TV is only offered while its home is enabled; TV settings always are. */
    private fun exitChoices()=listOfNotNull("Stay here","Open original Google TV".takeIf{com.majendra.tvlauncher.settings.SystemSettings.googleHomeAvailable(this)},"TV settings").toTypedArray()
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<String>,grantResults:IntArray){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==17&&!safeMode)select(selected,true)}
    override fun onResume(){
        super.onResume()
        if(safeMode)return
        renderForeground=true
        watchedUpdates?.cancel()
        watchedUpdates=lifecycleScope.launch {watchedSync.changes.collect {if(::panel.isInitialized&&selected in setOf("movies","series","kids","stremio"))(latestRenderInput?:lastResult)?.let{render(it)}}}
        com.majendra.tvlauncher.navigation.LiveBackPolicy.arm(this,false)
        sectionEnteredAt=System.currentTimeMillis()
        if(::panel.isInitialized){if(returnToContent){pendingContentService=selected;enterAtStart=false};if(uiJob?.isActive!=true)select(selected);scheduler.start{refreshTick()}}
    }
    /** Once a second while in front: refetch when the section's policy says so, otherwise keep programme bars moving. */
    private fun refreshTick() {
        if(!browsing(selected)||uiJob?.isActive==true||renderJob?.isActive==true)return
        val now=System.currentTimeMillis()
        if(RefreshPolicy.due(selected,lastResult,now,repository.nextRetryAt(selected),sectionEnteredAt)){select(selected);return}
        // Schedules used to be refetched every 30 seconds largely to advance these bars; that needs no network.
        if(!RefreshPolicy.scheduled(selected)||now-progressAt<RefreshPolicy.TICK_MILLIS)return
        progressAt=now
        (latestRenderInput?:lastResult)?.let {shown->RefreshPolicy.advance(shown,now).takeIf{it!==shown}?.let{render(it)}}
    }
    override fun onPause(){if(safeMode){super.onPause();return};renderForeground=false;invalidateRender();upcomingJob?.cancel();upcomingJob=null;starredJob?.cancel();starredJob=null;watchedPoll?.cancel();watchedPoll=null;watchedUpdates?.cancel();watchedUpdates=null;detailJob?.cancel();previewSettle?.cancel();detailGeneration++;kidsPicker.cancelPendingDraw();if(::panel.isInitialized)returnToContent=panel.hasFocus();cancelContentEntry();uiJob?.cancel();uiJob=null;filterJob?.cancel();filterJob=null;window.decorView.removeCallbacks(clearRecoveryBypass);scheduler.stop();super.onPause()}
    private fun startWatchedPolling(forceHistory:Boolean=false){
        if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
        watchedPoll?.cancel()
        watchedPoll=lifecycleScope.launch{
            var force=forceHistory
            while(isActive&&lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)){watchedSync.syncOnce(forceHistory=force);force=false;delay(if(lightweightLauncher)60_000L else 20_000L)}
        }
    }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);window.decorView.removeCallbacks(clearRecoveryBypass);if(hasFocus)window.decorView.postDelayed(clearRecoveryBypass,300)}
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);if(!safeMode)openRequestedSection(intent)}
    /** A remapped remote button asks for a section; open it and move focus into its content. */
    private fun openRequestedSection(intent:Intent?) {
        val sidebarOnly=intent?.getStringExtra(EXTRA_SIDEBAR)
        val id=(sidebarOnly?:intent?.getStringExtra(EXTRA_SECTION))?.takeIf { it in sidebarViews }?:return
        intent?.removeExtra(EXTRA_SECTION)
        intent?.removeExtra(EXTRA_SIDEBAR)
        // The button chooses where to land: don't let onResume restore the last focused card.
        returnToContent=false
        cancelContentEntry();debounce?.cancel()
        sidebarPanel.visibility=View.VISIBLE;sidebarViews[id]?.requestFocus()
        if(sidebarOnly!=null){if(selected!=id)select(id);focusSidebar()}else enterContent(id)
    }
    override fun onDestroy(){if(::details.isInitialized)details.release();super.onDestroy()}
    override fun onSaveInstanceState(outState:Bundle){outState.putString("service",selected);super.onSaveInstanceState(outState)}
    private fun refreshMix(){queries.remove(selected);lastResult=null;latestRenderInput=null;focusedItem=null;showPreview(null);panel.render(FeedResult());select(selected,true)}
    private fun refreshTonight(){
        if(selected !in setOf("movies","series")||!browsing(selected))return
        tonightSeeds[selected]=(tonightSeeds[selected]?:0L)+1L
        lastResult?.let{render(it)}
        notifyMessage("Watch tonight refreshed.")
    }
    private fun refreshLocalContent(){
        if(selected=="starred")render(choices.starredFeed())else lastResult?.let{render(it)}
    }
    private fun showLocalPreferences(){choices.showPreferencesDialog(this){refreshLocalContent();if(selected=="starred")enrichStarred()}}
    private fun showFilter(){
        val section=selected
        val type=MovieFilterPolicy.type(section)?.takeIf{MovieFilterPolicy.available(section)}?:return
        val year=java.time.LocalDate.now().year
        val genres=catalogue.genres(type)
        // The dialog opens on the active filter, or on the one last used in this section.
        val start=filters[section]?:MovieFilterPolicy.decode(choices.savedFilter(section))?:MovieFilter()
        MovieFilterDialog(this,section,genres,year,section in filters,{applyFilter(section,it)},{if(selected==section)clearFilter()})
            .show(MovieFilterPolicy.normalise(start,genres,year))
    }
    private fun applyFilter(section:String,filter:MovieFilter){
        if(selected!=section||!MovieFilterPolicy.available(section))return
        if(filters[section]!=filter)filterWindows.remove(section)
        filters[section]=filter;queries.remove(section) // A filter replaces a search.
        choices.saveFilter(section,MovieFilterPolicy.encode(filter))
        lastResult=null;latestRenderInput=null;focusedItem=null;panel.render(FeedResult());showPreview(null);select(section,true)
    }
    /** Back to the section's normal rows. */
    private fun clearFilter(){
        if(filters.remove(selected)==null)return
        filterWindows.remove(selected)
        lastResult=null;latestRenderInput=null;focusedItem=null;panel.render(FeedResult());showPreview(null);select(selected)
    }
    /**
     * Filtered results from the whole indexed catalogue: what is indexed now, then more as the catalogue is read in
     * the background. Cards are only ever appended, a bounded number at most, and Seen and Not interested are
     * passed to each query rather than stored. Runs until the section, the filter or the screen changes.
     */
    private fun startFilter(section:String,filter:MovieFilter){
        val type=MovieFilterPolicy.type(section)?:return
        val columns=FilterPaging.columns((resources.displayMetrics.widthPixels/resources.displayMetrics.density).toInt())
        // The same filter keeps its cards: returning from a title, or from another section, finds them where they were.
        val window=filterWindows[section]?.takeIf{it.filter==filter&&it.columns==columns}?:FilterWindow(section,filter,columns).also{filterWindows[section]=it}
        filterJob?.cancel()
        filterJob=lifecycleScope.launch {
            FilterResults(window,type,catalogue,{withContext(Dispatchers.IO){choices.excludedTitles(type,filter.hideSeen)}},filterDemand,filterChanged,
                {feed->if(selected==section&&filters[section]==filter)render(feed)}).run()
        }
    }
    private fun enrichStarred(){
        if(selected!="starred"||starredJob?.isActive==true||!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
        val generation=StremioAccountStore(this).generation
        starredJob=lifecycleScope.launch {
            // At a cold start in Starred this is the first use of the preferences store: read it off the UI thread.
            val items=withContext(Dispatchers.IO){choices.starredItemsForEnrichment()}
            withTimeoutOrNull(20_000){
                for(batch in items.chunked(2))coroutineScope {
                    batch.map { item->async {
                        val enriched=metadataClient.enrichItem(item)
                        ensureActive()
                        if(selected=="starred"&&StremioAccountStore(this@MainActivity).generation==generation)
                            choices.enrichStarredMetadata(enriched)
                    }}.awaitAll()
                }
            }
            // Never reorder an actively browsed list when metadata arrives.
            if(selected=="starred"&&!panel.hasFocus()&&StremioAccountStore(this@MainActivity).generation==generation)
                render(choices.starredFeed())
        }
    }
    private fun startUpcomingEpisodes(force:Boolean=false){
        if(selected!="series"||!browsing(selected)||!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))return
        if(upcomingJob?.isActive==true&&!force)return
        upcomingJob?.cancel()
        val generation=StremioAccountStore(this).generation
        upcomingJob=lifecycleScope.launch {
            var refreshing=force
            while(isActive&&selected=="series"&&browsing(selected)){
                val row=upcomingEpisodes.refresh(com.majendra.tvlauncher.sync.WatchedStore.shared(this@MainActivity).watching(),generation,refreshing).row
                ensureActive()
                if(StremioAccountStore(this@MainActivity).generation!=generation)break
                // The pass repeats every 30 seconds; the whole section is only drawn again when the row it produces changes.
                if(row!=upcomingShown){upcomingShown=row;lastResult?.let{render(it)}}
                refreshing=false;delay(if(lightweightLauncher)120_000L else 30_000L)
            }
        }
    }
    companion object {
        const val EXTRA_SECTION="com.majendra.tvlauncher.opensource.extra.SECTION"
        const val EXTRA_SIDEBAR="com.majendra.tvlauncher.opensource.extra.SIDEBAR"
        /** Longer than a held D-pad's repeat interval: crossing a row redraws nothing until the remote stops. */
        private const val LIGHTWEIGHT_PREVIEW_SETTLE_MILLIS=250L
        private const val HINT="OK  Open      Hold OK  More      ◀ at first card  Menu      Back  Menu"
    }
}
