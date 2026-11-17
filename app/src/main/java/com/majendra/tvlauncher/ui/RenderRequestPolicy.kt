package com.majendra.tvlauncher.ui

internal data class RenderContext(val section: String, val query: String, val visitSeed: Long,
    val tonightSeed: Long, val accountGeneration: Long)

internal data class RenderTicket(val generation: Long, val context: RenderContext)

/** Main-thread gate: cancelled work may finish blocking store reads, but cannot publish stale views. */
internal class RenderRequestGate {
    private var generation = 0L
    fun next(context: RenderContext) = RenderTicket(++generation, context)
    fun invalidate() { generation++ }
    fun accepts(ticket: RenderTicket, current: RenderContext) =
        ticket.generation == generation && ticket.context == current
}
