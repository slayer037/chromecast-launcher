package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.data.FeedResult

/**
 * When one title's details arrive, only that title's cards change. Applying the new metadata to rows
 * that are already projected gives the result a full projection would, without redoing every row.
 */
internal object CardPatchPolicy {
    /** Rows drawn from local watched state rather than the feed; a projection never adds source ratings to them. */
    val householdRows = setOf("watching-shows", "saved-shows", "upcoming-episodes")

    /** The same instance comes back when no card matched, and untouched rows and cards keep their identity. */
    fun withMetadata(result: FeedResult, source: String, id: String, metadata: Map<String, String>,
        skipRows: Set<String> = emptySet()): FeedResult {
        if (metadata.isEmpty()) return result
        var changed = false
        val rows = result.rows.map { row ->
            if (row.id in skipRows || row.items.none { it.source == source && it.id == id }) row else {
                changed = true
                row.copy(items = row.items.map { item ->
                    if (item.source == source && item.id == id) item.copy(metadata = item.metadata + metadata) else item
                })
            }
        }
        return if (changed) result.copy(rows = rows) else result
    }
}

/**
 * A details request that came back with nothing may only have been a dropped connection. Marking it done
 * left the title without a rating or description for the whole session, so allow a few more tries, one
 * per later visit to the card. Main thread only.
 */
internal class EnrichmentAttempts(private val limit: Int = 3, private val capacity: Int = 256) {
    private val attempts = linkedMapOf<String, Int>()
    fun allowed(key: String) = (attempts[key] ?: 0) < limit
    fun finished(key: String, useful: Boolean) {
        if (key !in attempts && attempts.size >= capacity) attempts.clear()
        attempts[key] = if (useful) limit else (attempts[key] ?: 0) + 1
    }
    fun clear() = attempts.clear()
}
