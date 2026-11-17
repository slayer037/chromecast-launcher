package com.majendra.tvlauncher.providers.sports

/** Sorts programme titles, channel names and leagues into the Sports Centre's per-sport rows. Pure; unit tested. */
object SportCategories {
    const val AFL = "AFL"
    const val NRL = "NRL"
    const val CRICKET = "Cricket"
    const val SOCCER = "Soccer"
    const val TENNIS = "Tennis"
    const val NFL = "NFL"
    const val BASKETBALL = "Basketball"
    const val BASEBALL = "Baseball"
    const val MOTORSPORT = "Motorsport"
    const val RUGBY = "Rugby"
    const val GOLF = "Golf"
    const val RACING = "Racing"
    const val COMBAT = "Combat"
    const val OTHER = "Other sport"
    val order = listOf(AFL, NRL, CRICKET, SOCCER, TENNIS, NFL, BASKETBALL, BASEBALL, MOTORSPORT, RUGBY, GOLF, RACING, COMBAT, OTHER)
    /** The sports followed at home, in the order their rows appear. Other sports stay out of Sports Centre's sport rows. */
    val followed = listOf(NFL, AFL, CRICKET, SOCCER, MOTORSPORT, BASEBALL, BASKETBALL)
    fun rowTitle(sport: String) = when (sport) {
        NFL -> "🏈  NFL"; AFL -> "🏉  AFL"; CRICKET -> "🏏  Cricket"; SOCCER -> "⚽  EPL & football"
        MOTORSPORT -> "🏁  Motor racing"; BASEBALL -> "⚾  Baseball"; BASKETBALL -> "🏀  Basketball"; else -> sport
    }

    // Most specific first: "rugby league" before "rugby", "motor racing" before "racing", "australian football" before soccer.
    private val rules = listOf(
        AFL to Regex("\\baflw?\\b|australian football|aussie rules|\\bfooty\\b|\\b(sanfl|wafl|vfl|vflw)\\b|brownlow"),
        NRL to Regex("\\bnrlw?\\b|rugby league|state of origin|super league|\\bqcup\\b|ron massey"),
        CRICKET to Regex("cricket|\\bwbbl\\b|\\bbbl\\b|big bash|\\bashes\\b|sheffield shield|\\bodi\\b|\\bt20|\\bipl\\b|test match|marsh cup"),
        NFL to Regex("\\bnfl\\b|american football|super bowl|monday night football|sunday night football"),
        BASEBALL to Regex("baseball|\\bmlb\\b|world series|\\babl\\b"),
        BASKETBALL to Regex("basketball|\\bw?nba\\b|\\bw?nbl\\b|euroleague|\\bncaab\\b|march madness"),
        MOTORSPORT to Regex("motorsport|motor sport|motor racing|\\bf1\\b|formula (1|one|2|e)|supercars|moto ?gp|nascar|indycar|\\brally\\b|wrc|superbike|" +
            "speedway|drag racing|\\bnhra\\b|\\bv8\\b|bathurst|grand prix|\\bmavtv\\b|motocross|supercross|\\bwec\\b|le mans"),
        TENNIS to Regex("tennis|\\batp\\b|\\bwta\\b|wimbledon|roland garros|davis cup|billie jean king cup|united cup|us open tennis|australian open"),
        RUGBY to Regex("rugby|wallabies|super rugby|six nations|bledisloe|rugby championship|wallaroos|\\bsevens\\b"),
        GOLF to Regex("golf|\\bl?pga\\b|liv golf|the masters|ryder cup|\\bdp world tour\\b"),
        RACING to Regex("horse racing|harness|greyhound|\\btrots\\b|melbourne cup|cox plate|caulfield cup|racing\\.com|trackside|sky racing|thoroughbred|" +
            "\\braces\\b|race day|raceday|\\bracing\\b|\\bstakes\\b"),
        COMBAT to Regex("\\bufc\\b|boxing|\\bmma\\b|wrestl|\\bwwe\\b|\\baew\\b|kickboxing|bellator|\\bpfl\\b|fight night|muay thai|\\bone championship\\b"),
        // Soccer after AFL/NFL/rugby, which own their own "football".
        SOCCER to Regex("soccer|football|a-league|\\bisuzu ute a-league\\b|premier league|\\bepl\\b|\\buefa\\b|champions league|europa league|la liga|serie a|" +
            "bundesliga|ligue 1|\\bfifa\\b|matildas|socceroos|\\bmls\\b|world cup qualif|fa cup|carabao|\\bfc\\b|united v |city v "),
        OTHER to Regex("college football|\\bncaaf\\b|\\bsports?\\b|\\bespn|olympic|cycling|tour de france|netball|hockey|\\bnhl\\b|surfing|\\bwsl\\b|swimming|" +
            "athletics|darts|snooker|billiards|volleyball|sailing|rowing|triathlon|skateboard|\\bx games\\b|red bull|fuel tv|extreme|skiing|" +
            "lacrosse|handball|badminton|squash|marathon|ironman|\\bgecko\\b")
    )

    /** The sport a programme title, description or channel name is about, or null when it does not look like sport. */
    fun classify(text: String?): String? {
        val lower = text?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (Regex("college football|\\bncaaf?\\b").containsMatchIn(lower)) return OTHER
        return rules.firstOrNull { (_, regex) -> regex.containsMatchIn(lower) }?.first
    }

    /** A specific sport only: the generic "sport" words do not count. */
    fun specific(text: String?): String? = classify(text)?.takeIf { it != OTHER }

    private val sportChannel = Regex("sport|racing|rush|golf|espn|fight|wrestl|tennis|gecko|trackside|horse|nfl|nba|nbl|motor|nhra|mavtv|" +
        "billiards|red bull|fuel tv|7mate|9 ?rush|racing\\.com|\\bfox (footy|league|cricket|sports)|\\bkayo\\b|bein|optus sport|stan sport|dazn|" +
        "eurosport|\\bufc\\b|\\bwwe\\b|\\bafl\\b|\\bnrl\\b|cricket|football|soccer|\\btab\\b|sky racing|extreme|olympic|\\barena\\b|\\btnt sports\\b")

    /** Channel names that are dedicated (or largely) sport, including free-to-air 7mate/9Rush and the racing channels. */
    fun isSportChannel(name: String?): Boolean = name?.lowercase()?.let { sportChannel.containsMatchIn(it) } == true

    /** The sport a channel mostly carries ("Fox Footy" → AFL), defaulting to [OTHER]. */
    fun ofChannel(name: String): String = specific(name.replace(Regex("(?i)\\bfox sports\\b"), "")) ?: when {
        Regex("(?i)fox league").containsMatchIn(name) -> NRL
        else -> OTHER
    }

    /** Stremio genres such as "Sports" or "Sport" mark a channel as sport even when its name does not. */
    fun isSportGenre(genres: List<String>): Boolean = genres.any { Regex("(?i)\\bsports?\\b").containsMatchIn(it) || specific(it) != null }

    /** "v", "vs" or "at" between two names: a live fixture rather than a magazine or news show. */
    fun looksLikeFixture(title: String): Boolean = Regex("(?i)\\S \\b(v|vs\\.?|versus)\\b \\S").containsMatchIn(title)

    /**
     * A guide programme's sport. The title decides first; the description only counts on a sport channel, so a
     * news bulletin that mentions the footy does not become an AFL fixture.
     */
    fun ofProgramme(title: String, description: String?, channelName: String, sportChannel: Boolean): String? {
        specific(title)?.let { return it }
        val magazine = Regex("(?i)news|tonight|sportscenter|magazine|talk|podcast|replay|classic|highlights|wrap|review|preview|\\bshow\\b").containsMatchIn(title)
        if (classify(title) == OTHER && !magazine) return OTHER // e.g. "Cycling: Tour de France" on SBS.
        if (!sportChannel) return null
        specific(description)?.let { return it }
        val channelSport = ofChannel(channelName)
        return when {
            channelSport != OTHER && !magazine -> channelSport
            looksLikeFixture(title) || Regex("(?i)\\blive\\b").containsMatchIn(title) -> OTHER
            else -> null
        }
    }
}
