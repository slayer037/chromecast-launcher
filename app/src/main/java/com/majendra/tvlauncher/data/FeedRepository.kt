package com.majendra.tvlauncher.data

import android.content.Context
import com.majendra.tvlauncher.providers.*
import com.majendra.tvlauncher.providers.sports.SportsCentreProvider
import com.majendra.tvlauncher.auth.StremioAccountStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Constructing this does no I/O and builds no source: the first screen touches it synchronously on the main thread. */
class FeedRepository(private val context:Context, private val scope:CoroutineScope) {
    private val cache=FeedCache(context)
    // Each source is built by its first use, so opening one section does not construct all of them.
    private val live=lazy { LiveTvProvider(context) }
    private val adapters:Map<String,Lazy<FeedAdapter>> = buildMap {
        put("kids",lazy { KidsCornerProvider(context) });put("movies",lazy { StremioDiscoveryProvider(context,"movies","movie") });put("series",lazy { StremioDiscoveryProvider(context,"series","series") });put("youtube",lazy { YouTubeProvider(context) });put("stremio",lazy { StremioProvider(context) });put("kayo",lazy { SportsCentreProvider(context) });put("live",live);put("tcl",lazy { TClProvider(context) })
        Services.broadcasters.forEach { put(it.id,lazy { BroadcasterProvider(context,it,live.value) }) }
        put("catchup",lazy { CatchUpProvider(context,Services.broadcasters) })
    }
    private val streams=ConcurrentHashMap<String,MutableStateFlow<FeedResult?>>()
    fun updates(id:String):StateFlow<FeedResult?> = streams.getOrPut(id) { MutableStateFlow(null) }.asStateFlow()
    private fun stream(id:String)=streams.getOrPut(id) { MutableStateFlow(null) }
    private class Load(val job:Deferred<FeedResult>,val force:Boolean)
    private val active=ConcurrentHashMap<String,Load>()
    private val memory=ConcurrentHashMap<String,FeedResult>()
    private val retryAfter=ConcurrentHashMap<String,Long>()
    private val lastResults=ConcurrentHashMap<String,FeedResult>()
    private val stremioAccount by lazy { StremioAccountStore(context) }
    private var stremioGeneration:Long?=null // Read by the first Stremio use: nothing is loaded for any account before then.
    // The section last asked for and the one before it (Sports Centre opens over the selected section): a memory trim never takes these.
    private val lastAsked=arrayOfNulls<String>(2)
    private fun touch(id:String)=synchronized(lastAsked) { if(lastAsked[0]!=id){lastAsked[1]=lastAsked[0];lastAsked[0]=id} }
    private fun sensitive(id:String)=id in setOf("stremio","movies","series")
    private fun discovery(id:String)=id=="movies"||id=="series"
    // Popular now: its row id, and the name of the small file a later read of that one row is saved under.
    private fun popularRowId(id:String)="$id-top"
    // When each section's Popular now was last read, by a full load or since then by refreshPopular.
    private val popularAt=ConcurrentHashMap<String,Long>()
    private val popularLock=kotlinx.coroutines.sync.Mutex()
    // Full loads begun per section, whatever became of them: a Popular now read that one overlapped is dropped.
    private val loadsBegun=ConcurrentHashMap<String,Int>()
    private fun clearSaved(id:String) { cache.clear(id);if(discovery(id)){cache.clear(popularRowId(id));popularAt.remove(id)} }
    // Leftovers of older versions, removed off the main thread; reads wait for this so a removed feed is never shown.
    private val cleanup=scope.launch(start=CoroutineStart.LAZY) {
        cache.clear("tcl") // Older versions cached the removed TCL published-content row.
        // Account rows stay in memory only. Movies and TV shows save just their public browse rows (see savedPublicRows).
        cache.clear("stremio")
        File(context.filesDir,"feed-stremio.json").delete()
    }
    /**
     * Called from Application.onCreate, off the main thread: the housekeeping, the shared HTTP engine and the account
     * generation are then ready (or under way) before the first screen asks. Local work only; nothing uses the network.
     */
    fun warm() {
        cleanup.start()
        scope.launch { LauncherHttp.client }
        scope.launch { syncStremioAccount() }
    }
    /** Also from Application.onCreate: builds the section the launcher will open on and reads its saved rows into memory. */
    suspend fun preload(id:String) { if(id in adapters){adapters.getValue(id).value;cached(id)} }
    @Synchronized fun syncStremioAccount() {
        val generation = stremioAccount.generation
        val known = stremioGeneration
        stremioGeneration = generation
        if(known == null || generation == known) return
        for(id in listOf("stremio","movies","series")) {
            active.remove(id)?.job?.cancel();retryAfter.remove(id)
            // Only account rows are account data: the public browse rows stay, stale, until the new account's load replaces them.
            val kept=FeedRefreshPolicy.publicRows(id,memory[id])?.let { LiveTvPolicy.filter(context.liveTvEnabled,it) }
            if(kept!=null){memory[id]=kept;lastResults[id]=kept}else{memory.remove(id);lastResults.remove(id)}
            stream(id).value=kept
            clearSaved(id);File(context.filesDir,"feed-$id.json").delete()
        }
    }
    fun nextRetryAt(id:String)=retryAfter[id]?:0L
    private fun schedule(id:String)=id in setOf("kids","kayo","live","7plus","9now","abc","sbs","10")
    fun adapter(id:String):FeedAdapter {
        val adapter=adapters.getValue(id).value
        if(LiveTvPolicy.sectionAllowed(context.liveTvEnabled,id))return adapter
        return object:FeedAdapter {
            override val id=adapter.id
            override val displayName=adapter.displayName
            override suspend fun isAvailable()=false
            override suspend fun load(forceRefresh:Boolean)=FeedResult(status=FeedStatus.UNSUPPORTED)
            override suspend fun resolvePlayback(item:ContentItem)=PlaybackResult.Unavailable("Live TV is unavailable on this device.")
        }
    }
    suspend fun cached(id:String):FeedResult? = withContext(Dispatchers.IO) {
        touch(id);cleanup.join()
        if(sensitive(id)) {syncStremioAccount();(memory[id]?:savedPublicRows(id))?.copy(status=FeedStatus.CACHED)?.let { LiveTvPolicy.filter(context.liveTvEnabled,it) }}
        else if(schedule(id)) null else (memory[id]?.copy(status=FeedStatus.CACHED)?:cache.read(id)?.also { memory[id]=it })?.let { LiveTvPolicy.filter(context.liveTvEnabled,it) }
    }
    /**
     * Movies and TV shows after a restart: the public rows saved by the last complete load. They count as shown, so the
     * refresh that follows a stale copy replaces them in one step instead of row by row. Personal rows are not in the
     * file; the screen adds them from local watched state.
     */
    private fun savedPublicRows(id:String):FeedResult? {
        val rows=FeedRefreshPolicy.restored(id,cache.read(id))?:return null
        // Popular now may have been read again since those rows were saved: the later copy of that one row wins.
        val popular=FeedRefreshPolicy.restored(id,cache.read(popularRowId(id)))?.takeIf { it.updatedAt>rows.updatedAt }
        val merged=popular?.rows?.firstOrNull { it.id==popularRowId(id) }?.let { FeedRefreshPolicy.withPopular(id,rows,it) }?:rows
        if(memory.putIfAbsent(id,merged)==null)popularAt[id]=popular?.updatedAt?:rows.updatedAt
        lastResults.putIfAbsent(id,merged)
        return merged
    }
    /**
     * Movies and TV shows hold their genre rows for a day. Popular now is one request, so opening the section on held
     * rows reads that row again once it is an hour old and puts it into them, in memory and in a small file beside
     * the saved copy. Public catalogue data only. Null when there is nothing new to draw: the row was not due, a full
     * load is running (it brings the row itself), the page could not be read, or it came back unchanged.
     */
    suspend fun refreshPopular(id:String):FeedResult? = withContext(Dispatchers.IO) {
        if(!discovery(id))return@withContext null
        popularLock.withLock {
            // Held rows are matched to the account first, as cached() and refresh() do; that account is checked again below.
            val generation=syncedStremioGeneration()
            val readAt=popularAt[id]
            val begun=loadsBegun[id]
            val asked=System.currentTimeMillis()
            if(memory[id]==null||active[id]?.job?.isActive==true||!RefreshPolicy.popularDue(id,readAt,asked))return@withLock null
            val row=(adapters.getValue(id).value as? StremioDiscoveryProvider)?.popular()?:return@withLock null
            // Settled under the account lock every full load publishes under, and only for the account the held rows
            // belong to: a full load that began meanwhile (running, finished or failed), or an account change, has
            // already decided what is shown, and held rows (which include Continue watching) are never put back after either.
            var accepted=false
            val shown=stremioAccount.withGeneration(generation) {
                val held=memory[id]
                if(held==null||loadsBegun[id]!=begun||popularAt[id]!=readAt)return@withGeneration null
                popularAt[id]=asked;accepted=true
                FeedRefreshPolicy.withPopular(id,held,row)?.let { merged ->
                    memory[id]=merged
                    LiveTvPolicy.filter(context.liveTvEnabled,merged.copy(status=FeedStatus.CACHED)).also { lastResults[id]=it;stream(id).value=it }
                }
            }
            // Stamped with the time it was asked for, so it can never outrank a full load that came after it.
            if(accepted)FeedRefreshPolicy.savable(id,FeedResult(listOf(row),FeedStatus.FRESH,updatedAt=asked))?.let { cache.write(popularRowId(id),it) }
            shown
        }
    }
    @Synchronized private fun syncedStremioGeneration():Long { syncStremioAccount();return stremioGeneration?:0L }
    // A load cut short still shows what it read, marked incomplete so it is neither saved nor held as long as a complete one.
    private fun cutShort(partial:FeedResult?,message:String)=partial?.takeIf { it.rows.any { row -> row.items.isNotEmpty() } }
        ?.let { it.copy(message=message,error=it.error?:FeedError("load_incomplete",message)) }
    @Synchronized fun refresh(id:String,force:Boolean=false):Deferred<FeedResult> {
        touch(id)
        if(sensitive(id))syncStremioAccount()
        val running=active[id]?.takeIf { it.job.isActive }
        // Share the load in flight, unless this caller asked for a forced refresh and that load is not one: then run after it.
        if(running!=null && FeedRefreshPolicy.joins(force,running.force)) return running.job
        val requestedGeneration = stremioGeneration ?: 0L
        loadsBegun.merge(id,1,Int::plus)
        return scope.async {
            running?.job?.join();cleanup.join()
            val recent=memory[id]
            if(!force && System.currentTimeMillis()<nextRetryAt(id)) return@async lastResults[id]?:FeedResult(status=FeedStatus.SOURCE_UNAVAILABLE,message="Source unavailable. Try again shortly.")
            if(!schedule(id) && !force && recent!=null && System.currentTimeMillis()-recent.updatedAt<120_000) return@async LiveTvPolicy.filter(context.liveTvEnabled,recent.copy(status=FeedStatus.CACHED))
            val progressiveRows=if(id=="stremio") StremioProgressiveRows().apply { merge(recent?.rows.orEmpty()) } else null
            var partial:FeedResult?=null
            var degraded=false
            // A refresh of rows already on screen must not show a partial set: dropping the focused
            // row for a moment throws remote focus out of the pane. Only the first load is progressive.
            val shown=progressiveRows==null && lastResults[id]?.rows?.any { it.items.isNotEmpty() }==true
            val loaded=try { withTimeout(45_000) { adapter(id).loadProgressively(force) { update ->
                fun publishPartial() {
                    progressiveRows?.merge(update.rows)
                    val displayed=LiveTvPolicy.filter(context.liveTvEnabled,if(progressiveRows!=null) update.copy(rows=progressiveRows.rows()) else update)
                    partial=displayed
                    if(!shown)stream(id).value=displayed
                }
                if(sensitive(id)) stremioAccount.withGeneration(requestedGeneration) { publishPartial() }
                    ?: throw CancellationException("Account changed")
                else publishPartial()
            } } }
                catch(e:TimeoutCancellationException) { degraded=true;cutShort(partial,"Some content is still unavailable. Showing the rows already loaded.") ?: FeedResult(status=FeedStatus.SOURCE_UNAVAILABLE,message="The source took too long. Open the app or try refreshing.") }
                catch(e:CancellationException) { throw e }
                catch(e:Exception) { degraded=true;cutShort(partial,"Some content could not be refreshed. Showing the rows already loaded.") ?: FeedResult(status=FeedStatus.SOURCE_UNAVAILABLE,message="This source is unavailable. You can still open its app.",error=FeedError("load_failed",e.javaClass.simpleName)) }
            fun publish(): FeedResult {
            if(sensitive(id) && loaded.status==FeedStatus.AUTHENTICATION_REQUIRED) {
                memory.remove(id);lastResults.remove(id);retryAfter.remove(id)
                clearSaved(id)
            }
            if(loaded.status==FeedStatus.NO_CONTENT) {clearSaved(id);memory.remove(id)}
            // A refresh that lost rows to a partial failure keeps the rows it could not reload instead of replacing a complete result.
            val result=if(schedule(id)) loaded else FeedRefreshPolicy.keepBetter(memory[id],loaded,degraded)
            if(result.status==FeedStatus.FRESH && !schedule(id)) {
                if(!sensitive(id))cache.write(id,result) else FeedRefreshPolicy.savable(id,result,degraded)?.let { cache.write(id,it) }
                memory[id]=result
                // Only a load that read Popular now itself resets that row's hour; a row carried over from before does not.
                if(discovery(id)&&loaded.rows.any { it.id==popularRowId(id)&&it.items.isNotEmpty() })popularAt[id]=result.updatedAt
            }
            val fallback=if(!schedule(id)&&result.rows.isEmpty()&&result.status==FeedStatus.SOURCE_UNAVAILABLE) (memory[id]?:if(!sensitive(id))cache.read(id) else null)?.copy(status=FeedStatus.CACHED,message="Saved content • refresh unavailable: ${result.message}") else null
            val displayed=LiveTvPolicy.filter(context.liveTvEnabled,fallback?:result)
            if(result.status==FeedStatus.SOURCE_UNAVAILABLE) retryAfter[id]=System.currentTimeMillis()+180_000L else retryAfter.remove(id)
            lastResults[id]=displayed
            stream(id).value=displayed
            // Account metadata is intentionally excluded from diagnostic exports as well as disk caches.
            // Items from a signed-in account (e.g. Stremio sports channels in Sports Centre) never reach the export file.
            // Only a debuggable build can export this file (run-as), so release builds do not write it on every refresh.
            if(FeedDiagnostics.exportable(context)&&!sensitive(id))runCatching { File(context.filesDir,"feed-$id.json").writeText(FeedCache.encode(displayed.copy(rows=displayed.rows.map { row ->
                row.copy(items=row.items.filterNot { it.metadata["accountAuthorised"]=="true" }) })).toString(2)) }
            FeedDiagnostics.event(context,"feed_loaded",mapOf("service" to id,"status" to displayed.status.name,"rows" to displayed.rows.size,"items" to displayed.rows.sumOf { it.items.size }))
            return displayed
            }
            if(sensitive(id)) stremioAccount.withGeneration(requestedGeneration) { publish() }
                ?: throw CancellationException("Account changed")
            else publish()
        }.also { active[id]=Load(it,force) }
    }
    /** From Application.onTrimMemory, on the main thread: map removals only, no I/O and no waiting. */
    fun trimMemory(level:Int) {
        val depth=MemoryTrimPolicy.depth(level)
        if(depth==MemoryTrimPolicy.Depth.NONE)return
        val kept=synchronized(lastAsked) { lastAsked.filterNotNull().toSet() }
        val loaded=HashSet<String>().apply { memory.forEach { (key,_)->add(key) };lastResults.forEach { (key,_)->add(key) } }
        for(id in MemoryTrimPolicy.sections(depth,loaded,kept)) {
            val load=active[id]
            if(load?.job?.isActive==true)continue // About to publish anyway.
            if(load!=null)active.remove(id,load) // A finished load still holds its result.
            memory.remove(id);lastResults.remove(id);streams[id]?.value=null
            (adapters[id]?.takeIf { it.isInitialized() }?.value as? MemoryTrimmable)?.trimMemory()
        }
        if(MemoryTrimPolicy.guide(depth,kept))GuideStore.releaseShared()
    }
}
