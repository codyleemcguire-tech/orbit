package com.orbit.player

import org.json.JSONArray
import org.json.JSONObject

// Asks the signed-in account's stream addons (Torrentio, Comet, …) for playable links,
// the same request Stremio makes: /stream/{type}/{id}.json, where id is an IMDb id
// ("tt0111161") or, for an episode, "tt0903747:1:2".
object StremioStreams {

    data class Addon(val name: String, val baseUrl: String)

    data class Stream(
        val addonName: String,
        // Short label, usually quality ("[TB+] Torrentio 1080p DV | HDR").
        val name: String,
        // File name, size, source and so on, one detail per line.
        val description: String,
        val url: String?,
        // Trailer addons answer with a YouTube id instead of a link.
        val youtubeId: String?,
        val headers: Map<String, String>,
        // Streams from one release share this, so the next episode can use the same one.
        val bingeGroup: String? = null,
        // The video's file name, used to find subtitles made for the same release.
        val filename: String? = null,
        // A torrent (no url): played through TorBox when it's cached there (see TorBox).
        val infoHash: String? = null,
        val fileIdx: Int? = null,
        val trackers: List<String> = emptyList()
    )

    // Addons whose manifest offers streams for this type and id.
    fun addonsFor(type: String, id: String): List<Addon> {
        StremioAddons.loadManifests()
        val seen = mutableMapOf<String, Int>()
        return StremioAddons.addonUrls.mapNotNull { manifestUrl ->
            try {
                val manifest = StremioAddons.manifest(manifestUrl)
                if (servesStreams(manifest, type, id)) {
                    Addon(
                        manifest.optString("name", manifestUrl),
                        manifestUrl.removeSuffix("/manifest.json")
                    )
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }
        }.map { addon ->
            // The sources page groups streams by addon name, so two addons with one name
            // (two Torrentio set-ups, say) are numbered rather than merged.
            val n = seen.merge(addon.name, 1, Int::plus) ?: 1
            if (n == 1) addon else addon.copy(name = "${addon.name} ($n)")
        }
    }

    private fun servesStreams(manifest: JSONObject, type: String, id: String): Boolean {
        val resources = manifest.optJSONArray("resources") ?: return false
        for (i in 0 until resources.length()) {
            val resource = resources.opt(i)
            // A resource is either "stream" (using the manifest's types and prefixes)
            // or an object naming its own.
            val (types, prefixes) = when {
                resource == "stream" ->
                    strings(manifest.optJSONArray("types")) to
                        strings(manifest.optJSONArray("idPrefixes"))
                resource is JSONObject && resource.optString("name") == "stream" ->
                    strings(resource.optJSONArray("types") ?: manifest.optJSONArray("types")) to
                        strings(resource.optJSONArray("idPrefixes") ?: manifest.optJSONArray("idPrefixes"))
                else -> continue
            }
            val typeOk = types.isEmpty() || type in types
            val idOk = prefixes.isEmpty() || prefixes.any { id.startsWith(it) }
            if (typeOk && idOk) return true
        }
        return false
    }

    fun fetchStreams(addon: Addon, type: String, id: String): List<Stream> {
        // Ids keep their colons ("tt0903747:1:2"); that's what addons expect.
        val path = "${addon.baseUrl}/stream/$type/${StremioAddons.encode(id).replace("%3A", ":")}.json"
        // One try, at most 10 s: Orbit lists the other addons' streams meanwhile.
        val body = StremioAddons.get(path, retry = false, timeoutMs = 10000)
        val streams = JSONObject(body).optJSONArray("streams") ?: return emptyList()
        return (0 until streams.length()).mapNotNull { i ->
            val stream = streams.optJSONObject(i) ?: return@mapNotNull null
            val url = stream.optString("url").takeIf { it.startsWith("http") }
            val youtubeId = stream.optString("ytId").takeIf { it.isNotBlank() }
            // Torrents (infoHash) play through TorBox when cached there (StreamSearch checks);
            // external-website streams can't be played here.
            val infoHash = stream.optString("infoHash").takeIf { it.length >= 32 }?.lowercase()
            if (url == null && youtubeId == null && infoHash == null) return@mapNotNull null

            val hints = stream.optJSONObject("behaviorHints")
            val headers = mutableMapOf<String, String>()
            hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { request ->
                request.keys().forEach { key -> headers[key] = request.optString(key) }
            }
            Stream(
                addonName = addon.name,
                name = unescape(stream.optString("name")).replace('\n', ' ').trim(),
                description = unescape(
                    stream.optString("description").ifBlank { stream.optString("title") }
                ).trim(),
                url = url,
                youtubeId = youtubeId,
                headers = headers,
                bingeGroup = hints?.optString("bingeGroup")?.takeIf { it.isNotBlank() },
                infoHash = infoHash.takeIf { url == null },
                fileIdx = if (stream.has("fileIdx")) stream.optInt("fileIdx") else null,
                trackers = stream.optJSONArray("sources")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.filter { it.startsWith("tracker:") }.map { it.removePrefix("tracker:") }
                }.orEmpty(),
                filename = hints?.optString("filename")?.takeIf { it.isNotBlank() }
                    ?: stream.optString("description").ifBlank { stream.optString("title") }
                        .lines().firstOrNull { it.contains(Regex("\\.(mkv|mp4|avi)", RegexOption.IGNORE_CASE)) }
                        ?.replace(Regex("^[^\\p{L}\\p{N}]+"), "")?.trim()
            )
        }
    }

    // Some addons send HTML entities ("Grey&#039;s"); decode them, keeping line breaks.
    private fun unescape(text: String): String =
        if ('&' !in text) text
        else text.split('\n').joinToString("\n") {
            android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        }

    private fun strings(array: JSONArray?): List<String> =
        if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }
}
