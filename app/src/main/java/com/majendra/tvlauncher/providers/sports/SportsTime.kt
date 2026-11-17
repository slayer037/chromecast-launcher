package com.majendra.tvlauncher.providers.sports

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Australian-style card times ("7:30 pm", "Sat 2:00 am") in the device's time zone. */
object SportsTime {
    const val HOUR = 60 * 60_000L
    private val clock = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    private val day = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH)
    private val date = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun label(at: Long, now: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (if (time.toLocalDate() == today) clock else day).format(time).replace("AM", "am").replace("PM", "pm")
    }

    /** "Today" runs to midnight, but at least 12 hours ahead so overnight European fixtures show in the evening. */
    fun windowEnd(now: Long, zone: ZoneId): Long {
        val midnight = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return maxOf(midnight, now + 12 * HOUR)
    }

    fun age(at: Long, now: Long, zone: ZoneId): String {
        val minutes = (now - at).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            minutes < 24 * 60 -> "${minutes / 60}h ago"
            else -> date.format(Instant.ofEpochMilli(at).atZone(zone))
        }
    }
}
