package com.majendra.tvlauncher.data

/** A source holding rows of its own that the repository may ask it to drop under memory pressure. */
interface MemoryTrimmable { fun trimMemory() }

/**
 * What a trim callback may release. Pressing Home must stay instant, so the sections kept on screen never lose their
 * rows, and merely hiding the UI releases only what a local file restores. Rows that need the network again go only
 * when the system reports real memory pressure. (Coil trims its own bitmap cache.)
 */
internal object MemoryTrimPolicy {
    enum class Depth { NONE, HIDDEN, LOW }
    // android.content.ComponentCallbacks2 levels, repeated here so this stays testable without Android.
    private const val RUNNING_LOW = 10
    private const val RUNNING_CRITICAL = 15
    private const val UI_HIDDEN = 20
    private const val BACKGROUND = 40
    /** Sections whose rows are built from the saved Melbourne guide. */
    private val guideSections = setOf("live", "kayo", "7plus", "9now", "abc", "sbs", "10")

    fun depth(level: Int) = when {
        level >= BACKGROUND || level == RUNNING_CRITICAL -> Depth.LOW
        level == UI_HIDDEN || level == RUNNING_LOW -> Depth.HIDDEN
        else -> Depth.NONE
    }

    /** Sections whose rows may be dropped: only under real pressure, and never one of the [kept] sections. */
    fun sections(depth: Depth, loaded: Set<String>, kept: Set<String>): Set<String> = if (depth == Depth.LOW) loaded - kept else emptySet()

    /** The parsed guide reloads from its compact file: it goes under pressure, or while hidden if no kept section draws from it. */
    fun guide(depth: Depth, kept: Set<String>) = depth == Depth.LOW || (depth == Depth.HIDDEN && kept.none { it in guideSections })
}
