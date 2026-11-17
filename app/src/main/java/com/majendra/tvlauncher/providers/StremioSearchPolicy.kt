package com.majendra.tvlauncher.providers

import com.majendra.tvlauncher.data.ContentItem
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLEncoder

internal data class StremioEpisode(val id: String, val season: Int?, val episode: Int?, val released: Long?)

internal data class StremioMetadataDetails(val description: String? = null, val background: String? = null,
    val imdbRating: String? = null, val genres: List<String> = emptyList()) {
    fun applyTo(item: ContentItem) = item.copy(description = item.description ?: description,
        landscapeImageUrl = item.landscapeImageUrl ?: background,
        metadata = item.metadata + (if (imdbRating != null) mapOf("imdbRating" to imdbRating,
            "imdbRatingSource" to "Cinemeta") else emptyMap()) +
            (if (genres.isNotEmpty()) mapOf("genres" to genres.joinToString("|")) else emptyMap()))
}

internal object StremioSearchPolicy {
    private val rating = Regex("[0-9]{1,2}(\\.[0-9])?")
    private val imdbId = Regex("tt[0-9]{1,12}")
    private val year = Regex("(?:18|19|20)[0-9]{2}")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")

    // Cinemeta's public top route redirects here; request the verified endpoint directly.
    // Search remains on v3-cinemeta, which serves its response without a redirect.
    fun discoveryUrl(type: String): String? = if (type in setOf("movie", "series"))
        "https://cinemeta-catalogs.strem.io/top/catalog/$type/top.json" else null

    fun imdbRating(value: String?): String? {
        val text = value?.trim() ?: return null
        if (!text.matches(rating)) return null
        val number = text.toDoubleOrNull() ?: return null
        return text.takeIf { number in 0.0..10.0 }
    }
    fun supportsSearch(extras: List<Pair<String, Boolean>>) =
        extras.any { it.first == "search" } && extras.none { it.second && it.first != "search" }

    fun catalogUrl(transport: String, type: String, catalogId: String, query: String): String? {
        if (!StremioMetadataPolicy.publicUrl(transport) || type !in setOf("movie", "series") ||
            catalogId.isBlank() || catalogId.length > 256 || query.trim().isBlank() || query.length > 200) return null
        val url = transport.toHttpUrlOrNull() ?: return null
        if (!url.encodedPath.endsWith("/manifest.json")) return null
        // Each extra is a URL-encoded value inside one path segment; keep ampersands and slashes in the query inert.
        val escaped = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        return url.newBuilder().query(null).encodedPath(url.encodedPath.removeSuffix("manifest.json"))
            .addPathSegment("catalog").addPathSegment(type).addPathSegment(catalogId)
            .addEncodedPathSegment("search=$escaped.json").build().toString()
    }

    /**
     * One card per title, first source first. A title counts as the same when the type and IMDb id match (the id
     * itself, or the "imdbId" an add-on supplied for an entry under its own id scheme), or when an entry with no
     * IMDb id has the same name and year as one that has. Entries without a year are never merged by name.
     */
    fun deduplicate(items: List<ContentItem>): List<ContentItem> {
        val identified = items.mapNotNullTo(HashSet()) { item -> named(item).takeIf { imdb(item) != null } }
        val seen = HashSet<String>()
        return items.filter { item ->
            val type = item.metadata["type"]
            val imdb = imdb(item)
            if (imdb != null) seen.add("$type/$imdb")
            else seen.add("$type/${item.id}") && named(item)?.let { it !in identified } != false
        }.take(80)
    }
    private fun imdb(item: ContentItem) = item.id.takeIf(imdbId::matches) ?: item.metadata["imdbId"]?.takeIf(imdbId::matches)
    private fun named(item: ContentItem): String? {
        val released = item.metadata["year"] ?: year.find(item.subtitle.orEmpty())?.value ?: return null
        val name = punctuation.replace(item.title.lowercase(), " ").trim().takeIf { it.isNotEmpty() } ?: return null
        return "${item.metadata["type"]}/$name/$released"
    }
}
