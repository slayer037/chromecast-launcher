package com.majendra.tvlauncher.providers.youtube

import com.majendra.tvlauncher.data.ContentItem
import kotlin.random.Random

/** A video YouTube itself published to the TV; recommendations fill rows, subscriptions/trending only steer categories and queries. */
data class YouTubeSeed(val title: String, val channel: String, val item: ContentItem, val kind: YouTubeChannelPolicy.Kind = YouTubeChannelPolicy.Kind.RECOMMENDED) {
    val weight get() = when (kind) { YouTubeChannelPolicy.Kind.RECOMMENDED -> 3; YouTubeChannelPolicy.Kind.SUBSCRIPTIONS -> 2; else -> 1 }
}
data class CategoryPlan(val id: String, val label: String, val seeds: List<ContentItem>, val queries: List<String>)

/** YouTube TV's published channels, matched by display name so locale/punctuation changes don't break it. */
object YouTubeChannelPolicy {
    enum class Kind { RECOMMENDED, SUBSCRIPTIONS, TRENDING, DROPPED, OTHER }
    fun kind(displayName: String): Kind { val n = displayName.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return when {
            n.contains("movie") || n.contains("film") || n.contains("music") || n.contains("continue") || n.contains("watch next") -> Kind.DROPPED
            n.contains("recommend") || n.contains("for you") -> Kind.RECOMMENDED
            n.contains("subscri") -> Kind.SUBSCRIPTIONS
            n.contains("trend") -> Kind.TRENDING
            else -> Kind.OTHER
        } }
}

object YouTubeMix {
    const val MIN_PER_ROW = 20
    const val MAX_PER_ROW = 40
    const val FOR_YOU = "for-you"
    private val stop = setOf("the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "with", "is", "are", "was", "this", "that", "my", "i", "you", "your",
        "how", "what", "why", "it", "at", "by", "from", "vs", "new", "official", "video", "full", "episode", "ep", "part")

    /** Rows: categories seeded by the user's published videos (weighted: recommended > subscriptions > trending), then his standing interests,
     *  then a "More for you" row for unclassified recommendations/subscriptions. Only recommended videos are placed in rows. */
    fun plan(seeds: List<YouTubeSeed>, random: Random, maxCategories: Int = 7): List<CategoryPlan> {
        val clean = seeds.filterNot { YouTubeNoiseFilter.isNoise(it.title, it.channel) }.distinctBy { it.item.metadata["videoId"] ?: it.item.id }
        val grouped = clean.groupBy { YouTubeCategories.classify(it.title, it.channel)?.id ?: FOR_YOU }
        val order = (grouped.keys.filter { it != FOR_YOU }.sortedByDescending { id -> grouped.getValue(id).sumOf { it.weight } } + YouTubeCategories.defaults)
            .distinct().take(maxCategories)
        fun shown(list: List<YouTubeSeed>) = list.filter { it.kind == YouTubeChannelPolicy.Kind.RECOMMENDED }.map { it.item }
        val categoryPlans = order.map { id -> val category = YouTubeCategories.get(id); val own = grouped[id].orEmpty()
            CategoryPlan(id, category.label, shown(own), queries(own, category.queries, random)) }
        val loose = grouped[FOR_YOU].orEmpty().filter { it.kind != YouTubeChannelPolicy.Kind.TRENDING }
        return categoryPlans + listOfNotNull(loose.takeIf { it.isNotEmpty() }?.let { CategoryPlan(FOR_YOU, "More for you", shown(it), queries(it, emptyList(), random)) })
    }
    /** Seed-derived queries (channels, then title topics) interleaved with shuffled category staples, so every refresh draws a new mix. */
    fun queries(seeds: List<YouTubeSeed>, staples: List<String>, random: Random, limit: Int = 8): List<String> {
        val fromSeeds = (seeds.map { it.channel }.filter { it.length in 2..40 }.distinct().shuffled(random) +
            seeds.map { topic(it.title) }.filter { it.isNotBlank() }.distinct().shuffled(random)).distinct()
        val base = staples.shuffled(random)
        val mixed = mutableListOf<String>()
        for (i in 0 until maxOf(fromSeeds.size, base.size)) { base.getOrNull(i)?.let(mixed::add); fromSeeds.getOrNull(i)?.let(mixed::add) }
        return mixed.distinctBy { it.lowercase() }.take(limit)
    }
    /** The first few meaningful words of a title, e.g. "Claude 5 just dropped and it's insane" -> "Claude 5 just dropped". */
    fun topic(title: String): String = title.replace(Regex("[|#\\[\\](){}!?\"“”'’:;,.]+"), " ").split(Regex("\\s+"))
        .filter { it.isNotBlank() && it.lowercase() !in stop && !it.startsWith("@") }.take(4).joinToString(" ")

    fun item(video: YouTubeVideo, packageName: String) = ContentItem("yt-${video.videoId}", "youtube", video.title,
        subtitle = listOf(video.channel, video.lengthText, video.publishedText).filter { it.isNotBlank() }.joinToString(" · "),
        description = listOf(video.channel, video.viewCountText).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { null },
        imageUrl = video.thumbnail, landscapeImageUrl = video.thumbnail, playbackUri = watchUri(video.videoId),
        metadata = mapOf("videoId" to video.videoId, "package" to packageName, "channel" to video.channel, "durationSeconds" to video.lengthSeconds.toString(), "origin" to "innertube"))
    /** Verified on the TCL: ACTION_VIEW of this URL with package com.google.android.youtube.tv starts that video. */
    fun watchUri(videoId: String) = "https://www.youtube.com/watch?v=$videoId"

    /** Published seeds first, then fetched videos (shuffled unless [random] is null, e.g. search relevance order); noise, Shorts, live and duplicates removed; capped. */
    fun assemble(seeds: List<ContentItem>, fetched: List<YouTubeVideo>, packageName: String, random: Random?, cap: Int = MAX_PER_ROW): List<ContentItem> {
        val fresh = fetched.filter { it.playable && !YouTubeNoiseFilter.isNoise(it.title, it.channel) }.let { if (random != null) it.shuffled(random) else it }.map { item(it, packageName) }
        return (seeds.filterNot { YouTubeNoiseFilter.isNoise(it.title, it.metadata["channel"].orEmpty()) } + fresh)
            .distinctBy { it.metadata["videoId"] ?: it.id }.take(cap)
    }
    /** Drops a video already shown in an earlier row, unless that would take the later row under [min]. */
    fun dedupeAcrossRows(rows: List<List<ContentItem>>, min: Int = MIN_PER_ROW): List<List<ContentItem>> {
        val seen = mutableSetOf<String>()
        val key = { i: ContentItem -> i.metadata["videoId"] ?: i.id }
        return rows.map { row ->
            val (repeats, unique) = row.partition { key(it) in seen }
            val allowed = repeats.take((min - unique.size).coerceAtLeast(0)).toSet()
            row.filter { it in allowed || key(it) !in seen }.also { kept -> seen += kept.map(key) } }
    }
}
