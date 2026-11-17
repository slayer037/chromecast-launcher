package com.majendra.tvlauncher.providers.youtube

/** Sleep/ambient noise loops ("shhh", white noise, 10-hour fan sounds) are never shown, whatever the source. */
object YouTubeNoiseFilter {
    private val patterns = listOf(
        "\\b(white|brown|pink|green|grey|gray|black|blue)\\s*noise\\b", "\\bs+h{2,}\\b", "\\bshh+\\b", "\\bshush(ing)?\\b",
        "\\bsleep\\s*(sounds?|music|aid|noise|machine)\\b", "\\bbaby\\s*sleep\\b", "\\bsleepy\\s*baby\\b", "\\b(put|get)s?\\s+(a\\s+|your\\s+)?bab(y|ies)\\s+to\\s+sleep\\b",
        "\\bwomb\\s*(sounds?|noise)?\\b", "\\bcolic\\b", "\\blullab(y|ies)\\b", "\\bfan\\s*(noise|sounds?)\\b", "\\bhair\\s*dryer\\b",
        "\\bvacuum(\\s*cleaner)?\\s*(sounds?|noise)\\b", "\\b(rain|ocean|wave|thunder|thunderstorm|fireplace|airplane|cabin|washing machine|dryer)\\s*(sounds?|noise)\\b",
        "\\bambient\\s*(sounds?|noise)\\b", "\\bdark\\s*screen\\b", "\\bblack\\s*screen\\b", "\\b(8|10|12)\\s*(hours?|hrs?)\\b",
        "\\b\\d+\\s*(hours?|hrs?)\\s+of\\b", "\\bfall\\s+asleep\\s+(fast|in)\\b", "\\bsleep\\s+instantly\\b", "\\bnoise\\s+for\\s+(sleep|sleeping|babies|focus|studying)\\b"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }
    fun isNoise(title: String, channel: String = ""): Boolean = "$title • $channel".let { text -> patterns.any { it.containsMatchIn(text) } }
}
