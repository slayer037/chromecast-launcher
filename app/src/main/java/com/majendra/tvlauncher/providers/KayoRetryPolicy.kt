package com.majendra.tvlauncher.providers

import java.util.Properties

/** Persistent request budget. Reserving before I/O also counts interrupted downloads. */
internal object KayoRetryPolicy {
    const val DAY = 24 * 60 * 60_000L
    const val MAX_ATTEMPTS = 6
    const val FIRST_RETRY = 15 * 60_000L
    data class State(
        val windowStart: Long = 0,
        val attempts: Int = 0,
        val failures: Int = 0,
        val nextAttempt: Long = 0,
        val lastSuccess: Long = 0
    )

    fun mayAttempt(state: State, now: Long): Boolean = now >= state.nextAttempt &&
        (state.lastSuccess == 0L || now >= state.lastSuccess + DAY) &&
        (state.attempts < MAX_ATTEMPTS || now >= state.windowStart + DAY)

    fun reserve(state: State, now: Long): State {
        require(mayAttempt(state, now))
        val reset = state.attempts == 0 || now >= state.windowStart + DAY
        val failures = (state.failures + 1).coerceAtMost(5)
        // Until success is committed this is a failed/interrupted attempt.
        val delay = FIRST_RETRY * (1L shl (failures - 1))
        return state.copy(
            windowStart = if (reset) now else state.windowStart,
            attempts = if (reset) 1 else state.attempts + 1,
            failures = failures,
            nextAttempt = now + delay
        )
    }

    fun success(state: State, now: Long): State = state.copy(
        failures = 0, nextAttempt = now + DAY, lastSuccess = now
    )

    fun encode(state: State): Properties = Properties().apply {
        setProperty("windowStart", state.windowStart.toString())
        setProperty("attempts", state.attempts.toString())
        setProperty("failures", state.failures.toString())
        setProperty("nextAttempt", state.nextAttempt.toString())
        setProperty("lastSuccess", state.lastSuccess.toString())
    }

    fun decode(values: Properties): State {
        fun number(key: String) = requireNotNull(values.getProperty(key)).toLong().also { require(it >= 0) }
        val count = number("attempts").also { require(it <= MAX_ATTEMPTS) }.toInt()
        val failures = number("failures").also { require(it <= 5) }.toInt()
        return State(number("windowStart"), count, failures, number("nextAttempt"), number("lastSuccess"))
    }

    /** RC7 wrote an attempt marker just before fetching, then replaced the cache. */
    fun migrate(cacheTime: Long, attemptTime: Long): State = when {
        attemptTime > cacheTime -> State(attemptTime, 1, 1, attemptTime + FIRST_RETRY, cacheTime)
        cacheTime > 0L -> State(cacheTime, 1, 0, cacheTime + DAY, cacheTime)
        else -> State()
    }
}
