package com.orbit.player

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

// TMDB as a metadata source for addon titles, after Nuvio's "TMDB Enrichment": titles are
// matched by their IMDb or TMDB id (not by searching the name), and one request returns
// the details, title logo, credits and recommendations.
object TmdbMeta {

    data class Details(
        val mediaType: String, // "movie" or "tv"
        val tmdbId: Int,
        val imdbId: String?,
        val metadata: TmdbTest.FilmMetadata,
        val logoPath: String?,
        val status: String,
        val studios: List<String>,
        val director: String?,
        val cast: List<TmdbTest.CastMember>,
        val recommendations: List<TmdbTest.CatalogueItem>,
        val trailerKey: String? = null // YouTube video id
    )

    private val cache = ConcurrentHashMap<String, Details>()
    private val ids = ConcurrentHashMap<String, Pair<String, Int>>()

    // Addon id ("tt…" or "tmdb:…") to TMDB's media type and id.
    fun resolve(addonId: String, type: String, key: String): Pair<String, Int>? {
        ids[addonId]?.let { return it }
        val mediaType = if (type == "series" || type == "tv") "tv" else "movie"
        val found = when {
            addonId.startsWith("tmdb:") -> addonId.removePrefix("tmdb:").substringBefore(':').toIntOrNull()?.let { mediaType to it }
            addonId.startsWith("tt") -> {
                val body = get("find/${addonId.substringBefore(':')}?external_source=imdb_id", key)
                val list = body.optJSONArray(if (mediaType == "tv") "tv_results" else "movie_results")
                    ?.takeIf { it.length() > 0 }
                    ?: body.optJSONArray(if (mediaType == "tv") "movie_results" else "tv_results")
                list?.optJSONObject(0)?.let { r ->
                    val actualType = if (r.has("first_air_date") || r.optString("media_type") == "tv") "tv" else "movie"
                    actualType to r.optInt("id")
                }
            }
            else -> null
        } ?: return null
        ids[addonId] = found
        return found
    }

    // Last resort for titles without ids: TMDB's search, best exact-title match.
    fun search(title: String, mediaType: String?, key: String): Pair<String, Int>? {
        val results = get("search/multi?query=${URLEncoder.encode(title, "UTF-8")}", key).optJSONArray("results") ?: return null
        val candidates = (0 until results.length()).map { results.getJSONObject(it) }
            .filter { it.optString("media_type") in setOf("movie", "tv") && (mediaType == null || it.optString("media_type") == mediaType) }
        val match = candidates.firstOrNull { it.optString("title").ifBlank { it.optString("name") }.equals(title.trim(), true) }
            ?: candidates.firstOrNull() ?: return null
        return match.optString("media_type") to match.optInt("id")
    }

    fun details(mediaType: String, tmdbId: Int, key: String): Details? {
        val cacheKey = "$mediaType:$tmdbId"
        cache[cacheKey]?.let { return it }
        val d = get(
            "$mediaType/$tmdbId?append_to_response=credits,${if (mediaType == "tv") "aggregate_credits," else ""}images,recommendations,external_ids,videos" +
                "&include_image_language=en,null&include_video_language=en,null",
            key
        )
        val date = d.optString("release_date").ifBlank { d.optString("first_air_date") }
        val runtime = if (mediaType == "movie") d.optInt("runtime", 0).takeIf { it > 0 }
            else d.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
        val metadata = TmdbTest.FilmMetadata(
            title = d.optString("title").ifBlank { d.optString("name") },
            releaseYear = date.take(4),
            genres = names(d.optJSONArray("genres")),
            runtimeMinutes = runtime,
            overview = d.optString("overview"),
            mediaType = mediaType,
            tmdbId = tmdbId,
            backdropPath = d.optString("backdrop_path").takeIf { it.startsWith("/") },
            rating = d.optDouble("vote_average", 0.0),
            tagline = d.optString("tagline"),
            seasonCount = d.optInt("number_of_seasons", 0)
        )
        // English logo first, then one with no text language.
        val logos = d.optJSONObject("images")?.optJSONArray("logos")
        val logo = logos?.let { arr ->
            val all = (0 until arr.length()).map { arr.getJSONObject(it) }
            (all.firstOrNull { it.optString("iso_639_1") == "en" } ?: all.firstOrNull())?.optString("file_path")
        }?.takeIf { it.startsWith("/") }
        val credits = d.optJSONObject("credits")
        // Shows: the whole run's cast (aggregate_credits) rather than just the latest season's.
        val showCast = d.optJSONObject("aggregate_credits")?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), 20)).map { arr.getJSONObject(it) }.map {
                TmdbTest.CastMember(it.optString("name"),
                    it.optJSONArray("roles")?.optJSONObject(0)?.optString("character").orEmpty(),
                    it.optString("profile_path").takeIf { p -> p.startsWith("/") })
            }
        }?.takeIf { it.isNotEmpty() }
        val cast = showCast ?: credits?.optJSONArray("cast")?.let { arr ->
            (0 until minOf(arr.length(), 20)).map { arr.getJSONObject(it) }.map {
                TmdbTest.CastMember(it.optString("name"), it.optString("character"),
                    it.optString("profile_path").takeIf { p -> p.startsWith("/") })
            }
        }.orEmpty()
        val director = credits?.optJSONArray("crew")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.firstOrNull { it.optString("job") == "Director" }?.optString("name")
        } ?: d.optJSONArray("created_by")?.optJSONObject(0)?.optString("name")
        val recs = d.optJSONObject("recommendations")?.optJSONArray("results")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.mapNotNull { r ->
                val poster = r.optString("poster_path").takeIf { it.startsWith("/") } ?: return@mapNotNull null
                TmdbTest.CatalogueItem(
                    id = r.optInt("id"),
                    title = r.optString("title").ifBlank { r.optString("name") },
                    overview = r.optString("overview"),
                    posterPath = poster,
                    mediaType = r.optString("media_type").ifBlank { mediaType },
                    releaseYear = r.optString("release_date").ifBlank { r.optString("first_air_date") }.take(4),
                    backdropPath = r.optString("backdrop_path").takeIf { it.startsWith("/") },
                    rating = r.optDouble("vote_average", 0.0)
                )
            }
        }.orEmpty()
        val studios = (names(d.optJSONArray("networks")) + names(d.optJSONArray("production_companies"))).distinct().take(3)
        val status = when (d.optString("status")) {
            "Returning Series" -> "Returning"
            "Ended", "Canceled" -> d.optString("status")
            "In Production", "Planned", "Post Production" -> "Upcoming"
            else -> ""
        }
        val imdb = d.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") }
            ?: d.optString("imdb_id").takeIf { it.startsWith("tt") }
        // Official YouTube trailer first, then any trailer, then a teaser.
        val videos = d.optJSONObject("videos")?.optJSONArray("results")?.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("site") == "YouTube" && it.optString("key").isNotBlank() }
        }.orEmpty()
        val trailer = (videos.firstOrNull { it.optString("type") == "Trailer" && it.optBoolean("official") }
            ?: videos.firstOrNull { it.optString("type") == "Trailer" }
            ?: videos.firstOrNull { it.optString("type") == "Teaser" }
            // Some only list it as a clip named "Trailer".
            ?: videos.firstOrNull { it.optString("name").contains("trailer", true) })?.optString("key")
        return Details(mediaType, tmdbId, imdb, metadata, logo, status, studios, director, cast, recs, trailer)
            .also { cache[cacheKey] = it }
    }

    private fun names(arr: JSONArray?): List<String> =
        if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }

    private fun get(path: String, key: String): JSONObject {
        val sep = if ('?' in path) '&' else '?'
        val connection = URL("https://api.themoviedb.org/3/$path${sep}api_key=${URLEncoder.encode(key, "UTF-8")}")
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
