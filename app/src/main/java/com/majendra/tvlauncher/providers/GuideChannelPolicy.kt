package com.majendra.tvlauncher.providers

/** Broadcast TV only. Keep time-shifted services, but collapse SD/HD simulcasts. */
object GuideChannelPolicy {
    private val radio = Regex("(?i)(radio|double\\s*j|triple\\s*j|abc\\s+(jazz|classic|country|listen|sport)|sbs\\s+(pop|chill))")
    fun television(channels: List<GuideChannel>): List<GuideChannel> = channels
        .filterNot { radio.containsMatchIn(it.name) }
        .groupBy { identity(it.name) }.values.map { variants ->
            variants.sortedWith(compareByDescending<GuideChannel> { Regex("(?i)(?:\\b|(?<=\\d))hd\\b").containsMatchIn(it.name) }
                .thenBy { it.number?.toIntOrNull() ?: Int.MAX_VALUE }).first()
        }.sortedBy { it.number?.toIntOrNull() ?: Int.MAX_VALUE }

    internal fun identity(name: String): String {
        val cleaned = name.lowercase().replace(Regex("(?<=\\d)(hd|sd)\\b"), "").replace(Regex("\\b(melbourne|victoria|vic|australia|hd|sd)\\b"), "")
            .replace(Regex("[^a-z0-9+]"), "")
        return when (cleaned) {
            "channelnine", "nine", "9" -> "nine"
            "seven", "7" -> "seven"
            else -> cleaned
        }
    }
}
