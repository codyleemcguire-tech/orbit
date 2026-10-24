package com.orbit.player

import android.content.SharedPreferences
import org.json.JSONObject

// Auto-play: which stream to start without the viewer choosing, after NuvioTV's
// StreamAutoPlaySelector (NuvioMedia/NuvioTV, GPL-3.0). "First source" takes the first
// playable stream in addon order, as NuvioTV's First stream mode does; "Best quality" takes
// the highest resolution, then Dolby Vision / HDR, keeping addon order between equals. Both
// skip what wouldn't play straight away: debrid links still to be downloaded, cam copies,
// and files over the size limit. Also remembers the last stream played for each title, so
// reopening it within a few hours starts the same one (NuvioTV's "reuse last link").
object StreamPick {

    const val off = "Off"
    const val first = "First source"
    const val best = "Best quality"
    val modes = listOf(off, first, best)

    private val notReady = Regex("download|\\bnot cached\\b|uncached|⬇", RegexOption.IGNORE_CASE)
    private val camCopy = Regex("\\b(HD-?CAM|CAM-?RIP|CAM|HD-?TS|TELESYNC|TS-?RIP|TELECINE|HD-?TC)\\b", RegexOption.IGNORE_CASE)
    private val sizePattern = Regex("([0-9]+(?:[.,][0-9]+)?)\\s*(TB|GB|MB)", RegexOption.IGNORE_CASE)

    // groups: each addon's streams, in the account's addon order.
    fun choose(
        groups: List<Pair<String, List<StremioStreams.Stream>>>,
        mode: String,
        maxSizeGb: Int,
        onlyAddon: String
    ): StremioStreams.Stream? {
        if (mode == off) return null
        val candidates = groups
            .filter { onlyAddon.isBlank() || it.first == onlyAddon }
            .flatMap { it.second }
            .filter { s ->
                val text = "${s.name} ${s.description}"
                s.url != null && s.youtubeId == null &&
                    !notReady.containsMatchIn(s.name) && !camCopy.containsMatchIn(text) &&
                    (maxSizeGb <= 0 || (sizeGb(text) ?: 0.0) <= maxSizeGb)
            }
        if (mode == first) return candidates.firstOrNull()
        // Stable sort: equal scores keep addon order (and each addon's own ranking).
        return candidates.withIndex().sortedWith(
            compareByDescending<IndexedValue<StremioStreams.Stream>> { resolution(it.value) }
                .thenByDescending { hdr(it.value) }
                .thenBy { it.index }
        ).firstOrNull()?.value
    }

    private fun resolution(s: StremioStreams.Stream): Int {
        val text = "${s.name} ${s.description}".lowercase()
        return when {
            "2160" in text || "4k" in text || "uhd" in text -> 4
            "1080" in text -> 3
            "720" in text -> 2
            "480" in text || "576" in text || "sd" in text.split(Regex("\\W+")) -> 1
            else -> 0
        }
    }

    private fun hdr(s: StremioStreams.Stream): Int {
        val text = "${s.name} ${s.description}"
        return when {
            Regex("dolby ?vision|\\bDV\\b|\\bDoVi\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 2
            Regex("HDR", RegexOption.IGNORE_CASE).containsMatchIn(text) -> 1
            else -> 0
        }
    }

    fun sizeGb(text: String): Double? {
        val m = sizePattern.findAll(text).lastOrNull() ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].uppercase()) {
            "TB" -> n * 1024
            "MB" -> n / 1024
            else -> n
        }
    }

    // ---- Last link ----

    fun remember(prefs: SharedPreferences, id: String, s: StremioStreams.Stream) {
        val json = JSONObject()
            .put("addon", s.addonName).put("name", s.name).put("description", s.description)
            .put("url", s.url).put("binge", s.bingeGroup).put("file", s.filename)
            .put("headers", JSONObject(s.headers as Map<*, *>))
            .put("at", System.currentTimeMillis())
        val editor = prefs.edit().putString("last_link_$id", json.toString())
        // Links older than a day are no use (debrid links expire): drop them as we go.
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("last_link_") && key != "last_link_$id") {
                val at = try { JSONObject(value as String).optLong("at") } catch (_: Exception) { 0L }
                if (System.currentTimeMillis() - at > 24 * 3_600_000L) editor.remove(key)
            }
        }
        editor.apply()
    }

    // The stream last played for id, if it was within hours.
    fun recall(prefs: SharedPreferences, id: String, hours: Int): StremioStreams.Stream? {
        if (hours <= 0) return null
        val json = prefs.getString("last_link_$id", null)?.let { JSONObject(it) } ?: return null
        if (System.currentTimeMillis() - json.optLong("at") > hours * 3_600_000L) return null
        val url = json.optString("url").takeIf { it.startsWith("http") } ?: return null
        val headers = json.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
        return StremioStreams.Stream(
            json.optString("addon"), json.optString("name"), json.optString("description"), url, null, headers,
            json.optString("binge").takeIf { it.isNotBlank() && it != "null" },
            json.optString("file").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    fun forget(prefs: SharedPreferences, id: String) {
        prefs.edit().remove("last_link_$id").apply()
    }
}
