package com.majendra.tvlauncher.providers

/**
 * Sorts antenna and TCL Channel channels into easy-to-scan Live TV rows by channel name and number. Shopping,
 * community and radio services are [HIDDEN]: they are left out of [order], so Live TV drops them.
 */
internal object LiveChannelCategories {
    const val MAIN = "Main channels"
    const val NEWS = "News"
    const val SPORT = "Sport"
    const val KIDS = "Kids & family"
    const val MOVIES = "Movies"
    const val DRAMA = "Drama"
    const val CRIME = "Crime & mystery"
    const val COMEDY = "Comedy"
    const val REALITY = "Reality"
    const val FOOD = "Food & cooking"
    const val TRAVEL = "Travel & adventure"
    const val NATURE = "Nature & animals"
    const val SCIENCE = "Science & documentary"
    const val HISTORY = "History"
    const val LIFESTYLE = "Home & lifestyle"
    const val MUSIC = "Music"
    const val CLASSIC = "Classic TV"
    const val GAMING = "Gaming & anime"
    const val MOTORS = "Cars & motors"
    const val INTERNATIONAL = "International"
    const val ENTERTAINMENT = "Entertainment"
    /** Shopping, community and radio: never shown. */
    const val HIDDEN = "Hidden"
    val order = listOf(MAIN, NEWS, SPORT, KIDS, MOVIES, DRAMA, CRIME, COMEDY, REALITY, FOOD, TRAVEL, NATURE, SCIENCE,
        HISTORY, LIFESTYLE, MUSIC, CLASSIC, GAMING, MOTORS, INTERNATIONAL, ENTERTAINMENT)

    fun visible(category: String) = category in order

    // The five networks' primary channels, after dropping place names, "HD" and "+1".
    private val main = setOf("abc", "abctv", "sbs", "sbsone", "7", "7hd", "seven", "channelnine", "nine", "9", "9hd", "10", "10hd", "ten")
    // Antenna multichannels whose names say nothing about what they show.
    private val antenna = mapOf("9gem" to DRAMA, "7bravo" to REALITY, "9life" to LIFESTYLE, "7two" to CLASSIC, "sbsfood" to FOOD,
        "sbsworldwatch" to NEWS, "abcentertains" to ENTERTAINMENT, "7mate" to ENTERTAINMENT, "9go" to ENTERTAINMENT)
    private fun r(pattern: String) = Regex(pattern)
    private val news = r("news|sky news|cnn|bbc world|bloomberg|al jazeera|aljazeera|weather|cnbc|euronews|\\bdw\\b|france 24|moconomy|worldwatch")
    // Includes TCL Channel kids streams (Baby Shark, Pokémon, Wiggles…), which say nothing like "kids" in the name.
    private val kids = r("kid|nick|cartoon|junior|family(?! (feud|guy))|baby|toon|disney|boomerang|pok[eé]mon|teletubbies|wiggles|smurf|oggy|" +
        "strawberry shortcake|ryan and friends|preston|my little pet|mr\\.? ?bean|pbs kids|sesame|peppa|paw patrol|lego|barbie|" +
        "bluey|thomas &|caillou|\\bdora\\b|blippi|cocomelon|tayo|robocar|masha|kartoon|duck ?tales")
    private val motors = r("motor|nhra|mavtv|\\bcars?\\b|\\bautos?\\b|top gear|wheeler|garage|drift|4x4|off.?road|truck|nascar|" +
        "indycar|formula|\\bf1\\b|\\brally|supercars|moto ?gp|speed|driving")
    private val sport = r("sport|racing|9 ?rush|golf|espn|fight|wrestl|tennis|gecko|trackside|horse|nfl|nba|nhl|mlb|billiards|" +
        "red bull tv|fuel tv|poker|surf|skate|boxing|ufc|mma|cricket|rugby|\\bafl\\b|nrl|football|soccer|basketball|baseball|" +
        "hockey|bowling|darts|snooker|olympic|athlet|pga|extreme|\\bski\\b|climb")
    private val gaming = r("gaming|gamer|e-?sports?|anime|retro ?crush|crunchyroll|twitch|\\bign\\b|video ?games?|nintendo|playstation|xbox")
    private val music = r("music|\\bmtv\\b|vevo|hits\\b|concert|karaoke|stingray|qello|jukebox|\\brock\\b|country music|" +
        "\\bpop\\b|hip ?hop|\\bdance\\b|clubbing|\\bdj\\b|jazz|blues|symphon")
    private val crime = r("crime|thriller|myster|detective|murder|\\bcsi\\b|forensic|investigat|\\bcops?\\b|police|" +
        "homicide|law (&|and) order|midsomer|poirot|agatha|unsolved|first 48|\\bfbi\\b|dateline|48 hours|suspense|noir|" +
        "sherlock|inspector|whodunit|cold case")
    private val food = r("food|cook|kitchen|chef|bak(e|ing|er)\\b|cuisine|recipe|tastemade|taste|ramsay|bon app|\\beats?\\b|bbq|" +
        "barbecue|grill|masterchef|jamie oliver|foodie|dinner|delicious|gourmet|wine")
    private val movies = r("movie|flix|film|cinema|hollywood|western|cowboy|bollywood|kung fu|horror|scream|action max|blockbuster")
    private val drama = r("drama|soap|telenovela|novela|\\bgem\\b|series ?channel|home and away|neighbours")
    private val comedy = r("comed|funny|laugh|sitcom|stand.?up|\\bjfl\\b|seinfeld|the office|always sunny|three's company|" +
        "carol burnett|the nanny|cheers|frasier|parody|prank|fail ?army|jokes?\\b")
    private val reality = r("reality|pawn|storage wars|housewives|bravo|survivor|love island|big brother|deadliest|bounty|" +
        "game show|price is right|deal or no deal|family feud|wheel of fortune|jeopardy|the chase|millionaire|wipeout|" +
        "fear factor|dating|\\bjudge|\\bcourt\\b|tattoo|\\brepo\\b|shark tank|dragons' den|ax men|swamp|alaska|gold rush|" +
        "bachelor|\\bidol\\b|got talent")
    private val nature = r("wild|nature|animal|\\bpets?\\b|terra mater|earth|planet|ocean|\\bsea\\b|safari|\\bzoo|\\bvets?\\b|" +
        "\\bdogs?\\b|\\bcats?\\b|nat ?geo|national geographic|jungle|shark|\\bbirds?\\b|aquarium|insect|reptile")
    private val travel = r("travel|adventur|explor|journey|around the world|trek|wander|voyage|destination|outdoor|fishing|hunting|" +
        "rick steves|globe|\\btrips?\\b|touris|nomad|road ?trip|backroads|expedition|passport|getaway|holiday|cruise")
    private val science = r("science|documentar|docu|mythbusters|how it'?s made|discovery|curiosity|space|nasa|\\btech|" +
        "engineer|smithsonian|\\bted\\b|\\bnova\\b|knowledge|magellan|real stories|\\bdocs?\\b|universe|cosmos|invent|" +
        "medical|hospital|air crash|disaster|mayday|unexplained|paranormal|\\bufos?\\b|aliens?\\b|machines")
    private val history = r("histor|\\bwars?\\b|wwii|ww2|military|ancient|battle|royal|empire|egypt|vikings?|medieval|archaeolog|heritage")
    private val lifestyle = r("home|house|garden|\\bdiy\\b|renovat|design|lifestyle|\\blife\\b|property|real estate|fashion|beauty|" +
        "wellness|yoga|fitness|workout|health|decor|craft|makeover|wedding|bride|selling|flip|tiny|relax|ambient|fireplace|" +
        "meditat|living|style|parent")
    private val classic = r("classic|retro|rewind|vintage|oldies|golden|\\b[5-9]0s\\b|nostalg|throwback|timeless|lassie|bonanza|" +
        "gunsmoke|i love lucy|happy days|baywatch|knight rider|a-team|star trek|twilight zone|dick van dyke|" +
        "andy griffith|beverly hillbillies|perry mason|columbo|murder she wrote|tv land|antenna tv")
    private val international = r("hindi|desi|punjabi|tamil|telugu|malayalam|bangla|urdu|español|espanol|latino|latina|en vivo|" +
        "italia|italiano|deutsch|german|français|francais|french|korea|k-?pop|arab|chinese|china|mandarin|cantonese|japan|turk|" +
        "greek|vietnam|filipino|pinoy|tagalog|russian|portug|brasil|brazil|africa|persian|farsi|\\bthai\\b|indonesia|polski|polish|hebrew|israel")
    // Names written in these scripts are foreign-language channels.
    private val scripts = setOf("CYRILLIC", "ARABIC", "HAN", "HANGUL", "DEVANAGARI", "GREEK", "THAI", "HIRAGANA", "KATAKANA", "HEBREW",
        "TAMIL", "TELUGU", "BENGALI", "GURMUKHI", "MALAYALAM")
    private val shopping = r("tvsn|shop|you\\.tv|channel 31|\\bc31\\b|^extra$|\\bextra (vic|melbourne)|church|faith|hope channel|" +
        "qvc|infomercial|gospel|bible|jesus|christian|god tv|daystar|\\btbn\\b|worship|ministr")
    // ABC's digital radio sits on low antenna numbers (26-29), so match the stations by name too.
    private val radio = r("radio|double j|triple j|jazz|\\bfm\\b|\\babc rn\\b|abc classic|abc country|listen|sbs (pop|chill)|" +
        "^abc (melbourne|sydney|brisbane|adelaide|perth|hobart|darwin|canberra)$") // ABC local radio, e.g. "ABC MELBOURNE".
    // The first matching rule wins, so specific themes (kids, motorsport, crime, cooking) beat broad ones.
    private val rules = listOf(NEWS to news, KIDS to kids, MOTORS to motors, SPORT to sport, GAMING to gaming, MUSIC to music,
        CRIME to crime, FOOD to food, MOVIES to movies, DRAMA to drama, COMEDY to comedy, REALITY to reality, NATURE to nature,
        TRAVEL to travel, SCIENCE to science, HISTORY to history, LIFESTYLE to lifestyle, CLASSIC to classic, INTERNATIONAL to international)

    /** [tclChannel]: TCL Channel streams use numbers from 100 up, so 200+ only means radio on the antenna. */
    fun of(name: String, number: String?, tclChannel: Boolean = false): String {
        val lower = name.lowercase()
        val num = number?.toDoubleOrNull()?.takeUnless { tclChannel }
        val base = lower.replace(Regex("\\b(melbourne|victoria|vic|australia)\\b|\\+1|\\(.*?\\)"), "")
            .replace(Regex("\\bhd\\b"), "hd").replace(Regex("[^a-z0-9]"), "")
        // TCL Channel jazz or classical streams are video channels; only antenna services count as radio by name and number.
        val radioService = if (tclChannel) Regex("\\bradio\\b").containsMatchIn(lower) else (num != null && num >= 200) || radio.containsMatchIn(lower)
        return when {
            radioService || shopping.containsMatchIn(lower) -> HIDDEN
            base in main || base.removeSuffix("hd") in main -> MAIN
            else -> antenna[base] ?: antenna[base.removeSuffix("hd")] ?: rules.firstOrNull { it.second.containsMatchIn(lower) }?.first
                ?: INTERNATIONAL.takeIf { name.any { c -> Character.UnicodeScript.of(c.code).name in scripts } } ?: ENTERTAINMENT
        }
    }
}
