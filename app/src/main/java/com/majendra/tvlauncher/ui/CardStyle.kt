package com.majendra.tvlauncher.ui

import com.majendra.tvlauncher.data.ContentRow
import com.majendra.tvlauncher.providers.sports.StremioSportChannels

/**
 * Card sizes in dp. Each row uses one style; rows share a view pool, so the style is also the view type.
 * [rowDp] is the horizontal list's height: card padding, art, title, subtitle and progress track.
 */
enum class CardStyle(val widthDp: Int, val artDp: Int, val titleSp: Float, val titleLines: Int, val titleDp: Int,
                     val subtitleSp: Float, val rowDp: Int) {
    LANDSCAPE(260, 140, 15f, 2, 44, 12f, 238),
    /** Movies and shows: full 2:3 posters (the art carries the name) with just year and rating below. */
    POSTER(152, 228, 0f, 1, 0, 13f, 266),
    /** Watching stays identifiable even when artwork is absent or carries no printed title. */
    WATCHING_POSTER(152, 180, 15f, 2, 44, 12f, 266),
    /** Adult browse rows: permanent names, full poster art and room for the next row. */
    STREAMING_POSTER(116, 162, 13f, 2, 38, 11f, 246),
    /** Resume cards favour a wide still and a visible progress track. */
    STREAMING_CONTINUE(216, 116, 14f, 2, 42, 11f, 204),
    /** YouTube: smaller 16:9 thumbnails so more fit across the screen. */
    MEDIA(164, 92, 13f, 2, 40, 11f, 178),
    /** Live TV channel tiles: a logo with the programme on now, small enough to see a bunch at once. */
    CHANNEL(112, 63, 12f, 1, 20, 10f, 128),
    /** Stremio sports channels retain readable names and properly fitted station logos. */
    STREMIO_CHANNEL(164, 92, 14f, 2, 40, 11f, 176),
    /** Fixture posters keep their full artwork, with a readable two-line matchup below. */
    STREMIO_EVENT(164, 228, 13f, 2, 40, 11f, 312),
    /** Kids Corner movies and shows: a rounded 2:3 picture filling the card; the art already carries the name. */
    KIDS_POSTER(170, 255, 0f, 1, 0, 0f, 272),
    /** Kids Corner ABC episodes: big wide pictures with just a name. */
    KIDS_WIDE(300, 170, 17f, 1, 30, 0f, 230),
    /** Kids Corner "Watch TV now": a small live tile below the movies and shows. */
    KIDS_LIVE(164, 92, 14f, 1, 24, 0f, 146),
    /** Sports Centre scoreboard cards: both teams, the score and the clock, drawn by [ScoreCardAdapter]. */
    SCORE(300, 0, 0f, 1, 0, 0f, 158);

    /** The picture is the whole card: no frame, text or progress track. */
    val pictureOnly get() = this == KIDS_POSTER

    companion object {
        fun of(row: ContentRow, streaming: Boolean = false): CardStyle {
            if (streaming) StreamingBrowsePolicy.style(row)?.let { return it }
            val first = row.items.firstOrNull() ?: return LANDSCAPE
            return when {
                row.id == "watching-shows" -> WATCHING_POSTER
                row.id == "kids-abc-live" -> KIDS_LIVE
                first.source == "kids" &&(row.id in setOf("kids-movies", "kids-series") ||
                    first.metadata["pickerEligible"] == "true") -> KIDS_POSTER
                first.source == "kids" -> KIDS_WIDE
                first.metadata["sportsSource"] == "stremio" ->
                    if (StremioSportChannels.usesPosterCard(first)) STREMIO_EVENT else STREMIO_CHANNEL
                first.metadata["card"] != null -> SCORE
                first.source == "stremio" -> POSTER
                first.source == "youtube" -> MEDIA
                first.metadata["layout"] == "channel" -> CHANNEL
                first.metadata["layout"] == "app" -> MEDIA // App banners are 16:9.
                else -> LANDSCAPE
            }
        }
    }
}
