package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.data.FeedResult
import com.majendra.tvlauncher.providers.cinemeta.CinemetaCatalogue
import com.majendra.tvlauncher.providers.cinemeta.CinemetaCoverage
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select

/**
 * Keeps one filter's cards supplied from the whole indexed catalogue. Each pass shows what the index holds, up to
 * the number of cards wanted so far; reads further into the catalogue when that is not enough, and one batch ahead
 * when it is; then waits for [demand] (focus nearing the end) before wanting more. Cards are added only at the end.
 * [excluded] is asked afresh on every pass, so Seen and Not interested are applied to answers and never stored;
 * each pass first takes newly excluded cards out of the window, so its count and its last rows are what is on
 * screen. [changed] says those choices may have changed, and starts a pass without asking for more.
 * Runs until cancelled. Call from the main thread: [show] and the window are not synchronised.
 */
class FilterResults(private val window: FilterWindow, private val type: String, private val catalogue: CinemetaCatalogue,
    private val excluded: suspend () -> Collection<String>, private val demand: ReceiveChannel<Unit>, private val changed: ReceiveChannel<Unit>,
    private val show: (FeedResult) -> Unit) {
    suspend fun run() {
        val query = window.filter.query(type)
        val batch = FilterPaging.batch(window.columns)
        while (demand.tryReceive().isSuccess || changed.tryReceive().isSuccess) { /* Left over from an earlier filter; the first pass covers both. */ }
        var failed = false
        while (currentCoroutineContext().isActive) {
            val hidden = excluded()
            // Before anything is counted: a card hidden since it was shown must not hold a place, or the last rows
            // the viewer can reach would never be "near the end" and nothing more would ever load.
            window.reconcile(hidden)
            val known = catalogue.query(query, 0, window.target, hidden)
            window.append(known.items.map { it.toContentItem() })
            val (step, status) = FilterPaging.decide(window.size, window.target, window.limit, known.total, known.mayHaveMore, failed)
            window.status = status
            show(window.feed())
            if (step == FilterStep.EXTEND || FilterPaging.readAhead(status, known.total, window.target, window.columns, known.mayHaveMore, failed)) {
                failed = catalogue.extend(query, window.target + batch, hidden).coverage == CinemetaCoverage.FAILED
                if (step == FilterStep.EXTEND) continue
            }
            // Wait for focus to near the end, or for local choices to change what may be shown.
            var wantsMore: Boolean? = null
            while (wantsMore == null) wantsMore = select<Boolean?> {
                changed.onReceive { false }
                demand.onReceive { if (FilterPaging.acceptsDemand(window.status)) true else null }
            }
            if (wantsMore != true) continue
            if (window.status == FilterStatus.MORE) window.target = FilterPaging.nextTarget(window.target, window.columns)
            failed = false
        }
    }
}
