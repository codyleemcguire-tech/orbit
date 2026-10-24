package com.orbit.player

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

// Trakt: signs in with a code shown on the TV (no password typed on the remote), tells
// Trakt what's playing so it's marked watched when finished, and keeps the Trakt watchlist
// in step with My List and Trakt's paused titles with Continue watching. Orbit's Trakt app
// has no client secret (Trakt's PKCE apps), so only the client id is built in.
class Trakt(
    private val prefs: SharedPreferences,
    private val progress: WatchProgress,
    private val library: StremioLibrary,
    private val tmdbKey: String
) {

    data class DeviceCode(val userCode: String, val url: String, val deviceCode: String, val intervalS: Int, val expiresInS: Int)

    // What a scrobble is about: a movie, or one episode of a show.
    data class Item(val imdbId: String, val type: String, val season: Int, val episode: Int) {
        companion object {
            // From an addon id: "tt0111161" or "tt0903747:1:2".
            fun from(id: String, type: String): Item? {
                if (!id.startsWith("tt")) return null
                val parts = id.split(':')
                return if (type == "series" && parts.size == 3) {
                    Item(parts[0], "series", parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
                } else if (type == "movie") Item(parts[0], "movie", -1, -1) else null
            }
        }
    }

    val available get() = clientId.isNotBlank()
    val signedIn get() = prefs.getString("trakt_refresh", null) != null
    val user: String? get() = prefs.getString("trakt_user", null)

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile var lastPull = 0L
        private set

    // ---- Signing in ----

    // Step 1: a code for the viewer to enter at trakt.tv/activate. Off the main thread.
    fun startSignIn(): DeviceCode {
        val r = request("POST", "oauth/device/code", JSONObject().put("client_id", clientId), auth = false) as JSONObject
        return DeviceCode(r.getString("user_code"), r.optString("verification_url", "https://trakt.tv/activate"),
            r.getString("device_code"), r.optInt("interval", 5), r.optInt("expires_in", 600))
    }

    // Step 2, called every few seconds: true once the viewer has approved, false while
    // waiting. Throws when the code expired or was refused.
    fun finishSignIn(code: DeviceCode): Boolean {
        val connection = open("POST", "oauth/device/token", auth = false)
        try {
            send(connection, JSONObject().put("code", code.deviceCode).put("client_id", clientId))
            when (val status = connection.responseCode) {
                200 -> {
                    saveTokens(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
                    val me = try {
                        (request("GET", "users/settings") as JSONObject).optJSONObject("user")
                    } catch (_: Exception) {
                        null
                    }
                    prefs.edit().putString("trakt_user", me?.optString("username")?.ifBlank { null } ?: "Trakt").commit()
                    return true
                }
                400 -> return false // not approved yet
                429 -> return false // asked too quickly; the next poll is slower
                404 -> throw IllegalStateException("Trakt didn't recognise the code.")
                409 -> throw IllegalStateException("That code was already used.")
                410 -> throw IllegalStateException("The code expired. Try again.")
                418 -> throw IllegalStateException("Sign-in was declined on Trakt.")
                else -> throw IllegalStateException("Trakt answered $status.")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun signOut() {
        val token = prefs.getString("trakt_access", null)
        prefs.edit().remove("trakt_access").remove("trakt_refresh").remove("trakt_expires")
            .remove("trakt_user").remove("trakt_watchlist_seen").apply()
        if (token != null) writer.execute {
            try {
                request("POST", "oauth/revoke", JSONObject().put("token", token).put("client_id", clientId), auth = false)
            } catch (_: Exception) {
            }
        }
    }

    private fun saveTokens(r: JSONObject) {
        val created = r.optLong("created_at", System.currentTimeMillis() / 1000)
        prefs.edit()
            .putString("trakt_access", r.getString("access_token"))
            .putString("trakt_refresh", r.getString("refresh_token"))
            .putLong("trakt_expires", (created + r.optLong("expires_in", 86400)) * 1000)
            .commit()
    }

    // A fresh access token, renewing it when it's about to run out.
    @Synchronized
    private fun accessToken(): String {
        val refresh = prefs.getString("trakt_refresh", null) ?: throw IllegalStateException("Not signed in to Trakt")
        val access = prefs.getString("trakt_access", null)
        if (access != null && System.currentTimeMillis() < prefs.getLong("trakt_expires", 0) - 3_600_000) return access
        val connection = open("POST", "oauth/token", auth = false)
        try {
            send(connection, JSONObject()
                .put("refresh_token", refresh).put("client_id", clientId)
                .put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob").put("grant_type", "refresh_token"))
            val status = connection.responseCode
            if (status == 400 || status == 401) {
                // The sign-in was revoked on trakt.tv: sign out here too.
                prefs.edit().remove("trakt_access").remove("trakt_refresh").remove("trakt_expires").remove("trakt_user").apply()
                throw IllegalStateException("Signed out of Trakt")
            }
            if (status !in 200..299) throw IllegalStateException("Trakt HTTP $status")
            saveTokens(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }))
        } finally {
            connection.disconnect()
        }
        return prefs.getString("trakt_access", null)!!
    }

    // ---- Scrobbling ----

    // action is "start" (playing), "pause" or "stop" (left the player or finished). Trakt
    // marks the title watched on a stop at 80% or more, and otherwise keeps it as paused.
    fun scrobble(action: String, item: Item, percent: Float) {
        if (!signedIn) return
        val body = JSONObject().put("progress", percent.coerceIn(0f, 100f).toDouble())
        if (item.type == "movie") body.put("movie", ids(item.imdbId))
        else body.put("show", ids(item.imdbId)).put("episode", JSONObject().put("season", item.season).put("number", item.episode))
        writer.execute {
            try {
                request("POST", "scrobble/$action", body)
            } catch (_: Exception) {
                // 409: already scrobbled a moment ago. Offline: nothing to do.
            }
        }
    }

    // Marked watched from Continue watching.
    fun markWatched(item: Item) {
        if (!signedIn) return
        val body = if (item.type == "movie") JSONObject().put("movies", JSONArray().put(ids(item.imdbId)))
        else JSONObject().put("shows", JSONArray().put(ids(item.imdbId).put("seasons", JSONArray().put(
            JSONObject().put("number", item.season).put("episodes", JSONArray().put(JSONObject().put("number", item.episode)))))))
        writer.execute {
            try {
                request("POST", "sync/history", body)
            } catch (_: Exception) {
            }
        }
    }

    // Removed from Continue watching: drop Trakt's paused entry for it too.
    fun dismiss(item: Item) {
        if (!signedIn) return
        writer.execute {
            try {
                val arr = request("GET", "sync/playback") as JSONArray
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { row ->
                    val isMovie = row.optString("type") == "movie"
                    val media = row.optJSONObject(if (isMovie) "movie" else "show")
                    media?.optJSONObject("ids")?.optString("imdb") == item.imdbId && (isMovie || item.type == "series")
                }.forEach { request("DELETE", "sync/playback/${it.optLong("id")}") }
            } catch (_: Exception) {
            }
        }
    }

    // ---- Watchlist ----

    fun setInWatchlist(imdbId: String, type: String, inList: Boolean) {
        if (!signedIn || !imdbId.startsWith("tt")) return
        val key = if (type == "series" || type == "tv") "shows" else "movies"
        val body = JSONObject().put(key, JSONArray().put(ids(imdbId)))
        writer.execute {
            try {
                request("POST", if (inList) "sync/watchlist" else "sync/watchlist/remove", body)
                val seen = seenOnTrakt().toMutableSet()
                if (inList) seen += imdbId else seen -= imdbId
                prefs.edit().putStringSet("trakt_watchlist_seen", seen).apply()
            } catch (_: Exception) {
            }
        }
    }

    private fun seenOnTrakt(): Set<String> = prefs.getStringSet("trakt_watchlist_seen", null) ?: emptySet()

    // ---- Pull ----

    // Brings the Trakt watchlist into My List (and My List up to Trakt the first time),
    // and Trakt's paused titles into Continue watching. Returns true when Home changed.
    // Call off the main thread, after the Stremio pull so both end up the same.
    fun pull(): Boolean {
        if (!signedIn) return false
        var changed = false
        val firstSync = !prefs.contains("trakt_watchlist_seen")

        val watchlist = (listOf("movies", "shows").flatMap { kind ->
            val arr = request("GET", "sync/watchlist/$kind") as JSONArray
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { row ->
                val media = row.optJSONObject(if (kind == "movies") "movie" else "show") ?: return@mapNotNull null
                val idsObj = media.optJSONObject("ids") ?: return@mapNotNull null
                val imdb = idsObj.optString("imdb").takeIf { it.startsWith("tt") } ?: return@mapNotNull null
                Triple(imdb, if (kind == "movies") "movie" else "series", media)
            }
        })
        val onTrakt = watchlist.map { it.first }.toSet()
        val local = library.myList()
        val localIds = local.map { it.id }.toSet()
        val seen = seenOnTrakt()

        // Added on Trakt since: into My List (and so Stremio's library too).
        watchlist.filter { it.first !in localIds }.forEach { (imdb, type, media) ->
            if (!firstSync && imdb in seen) return@forEach // removed in Orbit; the removal is on its way
            val tmdbId = media.optJSONObject("ids")?.optInt("tmdb") ?: 0
            val poster = tmdbPoster(type, tmdbId)?.first.orEmpty()
            library.toggle(imdb, type, media.optString("title"), poster, tmdbId)
            changed = true
        }
        // Removed on Trakt since Orbit last saw it there: out of My List.
        if (!firstSync) local.filter { it.id in seen && it.id !in onTrakt }.forEach {
            library.toggle(it.id, it.type, it.name, it.poster, it.tmdbId)
            changed = true
        }
        // First sync: whatever is only in My List goes up to Trakt.
        if (firstSync) {
            val up = local.filter { it.id !in onTrakt }
            val movies = JSONArray(up.filter { it.type == "movie" }.map { ids(it.id) })
            val shows = JSONArray(up.filter { it.type != "movie" }.map { ids(it.id) })
            if (up.isNotEmpty()) request("POST", "sync/watchlist", JSONObject().put("movies", movies).put("shows", shows))
        }
        prefs.edit().putStringSet("trakt_watchlist_seen", library.myList().map { it.id }.toSet()).apply()

        // Paused titles: newer than this device's progress goes into Continue watching.
        val playback = request("GET", "sync/playback?extended=full") as JSONArray
        (0 until playback.length()).mapNotNull { playback.optJSONObject(it) }.forEach { row ->
            val isMovie = row.optString("type") == "movie"
            val media = row.optJSONObject(if (isMovie) "movie" else "show") ?: return@forEach
            val imdb = media.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") } ?: return@forEach
            val tmdbId = media.optJSONObject("ids")?.optInt("tmdb") ?: 0
            val episode = row.optJSONObject("episode")
            if (!isMovie && episode == null) return@forEach
            val season = episode?.optInt("season") ?: -1
            val number = episode?.optInt("number") ?: -1
            val id = if (isMovie) imdb else "$imdb:$season:$number"
            val pausedAt = StremioLibrary.millis(row.optString("paused_at"))
            val localEntry = progress.get(id)
            if (localEntry != null && localEntry.updatedAt >= pausedAt - 2000) return@forEach
            val type = if (isMovie) "movie" else "series"
            if (!isMovie && tmdbId <= 0) return@forEach
            val tmdb = tmdbPoster(type, tmdbId)
            val runtimeMin = (if (isMovie) media.optInt("runtime") else episode?.optInt("runtime") ?: 0)
                .takeIf { it > 0 } ?: tmdb?.second ?: return@forEach
            val duration = runtimeMin * 60_000L
            val position = (row.optDouble("progress", 0.0) / 100.0 * duration).toLong()
            if (position < 60_000) return@forEach
            val title = episode?.optString("title")?.takeIf { it.isNotBlank() && it != "null" }
            progress.save(WatchProgress.Entry(
                id, type, media.optString("title"),
                localEntry?.episodeLabel?.takeIf { it.isNotBlank() }
                    ?: if (isMovie) "" else "S$season · E$number" + (title?.let { "  $it" } ?: ""),
                localEntry?.poster?.takeIf { it.isNotBlank() } ?: tmdb?.first.orEmpty(),
                if (isMovie) -1 else tmdbId, season, number, position, duration, pausedAt
            ))
            changed = true
        }
        lastPull = System.currentTimeMillis()
        return changed
    }

    // TMDB poster path and runtime (minutes; a show's usual episode length).
    private fun tmdbPoster(type: String, tmdbId: Int): Pair<String, Int?>? {
        if (tmdbId <= 0 || tmdbKey.isBlank()) return null
        return try {
            val kind = if (type == "movie") "movie" else "tv"
            val connection = URL("https://api.themoviedb.org/3/$kind/$tmdbId?api_key=${URLEncoder.encode(tmdbKey, "UTF-8")}")
                .openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 10000
                connection.readTimeout = 12000
                if (connection.responseCode !in 200..299) return null
                val r = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val runtime = if (kind == "movie") r.optInt("runtime") else r.optJSONArray("episode_run_time")?.optInt(0) ?: 0
                r.optString("poster_path") to runtime.takeIf { it > 0 }
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- HTTP ----

    private fun ids(imdbId: String) = JSONObject().put("ids", JSONObject().put("imdb", imdbId))

    private fun open(method: String, path: String, auth: Boolean): HttpURLConnection {
        val connection = URL("https://api.trakt.tv/$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", "Orbit/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty("trakt-api-version", "2")
        connection.setRequestProperty("trakt-api-key", clientId)
        if (auth) connection.setRequestProperty("Authorization", "Bearer ${accessToken()}")
        return connection
    }

    private fun send(connection: HttpURLConnection, body: JSONObject) {
        connection.doOutput = true
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
    }

    // The parsed answer: a JSONObject or JSONArray (an empty object for "no content").
    private fun request(method: String, path: String, body: JSONObject? = null, auth: Boolean = true): Any {
        val connection = open(method, path, auth)
        try {
            body?.let { send(connection, it) }
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("Trakt HTTP $status")
            val text = connection.inputStream.bufferedReader().use { it.readText() }.trim()
            return when {
                text.startsWith("[") -> JSONArray(text)
                text.startsWith("{") -> JSONObject(text)
                else -> JSONObject()
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val clientId: String = BuildConfig.TRAKT_CLIENT_ID
    }
}
