package com.majendra.tvlauncher.providers.sports

import com.majendra.tvlauncher.data.ContentItem
import java.io.StringReader
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** Headlines only: cards show the story in the details dialog and never open a player. */
object SportsNews {
    data class Headline(val title: String, val summary: String?, val image: String?, val published: Long, val link: String?, val source: String)
    data class Feed(val url: String, val label: String, val rss: Boolean)

    // Verified 2026-10-25: ABC Grandstand Sport RSS (25 items) and ESPN league news JSON return 200 without keys.
    val feeds = listOf(
        Feed("https://www.abc.net.au/news/feed/2942460/rss.xml", "ABC Sport", true),
        // Verified 2026-10-27: NFL news responds with articles, using the same bounded JSON parser.
        Feed("https://site.api.espn.com/apis/site/v2/sports/football/nfl/news", "ESPN NFL", false),
        Feed("https://site.api.espn.com/apis/site/v2/sports/australian-football/afl/news", "ESPN AFL", false),
        Feed("https://site.api.espn.com/apis/site/v2/sports/rugby-league/3/news", "ESPN NRL", false),
        Feed("https://site.api.espn.com/apis/site/v2/sports/soccer/aus.1/news", "ESPN A-League", false),
        Feed("https://site.api.espn.com/apis/site/v2/sports/soccer/eng.1/news", "ESPN Premier League", false)
    )
    const val MAX = 40

    fun espn(json: String, source: String): List<Headline> {
        val articles = runCatching { JSONObject(json).optJSONArray("articles") }.getOrNull() ?: return emptyList()
        return (0 until minOf(articles.length(), 60)).mapNotNull { i ->
            val a = articles.optJSONObject(i) ?: return@mapNotNull null
            val title = a.optString("headline").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val published = EspnScores.time(a.optString("published")) ?: return@mapNotNull null
            val image = a.optJSONArray("images")?.optJSONObject(0)?.optString("url")?.takeIf { it.startsWith("https://") }
            val link = a.optJSONObject("links")?.optJSONObject("web")?.optString("href")?.takeIf { it.startsWith("https://") }
            Headline(title, a.optString("description").trim().takeIf { it.isNotBlank() }, image, published, link, source)
        }
    }

    /** RSS 2.0 with Media RSS images. No DTDs or external entities are ever loaded. */
    fun rss(xml: String, source: String): List<Headline> {
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.length > SportsHttp.MAX_BYTES) return emptyList()
        val items = mutableListOf<Headline>()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = false }
        for (feature in listOf("http://xml.org/sax/features/external-general-entities", "http://xml.org/sax/features/external-parameter-entities",
            "http://apache.org/xml/features/nonvalidating/load-external-dtd")) runCatching { factory.setFeature(feature, false) }
        val reader = runCatching { factory.newSAXParser().xmlReader }.getOrNull() ?: return emptyList()
        reader.entityResolver = org.xml.sax.EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = object : DefaultHandler() {
            var inItem = false; var title = ""; var description: String? = null; var link: String? = null
            var date: Long? = null; var image: String? = null; var defaultImage: String? = null
            val text = StringBuilder()
            override fun startElement(uri: String?, localName: String?, qName: String, a: Attributes) {
                text.setLength(0)
                when (qName) {
                    "item" -> { inItem = true; title = ""; description = null; link = null; date = null; image = null; defaultImage = null }
                    "media:content", "media:thumbnail" -> if (inItem) a.getValue("url")?.takeIf { it.startsWith("https://") }?.let { url ->
                        if (image == null) image = url
                        if (a.getValue("isDefault") == "true") defaultImage = url
                    }
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inItem && text.length < 8_000) text.append(ch, start, length) }
            override fun endElement(uri: String?, localName: String?, qName: String) {
                if (!inItem) return
                val value = text.toString().trim()
                when (qName) {
                    "title" -> if (title.isEmpty()) title = value
                    "description" -> if (description == null) description = value.replace(Regex("<[^>]+>"), "").trim().takeIf { it.isNotBlank() }
                    "link" -> if (link == null) link = value.takeIf { it.startsWith("https://") }
                    "pubDate" -> date = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    "item" -> {
                        inItem = false
                        val published = date
                        if (title.isNotBlank() && published != null && items.size < 60)
                            items += Headline(title, description, defaultImage ?: image, published, link, source)
                    }
                }
            }
        }
        return runCatching { reader.parse(InputSource(StringReader(xml))); items.toList() }.getOrDefault(items.toList())
    }

    /** Newest first, one card per story across feeds, within the last three days. */
    fun merge(headlines: List<Headline>, now: Long): List<Headline> = headlines
        .filter { it.published <= now + SportsTime.HOUR && now - it.published <= 72 * SportsTime.HOUR }
        .sortedByDescending { it.published }
        .distinctBy { it.title.lowercase().replace(Regex("[^a-z0-9]"), "") }
        .take(MAX)

    fun item(headline: Headline, now: Long, zone: ZoneId): ContentItem = ContentItem(
        "news:${headline.link ?: headline.title}".take(512), "kayo", headline.title,
        "${headline.source} · ${SportsTime.age(headline.published, now, zone)}", headline.summary,
        headline.image, headline.image, startTime = headline.published,
        metadata = buildMap {
            put("sportsSource", "news"); put("openDetails", "true"); put("newsSource", headline.source)
            put("card", "headline"); put("chip", headline.source); put("clock", SportsTime.age(headline.published, now, zone))
            SportCategories.specific(headline.title + " " + headline.summary.orEmpty())?.let { put("sport", it) }
            headline.link?.let { put("articleUrl", it) }
        })
}
