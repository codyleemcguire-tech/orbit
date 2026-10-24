package com.orbit.player

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// My List and watch progress, kept in step with the Stremio account's library, the same
// store Stremio itself uses. A library item is a title (IMDb id); "removed: false,
// temp: false" puts it in the library (My List), and its state holds where the viewer got
// to (timeOffset in ms, and which episode as video_id). Without a Stremio sign-in, My List
// and progress stay on the device.
class StremioLibrary(
    private val prefs: SharedPreferences,
    private val progress: WatchProgress,
    private val tmdbKey: String
) {

    data class ListItem(
        val id: String,       // IMDb id
        val type: String,     // "movie" or "series"
        val name: String,
        val poster: String,   // full image URL
        val background: String,
        val tmdbId: Int,      // 0 when unknown
        val addedAt: Long
    )

    private val authKey get() = prefs.getString("stremio_auth_key", null)
    val signedIn get() = authKey != null

    // Latest copy of each library item, by IMDb id, so changes keep Stremio's other fields.
    private val remote = ConcurrentHashMap<String, JSONObject>()
    private val writer = Executors.newSingleThreadExecutor()
    private val lastPush = ConcurrentHashMap<String, Long>()
    @Volatile var lastPull = 0L
        private set

    // ---- My List ----

    fun myList(): List<ListItem> = try {
        val arr = JSONArray(prefs.getString("my_list", "[]"))
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            ListItem(it.getString("id"), it.optString("type", "movie"), it.optString("name"),
                it.optString("poster"), it.optString("background"), it.optInt("tmdbId"), it.optLong("addedAt"))
        }.sortedByDescending { it.addedAt }
    } catch (_: Exception) {
        emptyList()
    }

    fun inList(imdbId: String?) = imdbId != null && myList().any { it.id == imdbId }

    private fun saveList(items: List<ListItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster)
                .put("background", it.background).put("tmdbId", it.tmdbId).put("addedAt", it.addedAt))
        }
        prefs.edit().putString("my_list", arr.toString()).apply()
    }

    // Adds or removes a title; returns whether it's now in the list.
    fun toggle(imdbId: String, type: String, name: String, poster: String, tmdbId: Int): Boolean {
        val list = myList()
        val adding = list.none { it.id == imdbId }
        val now = System.currentTimeMillis()
        saveList(if (adding) list + ListItem(imdbId, type, name, imageUrl(poster), "", tmdbId, now)
            else list.filter { it.id != imdbId })
        if (signedIn) {
            val item = itemFor(imdbId, type, name, poster)
            item.put("removed", !adding)
            // Taken out of the library but part-watched: stays in Continue watching, as Stremio does.
            item.put("temp", !adding && (item.optJSONObject("state")?.optLong("timeOffset") ?: 0L) > 0)
            item.put("_mtime", iso(now))
            write(item)
        }
        return adding
    }

    // ---- Progress ----

    // Sends where the viewer is. Called often while playing; sends at most once a minute
    // per title unless forced (leaving the player, finishing).
    fun pushProgress(entry: WatchProgress.Entry, force: Boolean = false) {
        if (!signedIn || !entry.id.startsWith("tt")) return
        val imdbId = entry.id.substringBefore(':')
        val now = System.currentTimeMillis()
        if (!force && now - (lastPush[imdbId] ?: 0L) < 60_000) return
        lastPush[imdbId] = now
        val item = itemFor(imdbId, entry.type, entry.title, entry.poster)
        val state = item.optJSONObject("state") ?: JSONObject().also { item.put("state", it) }
        val finished = entry.watched
        state.put("timeOffset", if (finished) 0 else entry.positionMs)
        state.put("duration", entry.durationMs)
        state.put("video_id", entry.id)
        state.put("lastWatched", iso(now))
        if (finished) {
            state.put("timesWatched", state.optInt("timesWatched") + 1)
            if (entry.type == "movie") state.put("flaggedWatched", 1)
        }
        // A title only watched (not added) is a temporary item, shown in Continue watching.
        if (item.optBoolean("removed", true)) item.put("temp", true)
        item.put("_mtime", iso(now))
        write(item)
    }

    // Taken off Continue watching on this device: the same on Stremio.
    fun dismiss(entry: WatchProgress.Entry) {
        if (!signedIn || !entry.id.startsWith("tt")) return
        val item = remote[entry.id.substringBefore(':')] ?: return
        item.optJSONObject("state")?.put("timeOffset", 0)
        item.put("_mtime", iso(System.currentTimeMillis()))
        write(item)
    }

    // ---- Pull ----

    // Reads the account's library and brings My List and progress on this device up to
    // date. Returns true when anything shown on Home changed. Call off the main thread.
    fun pull(): Boolean {
        val key = authKey ?: return false
        val items = StremioAccount.callAny("datastoreGet", JSONObject()
            .put("authKey", key).put("collection", "libraryItem").put("ids", JSONArray()).put("all", true)) as? JSONArray
            ?: return false
        lastPull = System.currentTimeMillis()
        var changed = progress.pruneUnusable() > 0
        val all = (0 until items.length()).mapNotNull { items.optJSONObject(it) }
            .filter { it.optString("_id").startsWith("tt") }
        all.forEach { remote[it.getString("_id")] = it }

        // My List: the library's titles, in the order they were added.
        val known = myList().associateBy { it.id }
        val list = all.filter { !it.optBoolean("removed") && !it.optBoolean("temp") }.map { item ->
            val id = item.getString("_id")
            val type = if (item.optString("type") == "series") "series" else "movie"
            ListItem(
                id, type, item.optString("name"), item.optString("poster"), item.optString("background"),
                known[id]?.tmdbId?.takeIf { it > 0 } ?: tmdbIdFor(id, type),
                known[id]?.addedAt ?: millis(item.optString("_ctime"))
            )
        }
        if (list.map { it.id }.toSet() != known.keys) changed = true
        saveList(list)

        // Progress: take the account's where it's newer than this device's.
        all.forEach { item ->
            val state = item.optJSONObject("state") ?: return@forEach
            val type = if (item.optString("type") == "series") "series" else "movie"
            val videoId = state.optString("video_id").ifBlank { item.getString("_id") }
            if (!videoId.startsWith("tt")) return@forEach
            val offset = state.optLong("timeOffset")
            val duration = state.optLong("duration")
            val watchedAt = millis(state.optString("lastWatched")).takeIf { it > 0 } ?: millis(item.optString("_mtime"))
            val local = progress.get(videoId)
            val inContinue = offset > 0 && (!item.optBoolean("removed") || item.optBoolean("temp"))
            // Stremio sometimes records a show without its episode, or an impossible length;
            // neither can be resumed.
            val usable = duration in 1..WatchProgress.maxDurationMs && (type == "movie" || videoId.count { it == ':' } == 2)
            if (inContinue && usable) {
                if (local != null && local.updatedAt >= watchedAt - 2000) return@forEach
                val parts = videoId.split(':')
                val season = parts.getOrNull(1)?.toIntOrNull() ?: -1
                val episode = parts.getOrNull(2)?.toIntOrNull() ?: -1
                val tvId = if (type == "series") local?.tvId?.takeIf { it > 0 } ?: progress.tvIdFor(parts[0])
                    ?: tmdbIdFor(parts[0], "series") else -1
                if (type == "series" && tvId <= 0) return@forEach
                progress.save(WatchProgress.Entry(
                    videoId, type, item.optString("name"),
                    local?.episodeLabel?.takeIf { it.isNotBlank() } ?: if (season >= 0) "S$season · E$episode" else "",
                    local?.poster?.takeIf { it.isNotBlank() } ?: item.optString("poster"),
                    tvId, season, episode, offset, duration, watchedAt
                ))
                changed = true
            } else if (inContinue) {
                return@forEach
            } else if (local != null && local.resumable && millis(item.optString("_mtime")) > local.updatedAt + 2000) {
                // Finished or dismissed on another device since.
                if (state.optInt("timesWatched") > 0 || state.optInt("flaggedWatched") > 0) progress.markWatched(local)
                else progress.removeId(videoId)
                changed = true
            }
        }
        return changed
    }

    // ---- Helpers ----

    private fun itemFor(imdbId: String, type: String, name: String, poster: String): JSONObject =
        remote[imdbId] ?: JSONObject()
            .put("_id", imdbId)
            .put("name", name)
            .put("type", if (type == "series" || type == "tv") "series" else "movie")
            .put("poster", imageUrl(poster))
            .put("posterShape", "poster")
            .put("removed", true)
            .put("temp", true)
            .put("_ctime", iso(System.currentTimeMillis()))
            .put("state", JSONObject()
                .put("lastWatched", JSONObject.NULL).put("timeWatched", 0).put("timeOffset", 0)
                .put("overallTimeWatched", 0).put("timesWatched", 0).put("flaggedWatched", 0)
                .put("duration", 0).put("video_id", JSONObject.NULL).put("watched", "")
                .put("noNotif", false).put("season", 0).put("episode", 0))
            .also { remote[imdbId] = it }

    private fun write(item: JSONObject) {
        val key = authKey ?: return
        val copy = JSONObject(item.toString())
        writer.execute {
            try {
                StremioAccount.callAny("datastorePut", JSONObject()
                    .put("authKey", key).put("collection", "libraryItem").put("changes", JSONArray().put(copy)))
            } catch (_: Exception) {
                // Offline: the next change, or the next pull, catches up.
            }
        }
    }

    private fun tmdbIdFor(imdbId: String, type: String): Int = try {
        TmdbMeta.resolve(imdbId, type, tmdbKey)?.second ?: 0
    } catch (_: Exception) {
        0
    }

    companion object {
        fun imageUrl(path: String) =
            if (path.startsWith("/")) "https://image.tmdb.org/t/p/w342$path" else path

        fun iso(ms: Long): String = Instant.ofEpochMilli(ms).toString()

        fun millis(iso: String): Long = try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
