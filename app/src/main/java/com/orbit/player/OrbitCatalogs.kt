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

    // Franchises for the Collections catalog (TMDB collection ids, checked 2026-10-25). TMDB
    // has no list of popular collections, so these are chosen; a film page shows its own.
    private val collectionIds = listOf(
        1241, 10, 119, 121938, 645, 86311, 531241, 556, 125574, 263, 9485, 87359, 328, 404609,
        10194, 295, 84, 264, 8091, 135416, 528, 2344, 1575, 131635, 33514, 2150, 86066, 8354, 8650,
        748, 448150, 284433, 131295, 131292, 131296, 173710, 1709, 726871, 87096, 8945, 115575,
        230, 31562, 1570, 945, 86055, 304, 77816, 89137, 386382, 420, 2980, 8580, 422837, 9743,
        91361, 656, 313086, 2602, 8864, 10455, 41437
    )

    // Collections by id, once fetched: name, poster, backdrop, overview and its films.
    data class Collection(val id: Int, val name: String, val poster: String?, val backdrop: String?,
                          val overview: String, val parts: List<StremioAddons.Meta>)
    private val collections = java.util.concurrent.ConcurrentHashMap<Int, Collection>()

    // A TMDB collection and its films in release order (films not yet out last). Off the main thread.
    fun collection(id: Int, key: String): Collection? {
        collections[id]?.let { return it }
        return try {
            val d = get("collection/$id?language=en-GB", key)
            val names = movieGenres.entries.associate { (k, v) -> v to k }
            val parts = (0 until (d.optJSONArray("parts")?.length() ?: 0)).mapNotNull { d.optJSONArray("parts")!!.optJSONObject(it) }
                .filter { it.optString("poster_path").startsWith("/") }
                .sortedBy { it.optString("release_date").ifBlank { "9999" } }
                .map { r ->
                    StremioAddons.Meta(
                        id = "tmdb:${r.optInt("id")}", type = "movie", name = r.optString("title"),
                        poster = r.optString("poster_path"), year = r.optString("release_date").take(4),
                        description = r.optString("overview"),
                        rating = r.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { String.format(Locale.US, "%.1f", it) }.orEmpty(),
                        genres = r.optJSONArray("genre_ids")?.let { arr -> (0 until arr.length()).mapNotNull { names[arr.optInt(it)] } }.orEmpty(),
                        background = r.optString("backdrop_path").takeIf { it.startsWith("/") }
                    )
                }
            Collection(id, d.optString("name"), d.optString("poster_path").takeIf { it.startsWith("/") },
                d.optString("backdrop_path").takeIf { it.startsWith("/") }, d.optString("overview"), parts)
                .also { collections[id] = it }
        } catch (_: Exception) {
            null
        }
    }

    // The catalog shown when a collection is opened in Discover.
    fun collectionCatalog(id: Int, name: String) =
        StremioAddons.Catalog("$base|movie|collection:$id", addonName, base, "collection:$id", name, "movie", emptyList(), false, false)

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
            c("language", "World cinema", languages.keys.toList(), required = true) +
            listOfNotNull(if (type == "movie") c("collections", "Collections", emptyList()) else null)
    }

    fun page(catalog: StremioAddons.Catalog, pick: String?, skip: Int, key: String): StremioAddons.Page {
        // Collections: franchise posters, 20 at a time; an opened one lists its films.
        if (catalog.id == "collections") {
            val slice = collectionIds.drop(skip).take(20)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
            val found = slice.map { id -> pool.submit<Collection?> { collection(id, key) } }.mapNotNull { it.get() }
            pool.shutdown()
            val metas = found.filter { it.poster != null }.map { c ->
                val years = c.parts.mapNotNull { it.year.toIntOrNull() }
                StremioAddons.Meta(
                    id = "tmdbcol:${c.id}", type = "movie", name = c.name, poster = c.poster,
                    year = if (years.isEmpty()) "" else if (years.min() == years.max()) "${years.min()}" else "${years.min()}–${years.max()}",
                    description = c.overview.ifBlank { "${c.parts.size} films" },
                    rating = "", genres = listOf("${c.parts.size} films"), background = c.backdrop
                )
            }
            return StremioAddons.Page(metas, skip + 20 < collectionIds.size)
        }
        if (catalog.id.startsWith("collection:")) {
            val c = collection(catalog.id.removePrefix("collection:").toInt(), key)
                ?: throw IllegalStateException("Couldn't load the collection")
            return StremioAddons.Page(if (skip == 0) c.parts else emptyList(), false)
        }
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
