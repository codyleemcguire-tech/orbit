package com.orbit.player

import android.content.SharedPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

// TorBox, after NuvioTV's TorBox support (NuvioMedia/NuvioTV, GPL-3.0): sign in with a code
// (or an API key), check which torrent links from addons are already cached on TorBox so
// they play straight away, turn a cached torrent into a direct link when it's played, and
// search TorBox itself for cached copies of a title (TorBox's search service, when it's up).
class TorBox(private val prefs: SharedPreferences) {

    data class DeviceCode(val code: String, val url: String, val deviceCode: String, val intervalS: Int, val expiresInS: Int)

    val apiKey: String get() = prefs.getString("torbox_key", "").orEmpty().trim()
    val signedIn get() = apiKey.isNotEmpty()
    var enabled: Boolean
        get() = signedIn && prefs.getBoolean("torbox_enabled", true)
        set(v) { prefs.edit().putBoolean("torbox_enabled", v).apply() }
    val account: String? get() = prefs.getString("torbox_account", null)

    fun setKey(key: String?) {
        prefs.edit().apply {
            if (key.isNullOrBlank()) remove("torbox_key").remove("torbox_account") else putString("torbox_key", key.trim())
        }.commit()
    }

    // ---- Signing in ----

    fun startSignIn(): DeviceCode {
        val d = (get("v1/api/user/auth/device/start?app=Orbit", auth = false) as JSONObject).getJSONObject("data")
        return DeviceCode(
            d.getString("code"),
            d.optString("verification_url_complete").ifBlank { d.optString("verification_url", "https://torbox.app/oauth/device") },
            d.getString("device_code"), d.optInt("interval", 5), d.optInt("expires_in", 600)
        )
    }

    // True once approved on torbox.app; false while waiting.
    fun finishSignIn(code: DeviceCode): Boolean {
        val request = Request.Builder().url("$api/v1/api/user/auth/device/token")
            .post(JSONObject().put("device_code", code.deviceCode).toString().toRequestBody(json))
            .build()
        client.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val token = runCatching { JSONObject(body).optJSONObject("data")?.optString("access_token") }.getOrNull()
            if (r.isSuccessful && !token.isNullOrBlank()) {
                setKey(token)
                return true
            }
            if (r.code == 400 || r.code == 428 || r.code == 403 || r.code == 202) return false
            if (r.code == 410) throw IllegalStateException("The code expired. Try again.")
            return false
        }
    }

    // "you@example.com · Pro"; null when the key doesn't work. Saves it for Settings.
    fun checkAccount(): String? = try {
        val d = (get("v1/api/user/me") as JSONObject).getJSONObject("data")
        val plan = when (d.optInt("plan", -1)) { 0 -> "Free"; 1 -> "Essential"; 2 -> "Pro"; 3 -> "Standard"; else -> null }
        listOfNotNull(d.optString("email").ifBlank { null }, plan).joinToString("  ·  ").also {
            prefs.edit().putString("torbox_account", it).apply()
        }
    } catch (_: Exception) {
        null
    }

    // ---- Cached torrents ----

    // Which of these info hashes TorBox already has (lower case). Off the main thread.
    fun cached(hashes: Collection<String>): Set<String> {
        if (!enabled || hashes.isEmpty()) return emptySet()
        val found = mutableSetOf<String>()
        hashes.map { it.lowercase() }.distinct().chunked(100).forEach { batch ->
            try {
                val body = JSONObject().put("hashes", JSONArray(batch)).toString().toRequestBody(json)
                val r = send(Request.Builder().url("$api/v1/api/torrents/checkcached?format=object").post(body)) as JSONObject
                r.optJSONObject("data")?.keys()?.forEach { found += it.lowercase() }
            } catch (_: Exception) {
            }
        }
        return found
    }

    // Resolved links, by hash and file: TorBox's links last for hours.
    private val links = ConcurrentHashMap<String, Pair<String, Long>>()

    // A direct link for a cached torrent: added to the account only if cached, then the
    // right file chosen (Stremio's file index, else the episode, else the largest video).
    fun resolve(stream: StremioStreams.Stream, season: Int, episode: Int): String {
        val hash = stream.infoHash ?: throw IllegalStateException("Not a torrent")
        val key = "$hash/${stream.fileIdx}/$season/$episode"
        links[key]?.takeIf { System.currentTimeMillis() - it.second < 2 * 3_600_000L }?.let { return it.first }
        val magnet = buildString {
            append("magnet:?xt=urn:btih:").append(hash)
            stream.trackers.forEach { append("&tr=").append(URLEncoder.encode(it, "UTF-8")) }
        }
        val create = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("magnet", magnet)
            .addFormDataPart("add_only_if_cached", "true")
            .addFormDataPart("allow_zip", "false")
            .build()
        val created = send(Request.Builder().url("$api/v1/api/torrents/createtorrent").post(create)) as JSONObject
        val data = created.optJSONObject("data")
        val torrentId = data?.optInt("torrent_id", 0)?.takeIf { it > 0 } ?: data?.optInt("id", 0)?.takeIf { it > 0 }
            ?: throw IllegalStateException(created.optString("detail").ifBlank { "TorBox doesn't have this cached any more." })
        val torrent = (get("v1/api/torrents/mylist?id=$torrentId&bypass_cache=true") as JSONObject).optJSONObject("data")
        val files = torrent?.optJSONArray("files")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty()
        val file = pickFile(files, stream.fileIdx, season, episode) ?: throw IllegalStateException("No video in this torrent.")
        val link = (get("v1/api/torrents/requestdl?token=${URLEncoder.encode(apiKey, "UTF-8")}&torrent_id=$torrentId&file_id=${file.optInt("id")}") as JSONObject)
            .optString("data").takeIf { it.startsWith("http") } ?: throw IllegalStateException("TorBox didn't give a link.")
        links[key] = link to System.currentTimeMillis()
        return link
    }

    private fun pickFile(files: List<JSONObject>, fileIdx: Int?, season: Int, episode: Int): JSONObject? {
        fun name(f: JSONObject) = listOf(f.optString("short_name"), f.optString("name"), f.optString("absolute_path"))
            .firstOrNull { it.isNotBlank() }.orEmpty()
        val videos = files.filter { f ->
            f.optString("mimetype").startsWith("video/") ||
                Regex("\\.(mkv|mp4|avi|m4v|mov|ts|m2ts|webm)$", RegexOption.IGNORE_CASE).containsMatchIn(name(f))
        }.filterNot { Regex("\\bsample\\b", RegexOption.IGNORE_CASE).containsMatchIn(name(it)) }
        if (videos.isEmpty()) return null
        if (season > 0 && episode > 0) {
            val tag = Regex("s0*${season}[ ._-]?e0*${episode}(?!\\d)|\\b0*${season}x0*${episode}(?!\\d)", RegexOption.IGNORE_CASE)
            videos.firstOrNull { tag.containsMatchIn(name(it)) }?.let { return it }
        }
        fileIdx?.let { idx -> videos.firstOrNull { it.optInt("id", -1) == idx }?.let { return it } }
        return videos.maxByOrNull { it.optLong("size") }
    }

    // ---- TorBox search ----

    // Cached copies of a title from TorBox's own search (search-api.torbox.app). Empty when
    // the service can't be reached.
    fun search(type: String, id: String): List<StremioStreams.Stream> {
        if (!enabled || !id.startsWith("tt")) return emptyList()
        val parts = id.split(':')
        val episodeQuery = if (type == "series" && parts.size == 3) "&season=${parts[1]}&episode=${parts[2]}" else ""
        val url = "https://search-api.torbox.app/torrents/imdb:${parts[0]}?metadata=false&check_cache=true&cached_only=true$episodeQuery"
        val r = try {
            send(Request.Builder().url(url).get()) as JSONObject
        } catch (_: Exception) {
            return emptyList()
        }
        val torrents = r.optJSONObject("data")?.optJSONArray("torrents") ?: return emptyList()
        return (0 until torrents.length()).mapNotNull { torrents.optJSONObject(it) }.mapNotNull { t ->
            val hash = t.optString("hash").takeIf { it.length >= 32 } ?: return@mapNotNull null
            if (t.has("cached") && !t.optBoolean("cached")) return@mapNotNull null
            val title = t.optString("raw_title").ifBlank { t.optString("title") }
            val size = t.optLong("size").takeIf { it > 0 }?.let { formatSize(it) }
            StremioStreams.Stream(
                addonName = searchName,
                name = "TorBox ⚡ instant",
                description = listOfNotNull(title, size).joinToString("\n"),
                url = null, youtubeId = null, headers = emptyMap(),
                filename = title, infoHash = hash.lowercase()
            )
        }
    }

    private fun formatSize(bytes: Long): String =
        if (bytes >= 1L shl 30) String.format(java.util.Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
        else String.format(java.util.Locale.US, "%.0f MB", bytes / (1L shl 20).toDouble())

    // ---- HTTP ----

    private val client get() = NuvioEngine.apiClient
    private fun get(path: String, auth: Boolean = true) = send(Request.Builder().url("$api/$path").get(), auth)

    private fun send(builder: Request.Builder, auth: Boolean = true): Any {
        if (auth) builder.header("Authorization", "Bearer $apiKey")
        builder.header("User-Agent", "Orbit/${BuildConfig.VERSION_NAME}")
        client.newCall(builder.build()).execute().use { r ->
            val text = r.body?.string().orEmpty().trim()
            val parsed: Any = when {
                text.startsWith("{") -> JSONObject(text)
                text.startsWith("[") -> JSONArray(text)
                else -> JSONObject()
            }
            if (!r.isSuccessful) {
                val detail = (parsed as? JSONObject)?.optString("detail")?.takeIf { it.isNotBlank() }
                throw IllegalStateException(detail ?: "TorBox answered ${r.code}")
            }
            return parsed
        }
    }

    companion object {
        private const val api = "https://api.torbox.app"
        private val json = "application/json".toMediaType()
        const val searchName = "TorBox"
        const val searchBase = "torbox://search"
    }
}
