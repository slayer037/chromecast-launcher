package com.majendra.tvlauncher.providers.youtube

data class YouTubeCategory(val id: String, val label: String, val keywords: List<String>, val queries: List<String>) {
    private val patterns = keywords.map { Regex("(?<![\\p{L}\\p{N}])${Regex.escape(it)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) }
    fun score(text: String) = patterns.count { it.containsMatchIn(text) }
}

/** Keyword categoriser for the user's published recommendations; the first three are his standing interests and always get a row. */
object YouTubeCategories {
    val all = listOf(
        YouTubeCategory("tech-ai", "Tech & AI", listOf("ai", "a.i.", "artificial intelligence", "gpt", "chatgpt", "openai", "claude", "anthropic", "gemini", "llm", "llms",
            "agi", "deepseek", "grok", "copilot", "cursor", "vibe coding", "vibe code", "coding", "programming", "programmer", "developer", "software", "python",
            "javascript", "nvidia", "tech", "technology", "apple", "iphone", "android", "google", "robot", "robots", "agent", "agents", "model", "startup", "mkbhd", "fireship"),
            listOf("AI news this week", "new AI model released", "vibe coding", "Claude Code", "AI agents explained", "ChatGPT vs Claude vs Gemini",
                "AI coding tools", "tech news this week", "OpenAI news", "build an app with AI", "Nvidia AI", "Fireship")),
        YouTubeCategory("football", "Football", listOf("football", "soccer", "ronaldo", "cristiano", "cr7", "messi", "premier league", "champions league", "la liga",
            "serie a", "bundesliga", "uefa", "fifa", "world cup", "al nassr", "real madrid", "barcelona", "man united", "manchester united", "man city",
            "manchester city", "liverpool", "arsenal", "chelsea", "tottenham", "goal", "goals", "mbappe", "haaland", "neymar", "a-league", "socceroos", "penalty"),
            listOf("Cristiano Ronaldo", "Ronaldo goals", "Premier League highlights", "Champions League highlights", "Al Nassr highlights",
                "football skills", "Real Madrid highlights", "best football goals this week", "Ronaldo skills", "A-League highlights", "football documentary")),
        YouTubeCategory("food", "Food", listOf("food", "recipe", "recipes", "cooking", "cook", "chef", "kitchen", "bbq", "barbecue", "steak", "street food",
            "restaurant", "eating", "baking", "pizza", "burger", "ramen", "sushi", "mukbang", "gordon ramsay", "meal", "dinner", "taste test"),
            listOf("street food", "easy dinner recipes", "Gordon Ramsay", "best steak", "food review", "Japanese street food", "BBQ brisket",
                "chef cooking at home", "pizza recipe", "Joshua Weissman", "Nick DiGiovanni")),
        YouTubeCategory("sport", "Sport", listOf("nrl", "afl", "cricket", "ufc", "boxing", "f1", "formula 1", "nba", "nfl", "tennis", "golf", "rugby", "olympics", "mma"),
            listOf("NRL highlights", "AFL highlights", "F1 highlights", "UFC highlights", "cricket highlights", "NBA highlights")),
        YouTubeCategory("science", "Science & Space", listOf("science", "space", "nasa", "spacex", "physics", "universe", "planet", "rocket", "black hole", "engineering", "experiment"),
            listOf("space news", "SpaceX launch", "Veritasium", "science explained", "engineering explained", "Kurzgesagt")),
        YouTubeCategory("cars", "Cars", listOf("car", "cars", "tesla", "ev", "supercar", "porsche", "ferrari", "lamborghini", "bmw", "toyota", "drag race", "review drive"),
            listOf("car reviews", "Tesla news", "supercar review", "Top Gear", "Carwow drag race")),
        YouTubeCategory("money", "Business & Money", listOf("business", "money", "stock", "stocks", "invest", "investing", "crypto", "bitcoin", "economy", "entrepreneur", "marketing"),
            listOf("business news", "investing explained", "entrepreneur interview", "marketing strategy", "economy explained")),
        YouTubeCategory("comedy", "Comedy", listOf("comedy", "funny", "stand-up", "standup", "prank", "sketch", "snl", "roast"),
            listOf("stand up comedy", "funny sketches", "SNL", "comedy podcast clips")),
        YouTubeCategory("gaming", "Gaming", listOf("gaming", "gameplay", "minecraft", "fortnite", "playstation", "xbox", "nintendo", "gta", "fifa 26", "ea fc", "walkthrough"),
            listOf("gaming news", "new game trailers", "EA FC", "GTA 6"))
    )
    val defaults = listOf("tech-ai", "football", "food")
    fun get(id: String) = all.first { it.id == id }
    /** Best keyword match on title + channel; ties go to the earlier (standing-interest) category; no match -> null. */
    fun classify(title: String, channel: String = ""): YouTubeCategory? {
        val text = "$title $channel"
        return all.map { it to it.score(text) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }
}
