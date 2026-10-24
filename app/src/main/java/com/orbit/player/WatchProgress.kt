package com.orbit.player

import android.content.Context
import org.json.JSONObject

// Where the viewer got to in each movie and episode, kept on the device. Entries are keyed
// by the id Stremio addons use: "tt0111161" for a movie, "tt0903747:1:2" for an episode,
// so progress follows the title whichever stream or addon plays it.
class WatchProgress(context: Context) {

    data class Entry(
        val id: String,
        val type: String,
        // Movie title, or the show's title for an episode.
        val title: String,
        // "S1 · E2  Pilot" for episodes; empty for movies.
        val episodeLabel: String,
        val poster: String,
        val tvId: Int,
        val season: Int,
        val episode: Int,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long
    ) {
        val fraction: Float
            get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

        // Past 90% counts as watched (end credits are usually skipped).
        val watched: Boolean get() = durationMs > 0 && fraction >= 0.9f

        // Worth offering to resume: started properly and not finished.
        val resumable: Boolean get() = !watched && positionMs >= 60_000
    }

    private val prefs = context.getSharedPreferences("orbit_progress", Context.MODE_PRIVATE)

    fun get(id: String): Entry? = prefs.getString(id, null)?.let { parse(id, it) }

    fun save(entry: Entry) {
        val json = JSONObject()
            .put("type", entry.type)
            .put("title", entry.title)
            .put("episodeLabel", entry.episodeLabel)
            .put("poster", entry.poster)
            .put("tvId", entry.tvId)
            .put("season", entry.season)
            .put("episode", entry.episode)
            .put("position", entry.positionMs)
            .put("duration", entry.durationMs)
            .put("updatedAt", entry.updatedAt)
        prefs.edit().putString(entry.id, json.toString()).apply()
    }

    // Unfinished titles, most recently watched first. A show appears once, at its latest
    // episode.
    fun continueWatching(limit: Int = 20): List<Entry> =
        prefs.all.mapNotNull { (id, value) -> (value as? String)?.let { parse(id, it) } }
            .filter { it.resumable }
            .sortedByDescending { it.updatedAt }
            .distinctBy { if (it.type == "series") "show:${it.tvId}:${it.title}" else it.id }
            .take(limit)

    // Forgets a movie, or every episode of the entry's show, so nothing of it stays in
    // Continue watching.
    fun remove(entry: Entry) {
        val editor = prefs.edit()
        if (entry.type == "series") {
            prefs.all.forEach { (id, value) ->
                val e = (value as? String)?.let { parse(id, it) } ?: return@forEach
                if (e.type == "series" && e.tvId == entry.tvId && e.title == entry.title) editor.remove(id)
            }
        } else {
            editor.remove(entry.id)
        }
        editor.apply()
    }

    // Counts the entry as finished: off Continue watching, still ticked as watched.
    fun markWatched(entry: Entry) {
        val duration = entry.durationMs.takeIf { it > 0 } ?: 1L
        save(entry.copy(positionMs = duration, durationMs = duration, updatedAt = System.currentTimeMillis()))
    }

    // Progress for every episode of a show, keyed by season and episode number.
    fun forShow(tvId: Int): Map<Pair<Int, Int>, Entry> =
        prefs.all.mapNotNull { (id, value) -> (value as? String)?.let { parse(id, it) } }
            .filter { it.type == "series" && it.tvId == tvId }
            .associateBy { it.season to it.episode }

    private fun parse(id: String, raw: String): Entry? = try {
        val json = JSONObject(raw)
        Entry(
            id = id,
            type = json.optString("type"),
            title = json.optString("title"),
            episodeLabel = json.optString("episodeLabel"),
            poster = json.optString("poster"),
            tvId = json.optInt("tvId", -1),
            season = json.optInt("season", -1),
            episode = json.optInt("episode", -1),
            positionMs = json.optLong("position"),
            durationMs = json.optLong("duration"),
            updatedAt = json.optLong("updatedAt")
        )
    } catch (_: Exception) {
        null
    }

    companion object {
        fun format(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
