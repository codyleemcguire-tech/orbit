package com.orbit.player

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Reads catalogs from Stremio addons. Catalog selection follows Nuvio's Discover
// (NuvioMedia/NuvioTV, GPL-3.0): every browsable catalog of every addon, with its genres.
object StremioAddons {

    // The signed-in Stremio account's addons (see StremioAccount); empty when signed out.
    @Volatile
    var addonUrls: List<String> = emptyList()

    data class Catalog(
        val key: String,
        val addonName: String,
        val baseUrl: String,
        val id: String,
        val name: String,
        val type: String,
        val genres: List<String>,
        val genreRequired: Boolean,
        val supportsSkip: Boolean
    )

    data class Meta(
        val id: String,
        val type: String,
        val name: String,
        val poster: String?,
        val year: String,
        // Shown in Discover's info panel; catalogs send these with every item.
        val description: String = "",
        val rating: String = "",
        val genres: List<String> = emptyList(),
        val runtime: String = "",
        val background: String? = null
    )

    data class Page(val metas: List<Meta>, val hasMore: Boolean)

    private val manifests = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    // Manifests are read for catalogs and again for streams; keep them for the session.
    // Addons whose manifest couldn't be read this session, so they aren't asked again.
    private val brokenManifests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Manifests are also kept on disk for 6 hours (as NuvioTV does), so after Orbit starts the
    // first Play doesn't wait for every addon's manifest. An older copy is still used straight
    // away and refreshed in the background.
    @Volatile
    var diskCache: java.io.File? = null
    private const val manifestTtlMs = 6 * 60 * 60 * 1000L
    private val refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun cacheFile(manifestUrl: String): java.io.File? = diskCache?.let { dir ->
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(manifestUrl.toByteArray())
            .joinToString("") { "%02x".format(it) }
        java.io.File(dir, "manifest-$name.json")
    }

    fun manifest(manifestUrl: String): JSONObject {
        manifests[manifestUrl]?.let { return it }
        cacheFile(manifestUrl)?.takeIf { it.exists() }?.let { file ->
            try {
                val cached = JSONObject(file.readText())
                manifests[manifestUrl] = cached
                if (System.currentTimeMillis() - file.lastModified() > manifestTtlMs && refreshing.add(manifestUrl)) {
                    Thread {
                        try {
                            fetchManifest(manifestUrl)
                        } catch (_: Exception) {
                        } finally {
                            refreshing.remove(manifestUrl)
                        }
                    }.start()
                }
                return cached
            } catch (_: Exception) {
                file.delete()
            }
        }
        if (manifestUrl in brokenManifests) throw IllegalStateException("Addon unavailable")
        return try {
            fetchManifest(manifestUrl)
        } catch (e: Exception) {
            brokenManifests.add(manifestUrl)
            throw e
        }
    }

    private fun fetchManifest(manifestUrl: String): JSONObject {
        val text = get(manifestUrl, retry = false)
        val manifest = JSONObject(text)
        manifests[manifestUrl] = manifest
        try {
            cacheFile(manifestUrl)?.writeText(text)
        } catch (_: Exception) {
        }
        return manifest
    }

    // Lets a previously unreachable addon be tried again (e.g. after it's re-added).
    fun forget(manifestUrl: String) {
        manifests.remove(manifestUrl)
        brokenManifests.remove(manifestUrl)
        cacheFile(manifestUrl)?.delete()
    }

    // Reads every addon's manifest at once, so the first Play doesn't wait on them one by one.
    fun loadManifests() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        addonUrls.map { url ->
            pool.submit {
                try {
                    manifest(url)
                } catch (_: Exception) {
                }
            }
        }.forEach { it.get() }
        pool.shutdown()
    }

    fun fetchCatalogs(): List<Catalog> = addonUrls.flatMap { manifestUrl ->
        try {
            val manifest = manifest(manifestUrl)
            val baseUrl = manifestUrl.removeSuffix("/manifest.json")
            val addonName = manifest.optString("name", baseUrl)
            val catalogs = manifest.optJSONArray("catalogs") ?: JSONArray()

            (0 until catalogs.length()).mapNotNull { i ->
                val catalog = catalogs.getJSONObject(i)
                val extras = catalog.optJSONArray("extra") ?: JSONArray()
                val extraList = (0 until extras.length()).map { extras.getJSONObject(it) }
                val legacySupported = catalog.optJSONArray("extraSupported")
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    .orEmpty()

                // Skip catalogs that can't be browsed without input (search-only, id lists).
                val needsInput = extraList.any {
                    it.optBoolean("isRequired") && !it.optString("name").equals("genre", true)
                }
                if (needsInput) return@mapNotNull null

                val genreExtra = extraList.firstOrNull { it.optString("name").equals("genre", true) }
                val genres = genreExtra?.optJSONArray("options")
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    .orEmpty()
                val type = catalog.optString("type")
                val id = catalog.optString("id")

                Catalog(
                    key = "$baseUrl|$type|$id",
                    addonName = addonName,
                    baseUrl = baseUrl,
                    id = id,
                    name = catalog.optString("name", id),
                    type = type,
                    genres = genres,
                    genreRequired = genreExtra?.optBoolean("isRequired") == true,
                    supportsSkip = extraList.any { it.optString("name") == "skip" } ||
                        "skip" in legacySupported
                )
            }
        } catch (_: Exception) {
            // An unreachable addon just contributes no catalogs.
            emptyList()
        }
    }

    // Catalogs that accept a search query (Cinemeta's Popular, AIOMetadata's search
    // catalogs, …), in the account's addon order.
    fun searchCatalogs(): List<Catalog> = addonUrls.flatMap { manifestUrl ->
        try {
            val manifest = manifest(manifestUrl)
            val baseUrl = manifestUrl.removeSuffix("/manifest.json")
            val addonName = manifest.optString("name", baseUrl)
            val catalogs = manifest.optJSONArray("catalogs") ?: JSONArray()
            (0 until catalogs.length()).mapNotNull { i ->
                val catalog = catalogs.getJSONObject(i)
                val extras = catalog.optJSONArray("extra") ?: JSONArray()
                val names = (0 until extras.length()).map { extras.getJSONObject(it).optString("name") } +
                    (catalog.optJSONArray("extraSupported")
                        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                        .orEmpty())
                if ("search" !in names) return@mapNotNull null
                // Anything else required (a genre, an id list) can't be filled in for a search.
                val otherRequired = (0 until extras.length()).map { extras.getJSONObject(it) }
                    .any { it.optBoolean("isRequired") && it.optString("name") != "search" }
                if (otherRequired) return@mapNotNull null
                val type = catalog.optString("type")
                val id = catalog.optString("id")
                Catalog(
                    key = "$baseUrl|$type|$id",
                    addonName = addonName,
                    baseUrl = baseUrl,
                    id = id,
                    name = catalog.optString("name", id),
                    type = type,
                    genres = emptyList(),
                    genreRequired = false,
                    supportsSkip = false
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun fetchCatalogPage(catalog: Catalog, genre: String?, skip: Int, search: String? = null): Page {
        if (catalog.baseUrl == OrbitCatalogs.base) return OrbitCatalogs.page(catalog, genre, skip, BuildConfig.TMDB_API_KEY)
        val extras = buildList {
            if (!search.isNullOrBlank()) add("search=${encode(search)}")
            if (!genre.isNullOrBlank()) add("genre=${encode(genre)}")
            if (skip > 0) add("skip=$skip")
        }
        val path = "${catalog.baseUrl}/catalog/${catalog.type}/${encode(catalog.id)}" +
            (if (extras.isEmpty()) "" else "/" + extras.joinToString("&")) + ".json"

        val body = JSONObject(get(path))
        val metas = body.optJSONArray("metas") ?: JSONArray()
        val items = (0 until metas.length()).mapNotNull { i ->
            val meta = metas.getJSONObject(i)
            val id = meta.optString("id")
            val name = meta.optString("name")
            if (id.isBlank() || name.isBlank()) return@mapNotNull null
            Meta(
                id = id,
                type = meta.optString("type", catalog.type),
                name = name,
                poster = meta.optString("poster").takeIf { it.startsWith("http") },
                year = meta.optString("releaseInfo").ifBlank { meta.optString("year") },
                description = meta.optString("description"),
                rating = meta.optString("imdbRating"),
                genres = (meta.optJSONArray("genres") ?: meta.optJSONArray("genre"))
                    ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
                    .orEmpty().filter { it.isNotBlank() },
                runtime = meta.optString("runtime"),
                background = meta.optString("background").takeIf { it.startsWith("http") }
            )
        }
        val hasMore = catalog.supportsSkip && items.isNotEmpty() &&
            body.optBoolean("hasMore", true)
        return Page(items, hasMore)
    }

    internal fun encode(value: String) =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    // Cinemeta answers 504 for pages it hasn't cached yet and serves them moments later,
    // so failed requests are retried before giving up.
    // retry = false and a short timeout suit stream requests, where a slow addon shouldn't
    // hold up the others' results.
    // Through NuvioEngine's shared OkHttp pool, so an addon's connection stays warm between requests.
    internal fun get(url: String, retry: Boolean = true, timeoutMs: Int = 20000): String {
        val client = NuvioEngine.apiClient.newBuilder()
            .connectTimeout(minOf(10000, timeoutMs).toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs + 5000L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        var lastError: Exception? = null
        for (delayMs in if (retry) listOf(0L, 1500L, 4000L) else listOf(0L)) {
            if (delayMs > 0) Thread.sleep(delayMs)
            val request = okhttp3.Request.Builder().url(url).header("Accept", "application/json").build()
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.string().orEmpty()
                    lastError = IllegalStateException("Addon HTTP ${response.code}")
                    if (response.code < 500) throw lastError!!
                }
            } catch (e: java.io.IOException) {
                lastError = e
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Not a valid addon address")
            }
        }
        throw lastError ?: IllegalStateException("Addon request failed")
    }
}
