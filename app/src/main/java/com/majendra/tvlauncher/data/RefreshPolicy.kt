package com.majendra.tvlauncher.data

object RefreshPolicy {
    /**
     * Genre rows are several dozen catalogue requests, move over days and are reshuffled on screen every visit anyway:
     * fetch on entry once this stale, or from the manual refresh. Never on a timer.
     */
    const val DISCOVERY_STALE_MILLIS=24*60*60_000L
    /**
     * Popular now is one request and the row expected to move, so it is read again on its own on entry once this old
     * (the user's request, 12 November 2026). Cinemeta reissues the page every three hours (max-age=10800).
     */
    const val POPULAR_STALE_MILLIS=60*60_000L
    /** A load that lost rows or pages (it carries an error) is not held for the day a complete one is: it is tried again sooner. */
    const val PARTIAL_STALE_MILLIS=6*60*60_000L
    /** Schedules refresh at programme boundaries; this slow pass only catches a guide that changed without one. */
    const val SCHEDULE_SAFETY_MILLIS=5*60_000L
    /** A just-opened schedule section is still receiving its background guide and logo downloads. */
    const val SETTLE_MILLIS=2*60_000L
    /** Live scores, and the local progress-bar step that replaces the old 30-second refetch. */
    const val TICK_MILLIS=30_000L
    private val discovery=setOf("movies","series")
    private val schedule=setOf("kids","kayo","live","7plus","9now","abc","sbs","10")
    fun scheduled(service:String)=service in schedule

    /** Whether opening [service] should ask its source again, given the copy already held in memory. */
    fun fetchOnEntry(service:String,held:FeedResult?,now:Long):Boolean {
        if(held==null||service !in discovery)return true
        return held.rows.none{it.items.isNotEmpty()}||now-held.updatedAt !in 0 until if(held.error!=null)PARTIAL_STALE_MILLIS else DISCOVERY_STALE_MILLIS
    }

    /** Whether opening [service] with held rows should read Popular now again; [readAt] is when that row was last read. */
    fun popularDue(service:String,readAt:Long?,now:Long):Boolean =
        service in discovery&&(readAt==null||now-readAt !in 0 until POPULAR_STALE_MILLIS)

    fun due(service:String, result:FeedResult?, now:Long, nextRetryAt:Long=0, enteredAt:Long?=null):Boolean {
        if(result==null)return false
        if(now<nextRetryAt)return false
        if(service in discovery)return false
        if(service !in schedule)return now-result.updatedAt>=180_000L
        val settling=enteredAt!=null&&now-enteredAt in 0 until SETTLE_MILLIS
        val fast=settling||result.rows.any{row->row.items.any{it.metadata["scoreState"]=="live"}}
        if(now-result.updatedAt>=(if(fast)TICK_MILLIS else SCHEDULE_SAFETY_MILLIS))return true
        // A card changes when its programme ends or the next one starts, whichever the source listed.
        return result.rows.any { row->row.items.any { item->
            crossed(item.startTime,result.updatedAt,now)||crossed(item.endTime,result.updatedAt,now)
        } }
    }
    private fun crossed(boundary:Long?,since:Long,now:Long)=boundary!=null&&boundary>since&&now>=boundary

    /**
     * Programme progress as its source would compute it now, so bars keep moving between refreshes.
     * Returns the same instance when no bar would move, so an unchanged screen is not rendered again.
     */
    fun advance(result:FeedResult,now:Long):FeedResult {
        var changed=false
        val rows=result.rows.map { row->
            var moved=false
            val items=row.items.map { item->
                val start=item.startTime;val end=item.endTime;val old=item.progress
                if(old==null||start==null||end==null||end<=start)item else {
                    val next=((now-start).toDouble()/(end-start)).toFloat().coerceIn(0f,1f)
                    if(permille(next)==permille(old))item else {moved=true;item.copy(progress=next)}
                }
            }
            if(moved){changed=true;row.copy(items=items)}else row
        }
        return if(changed)result.copy(rows=rows)else result
    }
    /** Cards draw progress out of 1000. */
    private fun permille(value:Float)=if(value.isFinite())(value.coerceIn(0f,1f)*1000).toInt() else -1
}
