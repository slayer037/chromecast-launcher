package com.majendra.tvlauncher.navigation

import java.time.Instant
import java.time.ZoneId

/** Invocation-only guard: never schedules a shutdown, retry, or morning launch. */
object QuietHoursPolicy {
    private val bedroomZone = ZoneId.of("Australia/Sydney")

    fun allowsAutomaticLaunch(bedroomGuard: Boolean, now: Instant, interactive: Boolean): Boolean =
        !bedroomGuard || (interactive && now.atZone(bedroomZone).hour in 8..19)
}
