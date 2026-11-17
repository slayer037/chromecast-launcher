package com.majendra.tvlauncher.ui

/** A delayed repair belongs to one data update and one uninterrupted navigation state. */
class FocusRecoveryPolicy {
    data class Ticket internal constructor(val update: Long, val navigation: Long)
    data class Anchor(val key: String, val position: Int, val navigation: Long)
    private var update = 0L
    private var anchor: Anchor? = null

    fun changed(structural: Boolean, navigation: Long): Ticket? {
        if (!structural) return null
        update++
        return Ticket(update, navigation)
    }

    fun remember(key: String, position: Int, navigation: Long) {
        anchor = Anchor(key, position, navigation)
    }

    fun parkedAnchor(navigation: Long, parked: Boolean): Anchor? =
        anchor?.takeIf { parked && it.navigation == navigation }

    fun completed() { anchor = null }

    fun cancel() { update++; anchor = null }

    fun allows(ticket: Ticket, navigation: Long, ownsFocus: Boolean): Boolean =
        ticket.update == update && ticket.navigation == navigation && ownsFocus
}

/** Shared by the panel and its rows so a newer directional press wins over every queued repair. */
class ContentNavigationGeneration {
    var value: Long = 0L
        private set
    fun advance() { value++ }
}
