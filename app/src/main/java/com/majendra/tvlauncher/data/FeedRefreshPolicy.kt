package com.majendra.tvlauncher.data

/** Decisions the repository makes about a refresh, kept free of Android so they can be tested. */
internal object FeedRefreshPolicy {
    /** A caller shares the load already in flight, unless it asked for a forced refresh and that load is not one. */
    fun joins(requestedForce: Boolean, runningForce: Boolean) = !requestedForce || runningForce

    /**
     * A refresh that came back with rows but lost some to a partial failure ([degraded], or FRESH carrying an error)
     * must not replace a complete result: rows it could not reload are carried over from [previous], each put back
     * after the row it used to follow. Everything the refresh did load is the new data.
     */
    fun keepBetter(previous: FeedResult?, incoming: FeedResult, degraded: Boolean): FeedResult {
        if (previous == null || incoming.status != FeedStatus.FRESH || !(degraded || incoming.error != null)) return incoming
        val loaded = incoming.rows.filter { it.items.isNotEmpty() }.map { it.id }.toSet()
        if (previous.rows.none { it.items.isNotEmpty() && it.id !in loaded }) return incoming
        val rows = incoming.rows.filter { it.items.isNotEmpty() || previous.rows.none { old -> old.id == it.id && old.items.isNotEmpty() } }.toMutableList()
        previous.rows.forEachIndexed { index, old ->
            if (old.items.isEmpty() || old.id in loaded) return@forEachIndexed
            val before = previous.rows.subList(0, index).asReversed().firstNotNullOfOrNull { earlier ->
                rows.indexOfFirst { it.id == earlier.id }.takeIf { it >= 0 }
            }
            rows.add(if (before == null) 0 else before + 1, old)
        }
        return incoming.copy(rows = rows)
    }

    /**
     * What may outlive a Stremio account change. Movies and TV shows keep only their public Cinemeta browse rows,
     * recognised by id (anything else, including rows added later, counts as account data) and holding no
     * account-authorised item. They come back stale (updatedAt 0) so the next load still runs for the new account.
     * The account section itself keeps nothing.
     */
    fun publicRows(id: String, result: FeedResult?): FeedResult? {
        if (result == null || id !in setOf("movies", "series")) return null
        val rows = result.rows.filter { it.id == "$id-top" || it.id.startsWith("$id-genre-") }
            .map { row -> row.copy(items = row.items.filter { it.metadata["accountAuthorised"] != "true" && it.metadata["personalised"] == "false" }) }
            .filter { it.items.isNotEmpty() }
        return if (rows.isEmpty()) null else FeedResult(rows, FeedStatus.CACHED, result.message, 0L)
    }

    /**
     * What a Movies or TV shows load may leave on disk: the public browse rows of a complete load, with its time,
     * so a restarted launcher shows them without the network. Never account rows, and nothing after a partial load
     * ([degraded]: it timed out or failed part-way, which leaves a FRESH status and no error on the rows it did read),
     * which would otherwise stand in for the complete copy already saved and hold off the next fetch for hours.
     */
    fun savable(id: String, result: FeedResult, degraded: Boolean = false): FeedResult? {
        if (degraded || result.status != FeedStatus.FRESH || result.error != null) return null
        return publicRows(id, result)?.copy(status = FeedStatus.FRESH, updatedAt = result.updatedAt)
    }

    /** A saved copy as it is put back: filtered again, since the file is not trusted to hold only public rows, and keeping its age. */
    fun restored(id: String, saved: FeedResult?): FeedResult? =
        if (saved == null) null else publicRows(id, saved)?.copy(updatedAt = saved.updatedAt)

    /**
     * [held] with its Popular now row replaced by a later read of that one page; the genre rows and the age of the
     * whole result are untouched. The row keeps its place, or goes ahead of the genre rows (after any account row)
     * when there was none. Null when nothing on screen would change.
     */
    fun withPopular(id: String, held: FeedResult, row: ContentRow): FeedResult? {
        if (id !in setOf("movies", "series") || row.id != "$id-top" || row.items.isEmpty()) return null
        val at = held.rows.indexOfFirst { it.id == row.id }
        if (at >= 0 && held.rows[at] == row) return null
        val before = if (at >= 0) at else held.rows.indexOfFirst { it.id.startsWith("$id-genre-") }.let { if (it < 0) held.rows.size else it }
        return held.copy(rows = held.rows.take(before) + row + held.rows.drop(if (at >= 0) before + 1 else before))
    }
}
