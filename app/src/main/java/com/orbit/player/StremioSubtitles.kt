package com.orbit.player

import org.json.JSONObject

// Asks the account's subtitle addons (OpenSubtitles, …) for subtitle files, the same
// request Stremio makes: /subtitles/{type}/{id}.json with the id used for streams.
object StremioSubtitles {

    // forced: a "foreign parts only" file. Addons have no flag for it, but such files are
    // named that way ("…-Forced.srt", release group "[YTS.MX].forced").
    // releaseName: the release the file was made for, used to pick one that lines up.
    data class Subtitle(
        val url: String,
        val language: String,
        val addonName: String,
        val forced: Boolean = false,
        val releaseName: String = ""
    )

    private val forcedName = Regex("(^|[^a-z])(forced|foreign[ ._-]?parts)([^a-z]|$)", RegexOption.IGNORE_CASE)

    fun fetch(type: String, id: String): List<Subtitle> {
        StremioAddons.loadManifests()
        val addons = StremioAddons.addonUrls.mapNotNull { url ->
            val manifest = try {
                StremioAddons.manifest(url)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            if (!serves(manifest, type, id)) null
            else manifest.optString("name", url) to url.removeSuffix("/manifest.json")
        }
        return addons.flatMap { (name, base) ->
            try {
                val path = "$base/subtitles/$type/${StremioAddons.encode(id).replace("%3A", ":")}.json"
                val list = JSONObject(StremioAddons.get(path, retry = false, timeoutMs = 8000))
                    .optJSONArray("subtitles") ?: return@flatMap emptyList()
                (0 until list.length()).mapNotNull { i ->
                    val s = list.optJSONObject(i) ?: return@mapNotNull null
                    val url = s.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
                    val names = listOf("subtitleFileName", "movieReleaseName", "releaseGroup", "releaseOther")
                        .joinToString(" ") { s.optString(it) }
                    val release = s.optString("subtitleFileName").ifBlank { s.optString("movieReleaseName") }
                        .ifBlank { s.optString("title") }
                    Subtitle(url, s.optString("lang"), name, forcedName.containsMatchIn(names), release)
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    // Addons can return dozens of files per language: keep the first few in the viewer's
    // languages (addons list their best matches first) and one per other language.
    // onlyPreferred keeps just the viewer's languages (Nuvio's "Show only preferred languages").
    fun pick(
        all: List<Subtitle>,
        preferred: List<String>,
        onlyPreferred: Boolean,
        perPreferred: Int = 4,
        total: Int = 12
    ): List<Subtitle> {
        val wanted = preferred.mapNotNull { SubtitleSettings.normalize(it) }
        val byLanguage = all.groupBy { SubtitleSettings.normalize(it.language) ?: it.language }
        val others = if (onlyPreferred && wanted.isNotEmpty()) emptyList()
            else byLanguage.filterKeys { it !in wanted }.values.map { it.first() }
        // Forced files in the viewer's languages are always kept, beyond the usual few.
        val forced = wanted.flatMap { byLanguage[it].orEmpty().filter { s -> s.forced } }
        val regular = wanted.flatMap { byLanguage[it].orEmpty().filter { s -> !s.forced }.take(perPreferred) }
        return (forced + regular + others).take(total)
    }

    private fun serves(manifest: JSONObject, type: String, id: String): Boolean {
        val resources = manifest.optJSONArray("resources") ?: return false
        for (i in 0 until resources.length()) {
            val r = resources.opt(i)
            val (types, prefixes) = when {
                r == "subtitles" -> manifest.optJSONArray("types") to manifest.optJSONArray("idPrefixes")
                r is JSONObject && r.optString("name") == "subtitles" ->
                    (r.optJSONArray("types") ?: manifest.optJSONArray("types")) to
                        (r.optJSONArray("idPrefixes") ?: manifest.optJSONArray("idPrefixes"))
                else -> continue
            }
            val typeOk = types == null || (0 until types.length()).any { types.optString(it) == type }
            val idOk = prefixes == null || prefixes.length() == 0 ||
                (0 until prefixes.length()).any { id.startsWith(prefixes.optString(it)) }
            if (typeOk && idOk) return true
        }
        return false
    }
}
