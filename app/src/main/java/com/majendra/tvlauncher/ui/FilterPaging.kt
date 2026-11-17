package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.data.ContentItem
import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.data.FeedResult
import com.majendra.tvlauncher.data.FeedStatus

/** What the viewer is told about filtered results. */
enum class FilterStatus { LOADING, MORE, COMPLETE, UNREACHABLE, LIMIT, EMPTY }

/** What the results loop does next. */
enum class FilterStep { EXTEND, WAIT }

/** Pure paging rules for filtered results: how many to show, when to read more, and what to say. */
object FilterPaging {
    const val ROWS_PER_BATCH = 6
    const val MAX_ROWS = 100
    /** Focus this close to the last row asks for the next batch. */
    const val NEAR_END_ROWS = 2

    /** Poster cards that fit across the content pane without the row scrolling sideways. */
    fun columns(screenWidthDp: Int, cardWidthDp: Int = CardStyle.STREAMING_POSTER.widthDp + 12, insetDp: Int = 28) =
        ((screenWidthDp - insetDp) / cardWidthDp).coerceIn(4, 10)
    fun batch(columns: Int) = columns * ROWS_PER_BATCH
    fun limit(columns: Int) = columns * MAX_ROWS

    /**
     * After everything indexed has been appended up to [target]. [mayHaveMore]: the catalogue has unread pages for
     * this filter. [failed]: the last attempt to read them did not get through.
     */
    fun decide(shown: Int, target: Int, limit: Int, known: Int, mayHaveMore: Boolean, failed: Boolean): Pair<FilterStep, FilterStatus> = when {
        shown >= limit -> FilterStep.WAIT to FilterStatus.LIMIT
        shown >= target -> FilterStep.WAIT to if (known > shown || mayHaveMore) FilterStatus.MORE else FilterStatus.COMPLETE
        !mayHaveMore -> FilterStep.WAIT to if (shown == 0) FilterStatus.EMPTY else FilterStatus.COMPLETE
        failed -> FilterStep.WAIT to FilterStatus.UNREACHABLE
        else -> FilterStep.EXTEND to FilterStatus.LOADING
    }

    /** While more cards are waiting to be asked for, keep one batch of matches indexed ahead of the screen. */
    fun readAhead(status: FilterStatus, known: Int, target: Int, columns: Int, mayHaveMore: Boolean, failed: Boolean) =
        status == FilterStatus.MORE && mayHaveMore && !failed && known < target + batch(columns)

    /** Whether a new request for more changes anything: not at the limit and not once every match is shown. */
    fun acceptsDemand(status: FilterStatus) = status == FilterStatus.MORE || status == FilterStatus.UNREACHABLE
    fun nextTarget(target: Int, columns: Int) = minOf(target + batch(columns), limit(columns))

    /**
     * True when the focused row is one of the last [NEAR_END_ROWS] of the result [rows] that still hold cards.
     * Judged by position, never by the number in a row's id: rows whose cards have all gone leave gaps in the numbering.
     */
    fun nearEnd(rows: List<String>, rowId: String): Boolean {
        val position = rows.indexOf(rowId)
        return position >= 0 && position >= rows.size - NEAR_END_ROWS
    }
    fun rowId(section: String, index: Int) = "$section-filter-${index.toString().padStart(4, '0')}"
    fun statusRowId(section: String) = "$section-filter-status"

    /** The short state beside the section title. */
    fun label(status: FilterStatus) = when (status) {
        FilterStatus.LOADING -> "Loading more…"
        FilterStatus.MORE -> "More below"
        FilterStatus.COMPLETE -> "All matches shown"
        FilterStatus.UNREACHABLE -> "Can't reach the catalogue"
        FilterStatus.LIMIT -> "Showing the first matches"
        FilterStatus.EMPTY -> "No matches"
    }

    /** The line under the last row of results, or the whole message when there are none. */
    fun note(status: FilterStatus, shown: Int, noun: String) = when (status) {
        FilterStatus.LOADING -> if (shown == 0) "Looking through the catalogue…" else "Loading more $noun…"
        FilterStatus.MORE -> "More $noun load as you move down."
        FilterStatus.COMPLETE -> "That's every match: $shown ${if (shown == 1) noun.removeSuffix("s") else noun}."
        FilterStatus.UNREACHABLE -> if (shown == 0) "The catalogue could not be reached. Check the connection, then choose Filter again."
            else "The catalogue could not be reached, so there may be more matches than the $shown shown. Move down again to retry."
        FilterStatus.LIMIT -> "Showing the first $shown matches. Narrow the filter to see others."
        FilterStatus.EMPTY -> "No $noun match this filter. Choose Filter to change it, or Clear filter to go back."
    }

    fun feedStatus(status: FilterStatus, shown: Int) = when (status) {
        FilterStatus.LOADING -> FeedStatus.LOADING
        FilterStatus.EMPTY -> FeedStatus.NO_CONTENT
        FilterStatus.UNREACHABLE -> if (shown == 0) FeedStatus.SOURCE_UNAVAILABLE else FeedStatus.FRESH
        else -> FeedStatus.FRESH
    }
}

/**
 * The filtered cards on screen, in fixed rows. A card keeps its row and its order once shown: reading more of the
 * catalogue only adds cards at the end, and a title since marked seen locally or not interested is taken out
 * of its row without the others changing rows. Rows that did not change keep their identity. The window holds only
 * what may be shown, so its count and its last rows are the screen's. Bounded by [limit]. Main thread only.
 */
class FilterWindow(val section: String, val filter: MovieFilter, val columns: Int, val limit: Int = FilterPaging.limit(columns)) {
    private class Chunk(val index: Int, val items: ArrayList<ContentItem>, var row: ContentRow? = null)
    private val chunks = ArrayList<Chunk>()
    private val ids = HashSet<String>()
    private var nextIndex = 0
    private var rows: List<ContentRow> = emptyList()
    var target = minOf(FilterPaging.batch(columns), limit)
    var status = FilterStatus.LOADING
    var size = 0
        private set
    /** Goes up whenever the cards held change, so a caller can tell a new window from the one it already reported. */
    var generation = 0L
        private set

    /** Adds candidates not yet shown, in the order given, until [target] cards are shown. Returns how many were added. */
    fun append(candidates: List<ContentItem>): Int {
        var added = 0
        for (candidate in candidates) {
            if (size >= minOf(target, limit)) break
            if (!ids.add(candidate.id)) continue
            // Only the last row takes new cards; an earlier row that lost a card stays as it is.
            val row = chunks.lastOrNull()?.takeIf { it.items.size < columns } ?: Chunk(nextIndex++, ArrayList(columns)).also { chunks += it }
            row.items += candidate; row.row = null
            size++; added++
        }
        if (added > 0) { generation++; rebuild() }
        return added
    }

    /**
     * Takes out every card whose title key ("movie:tt…") or id is in [excluded], before rows are counted or paging is
     * decided. Remaining cards stay in their rows, in order; a row left without cards goes. True when anything went.
     */
    fun reconcile(excluded: Collection<String>): Boolean {
        if (size == 0 || excluded.isEmpty()) return false
        var removed = false
        val each = chunks.iterator()
        while (each.hasNext()) {
            val chunk = each.next()
            if (!chunk.items.removeAll { card -> (card.id in excluded || "${card.metadata["type"]}:${card.id}" in excluded).also { gone -> if (gone) ids.remove(card.id) } }) continue
            removed = true; chunk.row = null
            if (chunk.items.isEmpty()) each.remove()
        }
        if (!removed) return false
        size = chunks.sumOf { it.items.size }
        generation++
        rebuild()
        return true
    }

    /** Rows whose cards did not change are reused as they are; the heading sits on whichever row is now first. */
    private fun rebuild() {
        rows = chunks.mapIndexed { position, chunk ->
            val title = if (position == 0) MovieFilterPolicy.summary(filter) else ""
            chunk.row?.takeIf { it.title == title } ?: ContentRow(FilterPaging.rowId(section, chunk.index), title, chunk.items.toList()).also { chunk.row = it }
        }
    }

    fun rows(): List<ContentRow> = rows
    /** Whether focus in [rowId] is close enough to the end of what is shown to want more. */
    fun nearEnd(rowId: String) = FilterPaging.nearEnd(rows.map { it.id }, rowId)

    /** The rows, a closing line that says what state the results are in, and the matching feed status. */
    fun feed(): FeedResult {
        val noun = MovieFilterPolicy.noun(section)
        val note = FilterPaging.note(status, size, noun)
        return FeedResult(if (size == 0) emptyList() else rows + ContentRow(FilterPaging.statusRowId(section), "", emptyList(), note),
            FilterPaging.feedStatus(status, size), note)
    }
}
