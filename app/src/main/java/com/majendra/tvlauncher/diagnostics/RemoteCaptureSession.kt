package com.majendra.tvlauncher.diagnostics

/** Deliberately typed capture schema: there is no field that accepts screen or account text. */
enum class Pane { SIDEBAR, CONTENT, OTHER, NONE }
data class FocusSnapshot(val pane: Pane, val rowId: Int = -1, val adapterPosition: Int = -1) {
    internal fun safe() = copy(rowId = rowId.coerceIn(-1, 100000), adapterPosition = adapterPosition.coerceIn(-1, 100000))
}
enum class RemoteDirection { LEFT, RIGHT, UP, DOWN, CENTER, BACK }
enum class RemoteAction { DOWN, UP }

class RemoteCaptureSession(
    private val now: () -> Long,
    private val durationMs: Long = 10 * 60 * 1000L,
    private val maxEvents: Int = 500,
) {
    private var started: Long? = null
    private val events = ArrayDeque<String>()
    private var state = "Stopped"

    @Synchronized fun start() { events.clear(); started = now(); state = "Recording" }
    @Synchronized fun stop() { started = null; state = "Stopped" }
    @Synchronized fun clear() { started = null; events.clear(); state = "Stopped" }
    @Synchronized fun isRecording(): Boolean {
        val start = started ?: return false
        if (now() - start >= durationMs || now() < start) { started = null; state = "Expired"; return false }
        return true
    }
    @Synchronized fun status(): String { isRecording(); return "$state · ${events.size}/$maxEvents events" }
    @Synchronized fun lines(): List<String> = events.toList()
    @Synchronized fun focus(focus: FocusSnapshot): Boolean = append("FOCUS ${format(focus.safe())}")
    @Synchronized fun key(direction: RemoteDirection, action: RemoteAction, repeat: Int, downTime: Long, eventTime: Long, deviceId: Int, focus: FocusSnapshot): Boolean =
        append("KEY $direction $action repeat=${repeat.coerceIn(0, 100000)} downTime=${downTime.coerceAtLeast(0)} eventTime=${eventTime.coerceAtLeast(0)} deviceId=$deviceId ${format(focus.safe())}")

    private fun format(focus: FocusSnapshot) = "pane=${focus.pane} row=${focus.rowId} position=${focus.adapterPosition}"
    private fun append(value: String): Boolean {
        if (!isRecording()) return false
        events.addLast("+${(now() - started!!).coerceAtLeast(0)}ms $value")
        if (events.size >= maxEvents) { started = null; state = "Full" }
        return true
    }
}
