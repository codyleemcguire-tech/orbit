package com.orbit.player

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Turns a YouTube video id into a URL Orbit's player can open, so trailers play in the app.
// After Nuvio's InAppYouTubeExtractor (NuvioMedia/NuvioTV, GPL-3.0): YouTube's player API,
// asked as the visionOS app, returns an HLS stream that needs no signature deciphering.
// Other clients' plain MP4 files are the fallback.
object YouTubeTrailer {

    private class Client(val id: String, val version: String, val userAgent: String, val context: JSONObject)

    private val clients = listOf(
        Client("101", "1.02",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
            JSONObject().put("clientName", "VISIONOS").put("clientVersion", "1.02").put("deviceMake", "Apple")
                .put("deviceModel", "RealityDevice17,1").put("osName", "visionOS").put("osVersion", "26.5.23O471")),
        Client("3", "20.10.35", "com.google.android.youtube/20.10.35 (Linux; U; Android 14; en_US) gzip",
            JSONObject().put("clientName", "ANDROID").put("clientVersion", "20.10.35").put("osName", "Android")
                .put("osVersion", "14").put("platform", "MOBILE").put("androidSdkVersion", 34)),
        Client("5", "20.10.1", "com.google.ios.youtube/20.10.1 (iPhone16,2; U; CPU iOS 17_4 like Mac OS X)",
            JSONObject().put("clientName", "IOS").put("clientVersion", "20.10.1").put("deviceModel", "iPhone16,2")
                .put("osName", "iPhone").put("osVersion", "17.4.0.21E219").put("platform", "MOBILE"))
    )

    private const val fallbackKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val browserAgent =
        "Mozilla/5.0 (Linux; Android 12; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    // API key and visitor id from a watch page; without the visitor id YouTube asks to sign in.
    private data class Config(val key: String, val visitor: String?, val at: Long)
    @Volatile private var config: Config? = null

    private fun config(refresh: Boolean): Config {
        config?.takeIf { !refresh && System.currentTimeMillis() - it.at < 3 * 3600_000L }?.let { return it }
        val html = try {
            request("https://www.youtube.com/watch?v=dQw4w9WgXcQ&hl=en", mapOf("user-agent" to browserAgent), null)
        } catch (_: Exception) {
            ""
        }
        val key = Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(html)?.groupValues?.get(1) ?: fallbackKey
        val visitor = Regex("\"VISITOR_DATA\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        return Config(key, visitor, System.currentTimeMillis()).also { config = it }
    }

    // A playable URL for the video, or null. Call off the main thread.
    fun resolve(videoId: String): String? {
        for (attempt in 0..1) {
            val cfg = config(refresh = attempt > 0)
            var progressive: String? = null
            for (client in clients) {
                val response = try {
                    player(videoId, client, cfg)
                } catch (_: Exception) {
                    continue
                }
                if (response.optJSONObject("playabilityStatus")?.optString("status") != "OK") continue
                val streaming = response.optJSONObject("streamingData") ?: continue
                streaming.optString("hlsManifestUrl").takeIf { it.startsWith("http") }?.let { return it }
                // A file with both video and audio, tallest first.
                val formats = streaming.optJSONArray("formats")
                if (progressive == null && formats != null) {
                    progressive = (0 until formats.length()).mapNotNull { formats.optJSONObject(it) }
                        .filter { it.optString("url").startsWith("http") }
                        .maxByOrNull { it.optInt("height") }?.optString("url")
                }
            }
            if (progressive != null) return progressive
        }
        return null
    }

    private fun player(videoId: String, client: Client, cfg: Config): JSONObject {
        val context = JSONObject(client.context.toString()).put("hl", "en").put("gl", "US")
        val body = JSONObject()
            .put("videoId", videoId)
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .put("context", JSONObject().put("client", context))
            .put("playbackContext", JSONObject().put("contentPlaybackContext",
                JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
        val headers = mutableMapOf(
            "content-type" to "application/json",
            "origin" to "https://www.youtube.com",
            "x-youtube-client-name" to client.id,
            "x-youtube-client-version" to client.version,
            "user-agent" to client.userAgent
        )
        cfg.visitor?.let { headers["x-goog-visitor-id"] = it }
        return JSONObject(request("https://www.youtube.com/youtubei/v1/player?key=${cfg.key}", headers, body.toString()))
    }

    private fun request(url: String, headers: Map<String, String>, body: String?): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("accept-language", "en-US,en;q=0.9")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            if (connection.responseCode !in 200..299) throw IllegalStateException("YouTube HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
