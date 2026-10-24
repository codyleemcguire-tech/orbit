package com.orbit.player

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object TmdbTest {
    fun fetchInterstellar(apiKey: String): String {
        val encodedKey = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val url = URL(
            "https://api.themoviedb.org/3/search/movie?query=Interstellar&api_key=$encodedKey"
        )
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val status = connection.responseCode
            val stream = if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream.bufferedReader().use { it.readText() }

            if (status !in 200..299) {
                "TMDB HTTP $status"
            } else {
                val results = JSONObject(body).getJSONArray("results")
                if (results.length() == 0) {
                    "TMDB connected, but no film results returned."
                } else {
                    val film = results.getJSONObject(0)
                    "TMDB OK: ${film.optString("title")} (${film.optString("release_date")})"
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    data class CatalogueItem(
        val id: Int,
        val title: String,
        val overview: String,
        val posterPath: String?,
        val mediaType: String,
        val releaseYear: String = "",
        val backdropPath: String? = null,
        val rating: Double = 0.0
    )

    fun fetchPopularMovies(apiKey: String, page: Int = 1): List<CatalogueItem> {
        val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val url = URL(
            "https://api.themoviedb.org/3/movie/popular?api_key=$key&page=$page"
        )
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream
                         else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }

            if (status !in 200..299) {
                throw IllegalStateException("TMDB HTTP $status")
            }

            val results = JSONObject(body).getJSONArray("results")
            return (0 until results.length()).map { i ->
                val item = results.getJSONObject(i)
                CatalogueItem(
                    id = item.optInt("id"),
                    title = item.optString("title"),
                    overview = item.optString("overview"),
                    posterPath = item.optString("poster_path")
                        .takeIf { it.isNotBlank() && it != "null" },
                    mediaType = "movie",
                    releaseYear = item.optString("release_date").take(4),
                    backdropPath = item.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
                    rating = item.optDouble("vote_average", 0.0)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    fun fetchPopularTvShows(apiKey: String, page: Int = 1): List<CatalogueItem> {
        val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val url = URL("https://api.themoviedb.org/3/tv/popular?api_key=$key&page=$page")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream
                         else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }

            if (status !in 200..299) {
                throw IllegalStateException("TMDB HTTP $status")
            }

            val results = JSONObject(body).getJSONArray("results")
            return (0 until results.length()).map { i ->
                val item = results.getJSONObject(i)
                CatalogueItem(
                    id = item.optInt("id"),
                    title = item.optString("name"),
                    overview = item.optString("overview"),
                    posterPath = item.optString("poster_path")
                        .takeIf { it.isNotBlank() && it != "null" },
                    mediaType = "tv",
                    releaseYear = item.optString("first_air_date").take(4),
                    backdropPath = item.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
                    rating = item.optDouble("vote_average", 0.0)
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    data class CastMember(
        val name: String,
        val character: String,
        val profilePath: String?,
        val id: Int = 0 // TMDB person id, 0 when unknown
    )

    data class FilmMetadata(
        val title: String,
        val releaseYear: String,
        val genres: List<String>,
        val runtimeMinutes: Int?,
        val overview: String,
        val mediaType: String,
        val tmdbId: Int,
        val backdropPath: String? = null,
        val rating: Double = 0.0,
        val tagline: String = "",
        val seasonCount: Int = 0
    )

    fun fetchMetadata(title: String, apiKey: String): FilmMetadata? {
        fun getJson(path: String): JSONObject {
            val url = URL("https://api.themoviedb.org/3/$path")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream.bufferedReader().use { it.readText() }
                if (status !in 200..299) {
                    throw IllegalStateException("TMDB HTTP $status")
                }
                return JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }

        val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val search = getJson("search/multi?query=$encodedTitle&api_key=$key")
        val results = search.getJSONArray("results")
        if (results.length() == 0) return null

        val candidates = (0 until results.length())
            .map { results.getJSONObject(it) }
            .filter {
                it.optString("media_type") == "movie" ||
                    it.optString("media_type") == "tv"
            }

        val wantedTitle = title.trim().lowercase()
        val match = candidates.firstOrNull {
            it.optString("title").ifBlank { it.optString("name") }
                .trim().lowercase() == wantedTitle
        } ?: candidates.firstOrNull {
            val resultTitle = it.optString("title").ifBlank { it.optString("name") }
            resultTitle.contains(title, ignoreCase = true) ||
                title.contains(resultTitle, ignoreCase = true)
        } ?: return null

        val mediaType = match.optString("media_type")
        val id = match.getInt("id")
        val details = getJson("$mediaType/$id?api_key=$key")

        val date = details.optString("release_date").ifBlank {
            details.optString("first_air_date")
        }
        val year = date.takeIf { it.length >= 4 }?.take(4).orEmpty()

        val genresJson = details.optJSONArray("genres")
        val genres = if (genresJson == null) {
            emptyList()
        } else {
            (0 until genresJson.length()).mapNotNull { i ->
                genresJson.optJSONObject(i)?.optString("name")
                    ?.takeIf { it.isNotBlank() }
            }
        }

        val runtime = if (mediaType == "movie") {
            details.optInt("runtime", 0).takeIf { it > 0 }
        } else {
            val runtimes = details.optJSONArray("episode_run_time")
            runtimes?.optInt(0, 0)?.takeIf { it > 0 }
        }

        return FilmMetadata(
            title = details.optString("title").ifBlank {
                details.optString("name", title)
            },
            releaseYear = year,
            genres = genres,
            runtimeMinutes = runtime,
            overview = details.optString("overview"),
            mediaType = mediaType,
            tmdbId = id,
            backdropPath = details.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" },
            rating = details.optDouble("vote_average", 0.0),
            tagline = details.optString("tagline"),
            seasonCount = details.optInt("number_of_seasons", 0)
        )
    }

    fun fetchCast(title: String, apiKey: String): List<CastMember> {
        fun getJson(path: String): JSONObject {
            val url = URL("https://api.themoviedb.org/3/$path")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream.bufferedReader().use { it.readText() }
                if (status !in 200..299) {
                    throw IllegalStateException("TMDB HTTP $status")
                }
                return JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }

        val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val search = getJson("search/multi?query=$encodedTitle&api_key=$key")
        val results = search.getJSONArray("results")
        if (results.length() == 0) return emptyList()

        val candidates = (0 until results.length())
            .map { results.getJSONObject(it) }
            .filter {
                it.optString("media_type") == "movie" ||
                    it.optString("media_type") == "tv"
            }

        val wantedTitle = title.trim().lowercase()

        val match = candidates.firstOrNull {
            val resultTitle = (
                it.optString("title").ifBlank {
                    it.optString("name")
                }
            ).trim().lowercase()

            resultTitle == wantedTitle
        } ?: candidates.firstOrNull {
            val resultTitle = (
                it.optString("title").ifBlank {
                    it.optString("name")
                }
            ).trim().lowercase()

            resultTitle.contains(wantedTitle) ||
                wantedTitle.contains(resultTitle)
        } ?: return emptyList()

        val mediaType = match.optString("media_type")
        val id = match.getInt("id")
        val credits = getJson("$mediaType/$id/credits?api_key=$key")
        val cast = credits.getJSONArray("cast")

        return (0 until minOf(cast.length(), 20)).map { i ->
            val actor = cast.getJSONObject(i)
            CastMember(
                name = actor.optString("name"),
                character = actor.optString("character"),
                profilePath = actor.optString("profile_path").takeIf {
                    it.isNotBlank() && it != "null"
                },
                id = actor.optInt("id")
            )
        }
    }

    fun fetchActorProfile(actorName: String, apiKey: String): Pair<String, String> {
        fun getJson(path: String): JSONObject {
            val url = URL("https://api.themoviedb.org/3/$path")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val status = connection.responseCode
                val stream = if (status in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream.bufferedReader().use { it.readText() }
                if (status !in 200..299) {
                    throw IllegalStateException("TMDB HTTP $status")
                }
                return JSONObject(body)
            } finally {
                connection.disconnect()
            }
        }

        val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val query = java.net.URLEncoder.encode(actorName, "UTF-8")
        val search = getJson("search/person?query=$query&api_key=$key")
        val results = search.getJSONArray("results")
        if (results.length() == 0) return Pair("", "")

        val person = (0 until results.length())
            .map { results.getJSONObject(it) }
            .firstOrNull {
                it.optString("name").equals(actorName, ignoreCase = true)
            } ?: results.getJSONObject(0)

        val personId = person.getInt("id")
        val details = getJson("person/$personId?api_key=$key")
        val credits = getJson("person/$personId/combined_credits?api_key=$key")
        val castCredits = credits.optJSONArray("cast")

        val titles = if (castCredits == null) {
            emptyList()
        } else {
            (0 until castCredits.length())
                .map { castCredits.getJSONObject(it) }
                .sortedByDescending {
                    it.optString("release_date").ifBlank {
                        it.optString("first_air_date")
                    }
                }
                .distinctBy {
                    it.optString("title").ifBlank { it.optString("name") }
                }
                .take(8)
                .map {
                    val title = it.optString("title").ifBlank {
                        it.optString("name")
                    }
                    val date = it.optString("release_date").ifBlank {
                        it.optString("first_air_date")
                    }
                    val year = date.takeIf { d -> d.length >= 4 }?.take(4).orEmpty()
                    if (year.isBlank()) title else "$title ($year)"
                }
        }

        return Pair(
            details.optString("biography"),
            titles.joinToString("\n")
        )
    }



    data class TvSeason(
        val seasonNumber: Int,
        val name: String,
        val episodeCount: Int
    )

    data class TvEpisode(
        val episodeNumber: Int,
        val name: String,
        val overview: String,
        val runtimeMinutes: Int?,
        val stillPath: String? = null,
        val airDate: String = ""
    )

    // Stream addons look titles up by IMDb id. mediaType is "movie" or "tv".
    fun fetchImdbId(mediaType: String, tmdbId: Int, apiKey: String): String? {
        val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val url = URL("https://api.themoviedb.org/3/$mediaType/$tmdbId/external_ids?api_key=$key")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream
                         else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) throw IllegalStateException("TMDB HTTP $status")
            return JSONObject(body).optString("imdb_id").takeIf { it.startsWith("tt") }
        } finally {
            connection.disconnect()
        }
    }

    // Addon catalogs identify shows by IMDb id (tt…); the TV page needs TMDB's id.
    fun findTvIdByImdb(imdbId: String, apiKey: String): Int? {
        val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val url = URL(
            "https://api.themoviedb.org/3/find/$imdbId?api_key=$key&external_source=imdb_id"
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream
                         else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) throw IllegalStateException("TMDB HTTP $status")

            val results = JSONObject(body).optJSONArray("tv_results") ?: return null
            return results.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
        } finally {
            connection.disconnect()
        }
    }

    fun fetchTvSeasons(tvId: Int, apiKey: String): List<TvSeason> {
        val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val url = URL("https://api.themoviedb.org/3/tv/$tvId?api_key=$key")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream
                         else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) throw IllegalStateException("TMDB HTTP $status")

            val seasons = JSONObject(body).optJSONArray("seasons") ?: return emptyList()
            return (0 until seasons.length()).mapNotNull { i ->
                val item = seasons.optJSONObject(i) ?: return@mapNotNull null
                TvSeason(
                    seasonNumber = item.optInt("season_number", -1),
                    name = item.optString("name", "Season"),
                    episodeCount = item.optInt("episode_count", 0)
                )
            }.filter { it.seasonNumber >= 0 && it.episodeCount > 0 }
        } finally {
            connection.disconnect()
        }
    }

    fun fetchTvEpisodes(
        tvId: Int,
        seasonNumber: Int,
        apiKey: String
    ): List<TvEpisode> {
        val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
        val url = URL(
            "https://api.themoviedb.org/3/tv/$tvId/season/$seasonNumber?api_key=$key"
        )
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream
                         else connection.errorStream
            val body = stream.bufferedReader().use { it.readText() }
            if (status !in 200..299) throw IllegalStateException("TMDB HTTP $status")

            val episodes = JSONObject(body).optJSONArray("episodes") ?: return emptyList()
            return (0 until episodes.length()).mapNotNull { i ->
                val item = episodes.optJSONObject(i) ?: return@mapNotNull null
                TvEpisode(
                    episodeNumber = item.optInt("episode_number", 0),
                    name = item.optString("name", "Episode"),
                    overview = item.optString("overview"),
                    runtimeMinutes = item.optInt("runtime", 0).takeIf { it > 0 },
                    stillPath = item.optString("still_path").takeIf { it.isNotBlank() && it != "null" },
                    airDate = item.optString("air_date")
                )
            }
        } finally {
            connection.disconnect()
        }
    }

}
