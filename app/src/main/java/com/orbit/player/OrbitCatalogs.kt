package com.orbit.player

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.Locale

// Orbit's own Discover catalogs, from TMDB's discover lists: Popular, Trending, Top rated,
// New releases and Hidden gems, a catalog per decade, what's on each streaming service in
// the viewer's country, and world cinema by language. Each pages through TMDB 20 titles at a
// time, so Discover scrolls on for as long as there's more. The list catalogs and decades
// take a genre; the service and language catalogs take their service or language in the
// genre picker's place.
object OrbitCatalogs {

    const val base = "orbit://tmdb"
    const val addonName = "Orbit"

    // TMDB's genre ids, in the picker's order.
    private val movieGenres = linkedMapOf(
        "Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80,
        "Documentary" to 99, "Drama" to 18, "Family" to 10751, "Fantasy" to 14, "History" to 36,
        "Horror" to 27, "Music" to 10402, "Mystery" to 9648, "Romance" to 10749,
        "Science Fiction" to 878, "Thriller" to 53, "War" to 10752, "Western" to 37
    )
    private val tvGenres = linkedMapOf(
        "Action & Adventure" to 10759, "Animation" to 16, "Comedy" to 35, "Crime" to 80,
        "Documentary" to 99, "Drama" to 18, "Family" to 10751, "Kids" to 10762, "Mystery" to 9648,
        "Reality" to 10764, "Sci-Fi & Fantasy" to 10765, "Soap" to 10766, "Talk" to 10767,
        "War & Politics" to 10768, "Western" to 37
    )
    private fun genres(type: String) = if (type == "movie") movieGenres else tvGenres

    // Used when TMDB's list of the country's services can't be fetched.
    private val fallbackServices = linkedMapOf(
        "Netflix" to 8, "Prime Video" to 9, "Disney+" to 337, "Apple TV+" to 350, "Max" to 1899,
        "Paramount+" to 531, "Hulu" to 15, "Peacock" to 386, "NOW" to 39, "Sky Go" to 29,
        "BBC iPlayer" to 38, "ITVX" to 41, "Channel 4" to 103, "Crunchyroll" to 283, "MUBI" to 11
    )

    // The streaming services in the viewer's country, in TMDB's order for it (most watched
    // first): shops, rentals and the "Amazon Channel"-style duplicates left out, so each is a
    // service with something to watch. Fetched once per country and type.
    private val serviceCache = java.util.concurrent.ConcurrentHashMap<String, LinkedHashMap<String, Int>>()
    private val notAService = Regex(
        "store|amazon video|channel$|amazon channel|apple tv channel|roku premium|justwatch|rakuten|google play|" +
            "youtube|microsoft|kids|with ads|vudu|fandango|plex channel|chili|sky store|kino|buy|rent",
        RegexOption.IGNORE_CASE
    )

    private fun region() = Locale.getDefault().country.ifBlank { "GB" }

    private fun services(type: String, key: String): LinkedHashMap<String, Int> {
        val kind = if (type == "series") "tv" else "movie"
        val region = region()
        return serviceCache.getOrPut("$region|$kind") {
            try {
                val results = get("watch/providers/$kind?watch_region=$region", key).optJSONArray("results")
                val found = (0 until (results?.length() ?: 0)).mapNotNull { results!!.optJSONObject(it) }
                    .filter { !notAService.containsMatchIn(it.optString("provider_name")) }
                    .sortedBy { it.optJSONObject("display_priorities")?.optInt(region, 999) ?: it.optInt("display_priority", 999) }
                    .take(24)
                LinkedHashMap(found.associate { it.optString("provider_name") to it.optInt("provider_id") })
                    .takeIf { it.isNotEmpty() } ?: fallbackServices
            } catch (_: Exception) {
                return fallbackServices
            }
        }
    }

    private val languages = linkedMapOf(
        "Korean" to "ko", "Japanese" to "ja", "French" to "fr", "Spanish" to "es", "Hindi" to "hi",
        "Italian" to "it", "German" to "de", "Chinese" to "zh", "Cantonese" to "cn", "Danish" to "da",
        "Swedish" to "sv", "Norwegian" to "no", "Portuguese" to "pt", "Turkish" to "tr", "Thai" to "th"
    )

    private val decades = listOf(2020, 2010, 2000, 1990, 1980, 1970)

    // The catalogs, for movies then series; Discover lists them before the addons' own.
    // Call off the main thread (the services list comes from TMDB).
    fun catalogs(key: String): List<StremioAddons.Catalog> = listOf("movie", "series").flatMap { type ->
        val g = genres(type).keys.toList()
        fun c(id: String, name: String, picks: List<String> = g, required: Boolean = false) =
            StremioAddons.Catalog("$base|$type|$id", addonName, base, id, name, type, picks, required, true)
        listOf(
            c("popular", "Popular"),
            c("trending", "Trending this week", emptyList()),
            c("top", "Top rated"),
            c("new", "New releases"),
            c("gems", "Hidden gems")
        ) + decades.map { c("decade:$it", "${it}s") } +
            c("decade:1900", "Classics (before 1970)") +
            c("service", "Streaming on", services(type, key).keys.toList(), required = true) +
            c("language", "World cinema", languages.keys.toList(), required = true)
    }

    fun page(catalog: StremioAddons.Catalog, pick: String?, skip: Int, key: String): StremioAddons.Page {
        val type = catalog.type
        val tv = type == "series"
        val kind = if (tv) "tv" else "movie"
        val pageNo = skip / 20 + 1
        val dateField = if (tv) "first_air_date" else "primary_release_date"
        val today = String.format(Locale.US, "%tF", Calendar.getInstance())
        val region = region()
        val params = mutableListOf("page=$pageNo", "include_adult=false", "language=en-GB")
        val genreId = pick?.let { genres(type)[it] }
        var path = "discover/$kind"
        when {
            catalog.id == "trending" -> path = "trending/$kind/week"
            catalog.id == "popular" -> params += "sort_by=popularity.desc"
            catalog.id == "top" -> params += listOf("sort_by=vote_average.desc", "vote_count.gte=${if (tv) 300 else 1000}")
            catalog.id == "new" -> {
                val from = Calendar.getInstance().apply { add(Calendar.MONTH, -4) }
                params += listOf("sort_by=popularity.desc", "$dateField.lte=$today",
                    "$dateField.gte=${String.format(Locale.US, "%tF", from)}", "vote_count.gte=5")
            }
            catalog.id == "gems" -> params += listOf("sort_by=vote_average.desc", "vote_average.gte=7.4",
                "vote_count.gte=150", "vote_count.lte=${if (tv) 1500 else 2500}")
            catalog.id.startsWith("decade:") -> {
                val start = catalog.id.removePrefix("decade:").toInt()
                val end = if (start == 1900) 1969 else start + 9
                params += listOf("sort_by=popularity.desc", "$dateField.gte=$start-01-01",
                    "$dateField.lte=$end-12-31", "vote_count.gte=${if (start == 1900) 200 else 50}")
            }
            catalog.id == "service" -> {
                // Subscription, free and with-ads titles alike (BBC iPlayer, Channel 4, Tubi …).
                val list = services(type, key)
                val id = list[pick] ?: list.values.first()
                params += listOf("sort_by=popularity.desc", "with_watch_providers=$id", "watch_region=$region")
            }
            catalog.id == "language" -> {
                val code = languages[pick] ?: languages.values.first()
                params += listOf("sort_by=popularity.desc", "with_original_language=$code", "vote_count.gte=20")
            }
        }
        if (genreId != null && path.startsWith("discover")) params += "with_genres=$genreId"
        // Hidden gems without a genre: concert films and documentaries crowd the top otherwise.
        else if (catalog.id == "gems") params += "without_genres=99,10402"
        val body = get("$path?${params.joinToString("&")}", key)
        val results = body.optJSONArray("results")
        val names = genres(type).entries.associate { (k, v) -> v to k }
        val metas = (0 until (results?.length() ?: 0)).mapNotNull { i ->
            val r = results!!.optJSONObject(i) ?: return@mapNotNull null
            val id = r.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val poster = r.optString("poster_path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
            val date = r.optString(if (tv) "first_air_date" else "release_date")
            StremioAddons.Meta(
                id = "tmdb:$id",
                type = type,
                name = r.optString(if (tv) "name" else "title"),
                poster = poster,
                year = date.take(4),
                description = r.optString("overview"),
                rating = r.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                genres = r.optJSONArray("genre_ids")?.let { arr -> (0 until arr.length()).mapNotNull { names[arr.optInt(it)] } }.orEmpty(),
                background = r.optString("backdrop_path").takeIf { it.startsWith("/") }
            )
        }
        val totalPages = body.optInt("total_pages", pageNo).coerceAtMost(500)
        return StremioAddons.Page(metas, pageNo < totalPages)
    }

    private fun get(path: String, key: String): JSONObject {
        val connection = URL("https://api.themoviedb.org/3/$path&api_key=${URLEncoder.encode(key, "UTF-8")}")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 12000
            if (connection.responseCode !in 200..299) throw IllegalStateException("TMDB HTTP ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
