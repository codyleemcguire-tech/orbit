package com.orbit.player

import android.content.SharedPreferences
import android.view.Display
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Player preferences, modelled on Nuvio's playback settings (NuvioMedia/NuvioTV, GPL-3.0).
class PlayerSettings(private val prefs: SharedPreferences) {
    // "Off", "Show button" or "Skip automatically"; applies to intros and recaps.
    var skipIntro: String get() = prefs.getString("skip_intro", "Show button") ?: "Show button"
        set(v) = prefs.edit().putString("skip_intro", v).apply()
    var nextEpisodeButton: Boolean get() = prefs.getBoolean("next_episode_button", true)
        set(v) = prefs.edit().putBoolean("next_episode_button", v).apply()
    var autoplayNext: Boolean get() = prefs.getBoolean("autoplay_next", false)
        set(v) = prefs.edit().putBoolean("autoplay_next", v).apply()
    // Off by default, as in NuvioTV: switching the TV's mode takes a moment at the start.
    var matchFrameRate: Boolean get() = prefs.getBoolean("match_frame_rate", false)
        set(v) = prefs.edit().putBoolean("match_frame_rate", v).apply()
    // Tunnelled playback: the TV's hardware keeps sound and picture together (NuvioTV setting).
    var tunneling: Boolean get() = prefs.getBoolean("tunneling", false)
        set(v) = prefs.edit().putBoolean("tunneling", v).apply()
    // Auto-play (StreamPick): "Off", "First source" or "Best quality"; how long to wait for
    // addons; an optional size cap (GB, 0 = none) and addon; how long a title's last stream
    // is reused for (hours, 0 = never).
    var autoPlay: String get() = prefs.getString("auto_play", StreamPick.off) ?: StreamPick.off
        set(v) = prefs.edit().putString("auto_play", v).apply()
    var autoPlayWaitS: Int get() = prefs.getInt("auto_play_wait", 10)
        set(v) = prefs.edit().putInt("auto_play_wait", v).apply()
    var autoPlayMaxGb: Int get() = prefs.getInt("auto_play_max_gb", 0)
        set(v) = prefs.edit().putInt("auto_play_max_gb", v).apply()
    var autoPlayAddon: String get() = prefs.getString("auto_play_addon", "") ?: ""
        set(v) = prefs.edit().putString("auto_play_addon", v).apply()
    var reuseLinkHours: Int get() = prefs.getInt("reuse_link_hours", 1)
        set(v) = prefs.edit().putInt("reuse_link_hours", v).apply()
    // "Orbit", "Ask each time" or the name of an external player (see externalPlayers).
    var playWith: String get() = prefs.getString("play_with", "Orbit") ?: "Orbit"
        set(v) = prefs.edit().putString("play_with", v).apply()

    companion object {
        val skipModes = listOf("Off", "Show button", "Skip automatically")

        // Players that report back where the viewer stopped, so progress and Trakt still work.
        // MX Player's API (which Just Player shares) and VLC's.
        data class ExternalPlayer(val name: String, val packages: List<String>, val vlcApi: Boolean = false)
        val externalPlayers = listOf(
            ExternalPlayer("Just Player", listOf("com.brouken.player")),
            ExternalPlayer("VLC", listOf("org.videolan.vlc"), vlcApi = true),
            ExternalPlayer("MX Player", listOf("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad"))
        )
    }
}

// Which parts of TMDB enrichment are on (Nuvio's "TMDB Enrichment" settings).
class TmdbSettings(private val prefs: SharedPreferences) {
    var enabled: Boolean get() = prefs.getBoolean("tmdb_enrich", true)
        set(v) = prefs.edit().putBoolean("tmdb_enrich", v).apply()
    var logos: Boolean get() = prefs.getBoolean("tmdb_logos", true)
        set(v) = prefs.edit().putBoolean("tmdb_logos", v).apply()
    var moreLikeThis: Boolean get() = prefs.getBoolean("tmdb_more_like_this", true)
        set(v) = prefs.edit().putBoolean("tmdb_more_like_this", v).apply()
    var fillDiscover: Boolean get() = prefs.getBoolean("tmdb_fill_discover", true)
        set(v) = prefs.edit().putBoolean("tmdb_fill_discover", v).apply()
}

// Intro, recap and credits times from IntroDB (community-submitted, keyed by IMDb id),
// the source Nuvio uses for its Skip Intro button.
object IntroDb {
    data class Segment(val type: String, val startMs: Long, val endMs: Long)

    // id is an addon id: "tt0903747:1:2" for an episode, "tt…" for a movie.
    fun fetch(id: String): List<Segment> {
        val parts = id.split(':')
        val imdb = parts[0].takeIf { it.startsWith("tt") } ?: return emptyList()
        val query = if (parts.size >= 3) "imdb_id=$imdb&season=${parts[1]}&episode=${parts[2]}"
            else "imdb_id=$imdb&is_movie=true"
        val connection = URL("https://api.introdb.app/segments?$query").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            if (connection.responseCode != 200) return emptyList()
            val body = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            listOf("intro", "recap", "outro").mapNotNull { type ->
                val s = body.optJSONObject(type) ?: return@mapNotNull null
                val start = s.optLong("start_ms", -1).takeIf { it >= 0 }
                    ?: (s.optDouble("start_sec", -1.0) * 1000).toLong().takeIf { it >= 0 } ?: return@mapNotNull null
                val end = s.optLong("end_ms", -1).takeIf { it > 0 }
                    ?: (s.optDouble("end_sec", -1.0) * 1000).toLong().takeIf { it > 0 } ?: return@mapNotNull null
                if (end > start) Segment(type, start, end) else null
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection.disconnect()
        }
    }
}

object FrameRate {
    private val standard = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 50f, 59.94f, 60f)

    // Nearest standard rate to a measured one (within 3%), or 0 if none is close.
    fun snap(measured: Float): Float =
        standard.minByOrNull { kotlin.math.abs(it - measured) }
            ?.takeIf { kotlin.math.abs(it - measured) / it < 0.03f } ?: 0f

    // The display mode at the current resolution whose refresh rate matches the video
    // (exactly, or a whole multiple such as 50 Hz for 25 fps). Null keeps the current mode.
    fun bestMode(display: Display, fps: Float): Display.Mode? {
        if (fps <= 0f) return null
        val current = display.mode
        val candidates = display.supportedModes.filter {
            it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        fun matches(rate: Float, multiple: Int) = kotlin.math.abs(rate - fps * multiple) < 0.02f * multiple
        for (multiple in 1..3) {
            candidates.firstOrNull { matches(it.refreshRate, multiple) }?.let { return it }
        }
        return null
    }
}
