package com.majendra.tvlauncher.providers

/** Eligibility is deliberately narrower than a Family/Animation genre filter. */
object KidsClassificationPolicy {
    fun isAustralianG(classification: String?, country: String?) = classification == "G" && country == "AU"

    fun matchesFilm(seed: KidsFilmSeed, imdbId: String, title: String, year: String): Boolean =
        imdbId == seed.imdbId && title.trim().equals(seed.title, ignoreCase = true) &&
            year.trim() == seed.year.toString()

    fun matchesSeries(seed: KidsSeriesSeed, imdbId: String, title: String, years: String): Boolean =
        imdbId == seed.imdbId && title.trim().equals(seed.title, ignoreCase = true) &&
            Regex("^[0-9]{4}(?:$|[-–—].*)").matches(years.trim()) && years.take(4) == seed.year.toString()

    fun groupForAbcShow(slug: String): String = KidsSeriesSeeds.all.firstOrNull { it.abcSlug == slug }
        ?.let { "imdb:${it.imdbId}" } ?: if (slug == "bluey") "imdb:tt7678620" else "abc-show:$slug"

    fun eligibleEpisode(classification: String?, expiresAt: Long?, now: Long): Boolean =
        isAustralianG(classification, "AU") && expiresAt != null && expiresAt > now

    fun currentProgramme(classification: String?, start: Long, end: Long, now: Long): Boolean =
        isAustralianG(classification, "AU") && start > 0 && end > start && start <= now && now < end

    /** The live destination always exists; only a verified current G programme is playable. */
    fun liveState(candidates: List<LiveProgramme>, now: Long): KidsLiveState {
        candidates.firstOrNull { it.title.isNotBlank() && currentProgramme(it.classification, it.start, it.end, now) }
            ?.let { return KidsLiveState.Verified(it) }
        val airing = candidates.filter { it.start in 1..now && now < it.end }
        if (airing.any { !isAustralianG(it.classification, "AU") })
            return KidsLiveState.Unavailable("Not rated G right now", "ABC Kids is showing a programme that isn't confirmed as G. Check back soon.")
        if (airing.isNotEmpty())
            return KidsLiveState.Unavailable("Programme details missing", "ABC didn't name the current programme. Press OK to check again.")
        return KidsLiveState.Unavailable("Between programmes", "ABC Kids is between programmes. Press OK to check again.")
    }
}

data class LiveProgramme(val title: String, val subtitle: String, val description: String,
    val classification: String?, val start: Long, val end: Long)

sealed class KidsLiveState {
    data class Verified(val programme: LiveProgramme) : KidsLiveState()
    data class Unavailable(val headline: String, val reason: String) : KidsLiveState()
}

data class KidsFilmSeed(val imdbId: String, val title: String, val year: Int, val classificationSource: String)

/** Reviewed film records, not title-only searches; see docs/KIDS_SOURCES.md. */
object KidsFilmSeeds {
    val all = listOf(
        KidsFilmSeed("tt0114709", "Toy Story", 1995, "toy-story"),
        KidsFilmSeed("tt0120363", "Toy Story 2", 1999, "toy-story-2"),
        KidsFilmSeed("tt0435761", "Toy Story 3", 2010, "toy-story-3"),
        KidsFilmSeed("tt0266543", "Finding Nemo", 2003, "finding-nemo"),
        KidsFilmSeed("tt2277860", "Finding Dory", 2016, "finding-dory"),
        KidsFilmSeed("tt0910970", "WALL·E", 2008, "wall-e-0"),
        KidsFilmSeed("tt0033563", "Dumbo", 1941, "dumbo"),
        KidsFilmSeed("tt1979376", "Toy Story 4", 2019, "toy-story-4"),
        KidsFilmSeed("tt0120623", "A Bug's Life", 1998, "bugs-life"),
        KidsFilmSeed("tt1661199", "Cinderella", 2015, "cinderella"),
        KidsFilmSeed("tt0061852", "The Jungle Book", 1967, "jungle-book"),
        KidsFilmSeed("tt0065421", "The Aristocats", 1970, "aristocats"),
        KidsFilmSeed("tt0097757", "The Little Mermaid", 1989, "little-mermaid-0"),
        KidsFilmSeed("tt0034492", "Bambi", 1942, "bambi"),
        KidsFilmSeed("tt0076363", "The Many Adventures of Winnie the Pooh", 1977, "many-adventures-winnie-pooh"),
        KidsFilmSeed("tt0220099", "The Tigger Movie", 2000, "tigger-movie-1"),
        KidsFilmSeed("tt0070608", "Robin Hood", 1973, "robin-hood-1"),
        KidsFilmSeed("tt0046183", "Peter Pan", 1953, "peter-pan-2"),
        KidsFilmSeed("tt0119282", "Hercules", 1997, "hercules"),
        KidsFilmSeed("tt0114148", "Pocahontas", 1995, "pocahontas-0"),
        KidsFilmSeed("tt0103639", "Aladdin", 1992, "aladdin-4"),
        KidsFilmSeed("tt0198781", "Monsters, Inc.", 2001, "monsters-inc-1"),
        KidsFilmSeed("tt0110357", "The Lion King", 1994, "lion-king-1")
    )
}

data class KidsSeriesSeed(val imdbId: String, val title: String, val year: Int,
    val classificationSource: String, val abcSlug: String)

/** Australian distributor classifications, distinct from foreign TV-Y/TV-G certificates. */
object KidsSeriesSeeds {
    val all = listOf(
        KidsSeriesSeed("tt4291050", "Hey Duggee", 2014, "hey-duggee", "hey-duggee"),
        KidsSeriesSeed("tt3121722", "PAW Patrol", 2013, "paw-patrol", "paw-patrol"),
        KidsSeriesSeed("tt0426769", "Peppa Pig", 2004, "peppa-pig", "peppa-pig"),
        KidsSeriesSeed("tt0983983", "Shaun the Sheep", 2007, "shaun-the-sheep", "shaun-the-sheep"),
        KidsSeriesSeed("tt0329829", "Fireman Sam", 1987, "fireman-sam", "fireman-sam"),
        KidsSeriesSeed("tt0086815", "Thomas & Friends", 1983, "thomas-and-friends", "thomas-and-friends"),
        KidsSeriesSeed("tt4458594", "Blaze and the Monster Machines", 2014, "blaze-and-the-monster-machines", "blaze-and-the-monster-machines"),
        KidsSeriesSeed("tt23858980", "Rubble & Crew", 2023, "rubble-and-crew", "rubble-and-crew")
    )
}
