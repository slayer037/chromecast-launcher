package com.majendra.tvlauncher.providers.sports

import com.majendra.tvlauncher.data.ContentItem
import java.net.URLEncoder
import org.json.JSONObject

/** Sports channels and event listings from installed Stremio add-ons; availability is checked in Stremio. */
object StremioSportChannels {
    data class Channel(val type: String, val id: String, val name: String, val image: String?, val description: String?,
                       val genres: List<String>, val artworkKind: String? = null) {
        val sport get() = SportCategories.ofChannel(name).takeIf { it != SportCategories.OTHER }
            ?: genres.firstNotNullOfOrNull { SportCategories.specific(it) } ?: SportCategories.OTHER
        val event get() = SportCategories.looksLikeFixture(name) || Regex("(?i)\\S\\s+at\\s+\\S").containsMatchIn(name)
    }
    const val PACKAGE = "com.stremio.one"

    /** Artwork shape is independent of whether a catalogue title clearly identifies an event. */
    fun usesPosterCard(item: ContentItem) = item.metadata["stremioKind"] == "event" || item.metadata["artworkKind"] == "poster"

    /** Channels in one catalogue response whose name or genre looks like sport. */
    fun parse(json: JSONObject?, type: String, sportFiltered: Boolean = false): List<Channel> {
        val metas = json?.optJSONArray("metas") ?: json?.optJSONArray("metasDetailed") ?: return emptyList()
        return (0 until minOf(metas.length(), 400)).mapNotNull { index ->
            val meta = metas.optJSONObject(index) ?: return@mapNotNull null
            val id = meta.optString("id").trim()
            val name = meta.optString("name").trim()
            if (id.isBlank() || id.length > 512 || name.isBlank() || name.length > 200) return@mapNotNull null
            val genres = listOf("genres", "genre").flatMap { key ->
                meta.optJSONArray(key)?.let { a -> (0 until minOf(a.length(), 12)).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }
                    ?: listOfNotNull(meta.optString(key).takeIf { it.isNotBlank() && !meta.isNull(key) })
            }
            if (!sportFiltered && !SportCategories.isSportChannel(name) && !SportCategories.isSportGenre(genres)) return@mapNotNull null
            val artwork = listOf("logo", "poster", "background").firstNotNullOfOrNull { key ->
                meta.optString(key).takeIf { it.startsWith("https://") }?.let { key to it }
            }
            Channel(type, id, name, artwork?.second, meta.optString("description").trim().takeIf { it.isNotBlank() && !meta.isNull("description") }?.take(2_000), genres, artwork?.first)
        }
    }

    fun detailUri(channel: Channel) = "stremio:///detail/${channel.type}/" + URLEncoder.encode(channel.id, "UTF-8").replace("+", "%20")

    fun item(channel: Channel, prefix: String): ContentItem = ContentItem("$prefix:stremio:${channel.type}:${channel.id}".take(600), "kayo", channel.name,
        if (channel.sport == SportCategories.OTHER) "Open in Stremio" else "${channel.sport} · Stremio", channel.description,
        channel.image, channel.image, playbackUri = detailUri(channel),
        metadata = mapOf("sportsSource" to "stremio", "accountAuthorised" to "true", "sport" to channel.sport, "type" to channel.type, "package" to PACKAGE,
            "playbackPackage" to PACKAGE, "channelId" to "stremio:${channel.id}", "channelName" to channel.name,
            "layout" to "channel", "stremioKind" to if (channel.event) "event" else "channel") +
            (channel.artworkKind?.let { mapOf("artworkKind" to it) } ?: emptyMap()) +
            (if (channel.artworkKind == "logo") mapOf("logo" to "true") else emptyMap()))
}
