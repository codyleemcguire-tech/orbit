package com.orbit.player

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Reads collections exported from Nuvio (Collections > Export, or its phone page) and
// loads the titles behind each folder. Format follows Nuvio's CollectionsDataStore
// (NuvioMedia/NuvioTV, GPL-3.0): a JSON array of collections, each with folders, each
// folder with sources from an addon catalog, TMDB or Trakt.
object NuvioCollections {

    data class Collection(val id: String, val title: String, val folders: List<Folder>)

    data class Folder(
        val id: String,
        val title: String,
        val cover: String?,
        val emoji: String?,
        val backdrop: String?,
        val sources: List<Source>
    )

    sealed interface Source {
        val label: String
    }

    data class AddonSource(
        val addonId: String,
        val type: String,
        val catalogId: String,
        val genre: String?
    ) : Source {
        override val label get() = listOfNotNull(catalogId, genre).joinToString(" · ")
    }

    data class TmdbSource(
        val kind: String,
        override val label: String,
        val tmdbId: Int?,
        val mediaType: String,
        val sortBy: String,
        val filters: JSONObject?
    ) : Source

    data class TraktSource(override val label: String, val listId: Long) : Source

    // Throws IllegalArgumentException with a readable message when it isn't Nuvio's format.
    fun parse(json: String): List<Collection> {
        val trimmed = json.trim()
        if (!trimmed.startsWith("[")) throw IllegalArgumentException("Expected Nuvio's collections export (a JSON list).")
        val array = JSONArray(trimmed)
        val collections = (0 until array.length()).mapNotNull { i ->
            val c = array.optJSONObject(i) ?: return@mapNotNull null
            val folders = c.optJSONArray("folders") ?: return@mapNotNull null
            Collection(
                id = c.optString("id").ifBlank { "collection-$i" },
                title = c.optString("title").ifBlank { "Collection" },
                folders = (0 until folders.length()).mapNotNull { j ->
                    val f = folders.optJSONObject(j) ?: return@mapNotNull null
                    // Newer exports use "sources"; older ones only had addon "catalogSources".
                    val sources = f.optJSONArray("sources") ?: f.optJSONArray("catalogSources") ?: JSONArray()
                    Folder(
                        id = f.optString("id").ifBlank { "folder-$j" },
                        title = f.optString("title"),
                        cover = f.optString("coverImageUrl").takeIf { it.startsWith("http") },
                        emoji = f.optString("coverEmoji").takeIf { it.isNotBlank() && it != "null" },
                        backdrop = f.optString("heroBackdropUrl").takeIf { it.startsWith("http") },
                        sources = (0 until sources.length()).mapNotNull { k ->
                            sources.optJSONObject(k)?.let(::parseSource)
                        }
                    )
                }
            )
        }
        if (collections.isEmpty()) throw IllegalArgumentException("No collections found in that export.")
        return collections
    }

    private fun parseSource(s: JSONObject): Source? = when (s.optString("provider", "addon").lowercase()) {
        "tmdb" -> {
            val kind = s.optString("tmdbSourceType").uppercase()
            if (kind.isBlank()) null
            else TmdbSource(
                kind = kind,
                label = s.optString("title").ifBlank { kind.lowercase().replaceFirstChar { it.uppercase() } },
                tmdbId = s.optInt("tmdbId", 0).takeIf { it > 0 },
                mediaType = if (s.optString("mediaType").equals("tv", true)) "tv" else "movie",
                sortBy = s.optString("sortBy").ifBlank { "popularity.desc" },
                filters = s.optJSONObject("filters")
            )
        }
        "trakt" -> s.optLong("traktListId", 0).takeIf { it > 0 }?.let {
            TraktSource(s.optString("title").ifBlank { "Trakt list $it" }, it)
        }
        else -> {
            val addonId = s.optString("addonId")
            val type = s.optString("type")
            val catalogId = s.optString("catalogId")
            if (addonId.isBlank() || type.isBlank() || catalogId.isBlank()) null
            else AddonSource(addonId, type, catalogId, s.optString("genre").takeIf { it.isNotBlank() && it != "null" })
        }
    }

    // next: where the following page starts (an addon's skip offset, or TMDB's page
    // number); null when the source has no more.
    data class Loaded(
        val label: String,
        val metas: List<StremioAddons.Meta>,
        val problem: String? = null,
        val next: Int? = null
    )

    // Loads one page of a source's titles; cursor is a Loaded.next (null for the first).
    // Call off the main thread.
    fun load(source: Source, tmdbKey: String, cursor: Int? = null): Loaded = try {
        when (source) {
            is AddonSource -> loadAddon(source, cursor ?: 0)
            is TmdbSource -> {
                val page = cursor ?: 1
                val (metas, totalPages) = loadTmdb(source, tmdbKey, page)
                // TMDB serves at most 500 pages of anything.
                Loaded(source.label, metas, next = (page + 1).takeIf { it <= minOf(totalPages, 500) && metas.isNotEmpty() })
            }
            is TraktSource -> Loaded(source.label, emptyList(), "Trakt lists aren't supported in Orbit yet.")
        }
    } catch (e: Exception) {
        Loaded(source.label, emptyList(), "Couldn't load this source (${e.message}).")
    }

    private fun loadAddon(source: AddonSource, skip: Int): Loaded {
        // Find the installed addon by its manifest id, then the catalog within it.
        for (url in StremioAddons.addonUrls) {
            val manifest = try {
                StremioAddons.manifest(url)
            } catch (_: Exception) {
                continue
            }
            if (manifest.optString("id") != source.addonId) continue
            val catalogs = manifest.optJSONArray("catalogs") ?: JSONArray()
            for (i in 0 until catalogs.length()) {
                val c = catalogs.getJSONObject(i)
                if (c.optString("type") != source.type || c.optString("id") != source.catalogId) continue
                val extras = c.optJSONArray("extra") ?: JSONArray()
                val supportsSkip = (0 until extras.length()).any { extras.getJSONObject(it).optString("name") == "skip" } ||
                    c.optJSONArray("extraSupported")?.let { a -> (0 until a.length()).any { a.optString(it) == "skip" } } == true
                val catalog = StremioAddons.Catalog(
                    key = "${url}|${source.type}|${source.catalogId}",
                    addonName = manifest.optString("name"),
                    baseUrl = url.removeSuffix("/manifest.json"),
                    id = source.catalogId,
                    name = c.optString("name", source.catalogId),
                    type = source.type,
                    genres = emptyList(),
                    genreRequired = false,
                    supportsSkip = supportsSkip
                )
                val label = listOfNotNull(catalog.name, source.genre).joinToString(" · ") +
                    "  ·  ${catalog.addonName}"
                val page = StremioAddons.fetchCatalogPage(catalog, source.genre, skip)
                return Loaded(label, page.metas, next = (skip + page.metas.size).takeIf { page.hasMore })
            }
            return Loaded(source.label, emptyList(), "${manifest.optString("name")} no longer has this catalog.")
        }
        return Loaded(source.label, emptyList(), "Needs the addon “${source.addonId}”, which isn't installed.")
    }

    // Returns one page of titles and how many pages the source has.
    private fun loadTmdb(source: TmdbSource, key: String, page: Int): Pair<List<StremioAddons.Meta>, Int> {
        val type = source.mediaType
        val id = source.tmdbId
        var totalPages = 1
        fun paged(path: String): JSONArray? {
            val sep = if ('?' in path) '&' else '?'
            val body = tmdb("$path${sep}page=$page", key)
            totalPages = body.optInt("total_pages", 1)
            return body.optJSONArray("results") ?: body.optJSONArray("items")
        }
        val results: JSONArray = when (source.kind) {
            "LIST" -> paged("list/$id")
            "COLLECTION" -> tmdb("collection/$id", key).optJSONArray("parts")
                // A film series reads best in release order.
                ?.let { parts ->
                    JSONArray((0 until parts.length()).map { parts.getJSONObject(it) }
                        .sortedBy { it.optString("release_date").ifBlank { "9999" } })
                }
            "COMPANY" -> paged("discover/$type?sort_by=${source.sortBy}&with_companies=$id")
            "NETWORK" -> paged("discover/tv?sort_by=${source.sortBy}&with_networks=$id")
            "PERSON", "DIRECTOR" -> {
                val credits = tmdb("person/$id/combined_credits", key)
                val list = if (source.kind == "DIRECTOR") {
                    credits.optJSONArray("crew")?.let { crew ->
                        (0 until crew.length()).map { crew.getJSONObject(it) }.filter { it.optString("job") == "Director" }
                    }
                } else {
                    credits.optJSONArray("cast")?.let { cast -> (0 until cast.length()).map { cast.getJSONObject(it) } }
                }.orEmpty().sortedByDescending { it.optDouble("popularity") }.distinctBy { it.optInt("id") }
                JSONArray(list)
            }
            else -> {
                // DISCOVER: Nuvio's filter names are TMDB's discover parameters in camelCase.
                val params = mutableListOf("sort_by=${source.sortBy}")
                source.filters?.let { f ->
                    f.keys().forEach { k ->
                        val v = f.opt(k)
                        if (v == null || v == JSONObject.NULL || v.toString().isBlank()) return@forEach
                        val name = k.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() }
                            .replace("_gte", ".gte").replace("_lte", ".lte")
                            .let { if (type == "tv" && it.startsWith("release_date")) it.replace("release_date", "first_air_date") else it }
                            .let { if (it == "year") (if (type == "tv") "first_air_date_year" else "primary_release_year") else it }
                        params += "$name=${java.net.URLEncoder.encode(v.toString(), "UTF-8")}"
                    }
                }
                paged("discover/$type?${params.joinToString("&")}")
            }
        } ?: JSONArray()

        val metas = (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            // Lists and credits mix films and series; each item says which it is.
            val itemType = item.optString("media_type").ifBlank { type }
            if (itemType != "movie" && itemType != "tv") return@mapNotNull null
            val poster = item.optString("poster_path").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val title = item.optString("title").ifBlank { item.optString("name") }
            if (title.isBlank()) return@mapNotNull null
            StremioAddons.Meta(
                id = "tmdb:${item.optInt("id")}",
                type = if (itemType == "tv") "series" else "movie",
                name = title,
                poster = "https://image.tmdb.org/t/p/w342$poster",
                year = item.optString("release_date").ifBlank { item.optString("first_air_date") }.take(4),
                description = item.optString("overview"),
                rating = item.optDouble("vote_average", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) }.orEmpty(),
                background = item.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }
                    ?.let { "https://image.tmdb.org/t/p/w780$it" }
            )
        }
        return metas to totalPages
    }

    private fun tmdb(path: String, key: String): JSONObject {
        val sep = if ('?' in path) '&' else '?'
        val connection = URL("https://api.themoviedb.org/3/$path${sep}api_key=${java.net.URLEncoder.encode(key, "UTF-8")}")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("TMDB HTTP $status")
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }
}
