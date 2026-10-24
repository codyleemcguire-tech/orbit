package com.orbit.player

import android.app.Activity
import android.os.Build
import android.os.Bundle
import kotlin.concurrent.thread
import java.net.HttpURLConnection
import java.net.URL
import android.os.Looper
import android.os.Handler
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.orbit.player.MainActivity.ActorPage
import com.orbit.player.MainActivity.DetailHero
import com.orbit.player.MainActivity.DiscoverAdapter
import com.orbit.player.MainActivity.FolderAdapter
import com.orbit.player.MainActivity.NextEpisode
import com.orbit.player.MainActivity.PosterCard
import com.orbit.player.MainActivity.WatchTarget

// Discover: browsing every catalog of every addon, by type and genre, with an info panel.

// Forget Discover's catalogs so the next visit loads them from the new addon list.
internal fun MainActivity.resetDiscover() {
    discoverRequest++
    searchCatalogsCache = null
    discoverCatalogs = null
    discoverResults.clear()
    discoverFocusIndex = -1
    discoverLoading = false
}

// Discover: browse any addon catalog as a poster grid, filtered by type, catalog and
// genre. Modelled on Nuvio's Discover tab, using Orbit's poster cards.
internal fun MainActivity.showDiscoverScreen() {
    currentScreen = "discover"
    detailsReturnScreen = "discover"
    actorPages.clear()

    // Everything above the posters becomes the grid's first, full-width row,
    // so it scrolls away with the grid as before.
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 40, 0, 0)
    }
    val tabs = addHeader("discover", header)

    header.addView(TextView(this).apply {
        text = "Discover"
        textSize = Ui.Type.Title.size
        setTextColor(Ui.textPrimary)
        typeface = Ui.font("bold")
        letterSpacing = Ui.Type.Title.tracking
        setPadding(0, 4, 0, 24)
    })

    val pickers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    typePicker = makePicker { chooseDiscoverType() }
    catalogPicker = makePicker { chooseDiscoverCatalog() }
    genrePicker = makePicker { chooseDiscoverGenre() }
    listOf(typePicker, catalogPicker, genrePicker).forEach {
        it.id = View.generateViewId()
        pickers.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 })
    }
    header.addView(pickers)
    pointTabsDownTo(tabs, typePicker)

    discoverStatus = TextView(this).apply {
        textSize = 16f
        setTextColor(Color.LTGRAY)
        setPadding(0, 24, 0, 0)
    }
    header.addView(discoverStatus)
    discoverHeader = header

    // Back from a title lands on the poster it was opened from.
    pendingDiscoverFocus = discoverFocusIndex.takeIf { it in discoverResults.indices } ?: -1

    discoverList = androidx.recyclerview.widget.RecyclerView(this).apply {
        layoutManager = object : androidx.recyclerview.widget.GridLayoutManager(context, discoverColumns) {
            // Scroll far enough to leave room around the focused poster: it zooms by 12%
            // and gains a ring, which would otherwise run off the top or bottom edge.
            override fun requestChildRectangleOnScreen(
                parent: androidx.recyclerview.widget.RecyclerView,
                child: View,
                rect: android.graphics.Rect,
                immediate: Boolean,
                focusedChildVisible: Boolean
            ): Boolean {
                val padded = android.graphics.Rect(rect.left, rect.top - 70, rect.right, rect.bottom + 70)
                return super.requestChildRectangleOnScreen(parent, child, padded, immediate, focusedChildVisible)
            }
        }.apply {
                spanSizeLookup = object :
                    androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) =
                        if (position == 0) discoverColumns else 1
                }
            }
        adapter = DiscoverAdapter()
        // Inserting a page shouldn't animate every poster.
        itemAnimator = null
        setItemViewCacheSize(2 * discoverColumns)
        // Room for the zoomed poster, and columns that line up with the title.
        setPadding(Ui.gutter, 0, 8, 40)
        clipToPadding = false
        clipChildren = false
    }
    // Grid on the left, info about the highlighted title fixed on the right.
    val screen = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        background = screenBackground()
        addView(discoverList, LinearLayout.LayoutParams(0, -1, 1f))
        addView(makeDiscoverInfoPanel(), LinearLayout.LayoutParams(560, -1))
    }
    setContentView(screen)
    discoverResults.getOrNull(discoverFocusIndex)?.let { showDiscoverInfo(it, immediate = true) }
    // Moving up into the header shows all of it, not just the control that took focus.
    // The listener is window-wide, so it's added once and removed when Discover closes.
    val watcher = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        if (currentScreen != "discover") return@OnGlobalFocusChangeListener
        var view: View? = newFocus
        while (view != null && view !== discoverHeader) view = view.parent as? View
        if (view != null && discoverList.computeVerticalScrollOffset() > 0) {
            discoverList.smoothScrollToPosition(0)
        }
    }
    stopDiscoverFocusWatcher()
    window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(watcher)
    discoverFocusWatcher = watcher

    updatePickerLabels()
    when {
        discoverCatalogs == null -> loadDiscoverCatalogs()
        discoverResults.isEmpty() -> fetchDiscoverPage(reset = true)
    }
    if (pendingDiscoverFocus > 0) discoverList.scrollToPosition(pendingDiscoverFocus + 1)
}

// Fixed panel on the right of Discover: backdrop, type, title, chips, genres, synopsis.
internal fun MainActivity.makeDiscoverInfoPanel(): View {
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(36, 150, Ui.gutter, 40)
        setBackgroundColor(Ui.surface)
    }
    infoBackdrop = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.rounded(Ui.surfaceRaised, 14f)
    }
    panel.addView(infoBackdrop, LinearLayout.LayoutParams(-1, 258))
    infoOverline = Ui.text(this, Ui.Type.Overline, "", Ui.accent).apply { setPadding(0, 30, 0, 0) }
    panel.addView(infoOverline)
    infoTitle = Ui.text(this, Ui.Type.Title, "Highlight a title", maxLines = 2).apply {
        textSize = 24f
        setPadding(0, 12, 0, 16)
    }
    panel.addView(infoTitle)
    infoChips = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    panel.addView(infoChips)
    infoGenres = Ui.text(this, Ui.Type.Label, "", Ui.textSecondary, maxLines = 1).apply {
        setPadding(0, 14, 0, 0)
    }
    panel.addView(infoGenres)
    infoOverview = Ui.text(this, Ui.Type.Body, "Move onto a poster to see what it's about.", Ui.textSecondary, maxLines = 9).apply {
        textSize = 14f
        setPadding(0, 18, 0, 0)
    }
    panel.addView(infoOverview)
    return panel
}

// Catalogs write runtime as "126 min", "1h55min" or "45"; show it like the rest of Orbit.
internal fun MainActivity.formatCatalogRuntime(raw: String): String? {
    if (raw.isBlank()) return null
    val hours = Regex("(\\d+)\\s*h").find(raw)?.groupValues?.get(1)?.toInt() ?: 0
    val minutes = Regex("(\\d+)\\s*m").find(raw)?.groupValues?.get(1)?.toInt()
        ?: if (hours == 0) raw.filter { it.isDigit() }.toIntOrNull() else 0
    return Ui.formatRuntime(hours * 60 + (minutes ?: 0))
}

internal fun MainActivity.showDiscoverInfo(meta: StremioAddons.Meta, immediate: Boolean = false) {
    infoHandler.removeCallbacksAndMessages(null)
    val apply = apply@{
        if (currentScreen != "discover" && currentScreen != "collection") return@apply
        infoOverline.text = when (meta.type) {
            "movie" -> "MOVIE"
            "series" -> "SERIES"
            else -> meta.type.uppercase()
        }
        infoTitle.text = meta.name
        infoChips.removeAllViews()
        listOfNotNull(
            meta.year.takeIf { it.isNotBlank() },
            meta.rating.toDoubleOrNull()?.let { Ui.formatRating(it) },
            formatCatalogRuntime(meta.runtime)
        ).forEach { label ->
            infoChips.addView(
                Ui.chip(this, label, if (label.startsWith("★")) Ui.gold else Ui.textSecondary),
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 10 }
            )
        }
        infoGenres.text = meta.genres.take(3).joinToString("  ·  ")
        infoOverview.text = meta.description.ifBlank { "No synopsis from this catalog." }
        infoBackdrop.setImageDrawable(null)
        (meta.background ?: meta.poster)?.let {
            loadPosterImage(infoBackdrop, it, "w780", targetWidth = 488, cornerRadius = 14f)
        }
        // Fill what the catalog left out (synopsis, backdrop, rating, genres) from TMDB.
        val missing = meta.description.isBlank() || meta.background == null || meta.rating.isBlank() || meta.genres.isEmpty()
        if (missing && tmdbSettings.enabled && tmdbSettings.fillDiscover) thread {
            val d = try {
                TmdbMeta.resolve(meta.id, meta.type, BuildConfig.TMDB_API_KEY)
                    ?.let { (t, id) -> TmdbMeta.details(t, id, BuildConfig.TMDB_API_KEY) }
            } catch (_: Exception) {
                null
            } ?: return@thread
            Handler(Looper.getMainLooper()).post {
                if (infoTitle.text != meta.name) return@post
                val m = d.metadata
                if (meta.description.isBlank() && m.overview.isNotBlank()) infoOverview.text = m.overview
                if (meta.genres.isEmpty()) infoGenres.text = m.genres.take(3).joinToString("  ·  ")
                if (meta.rating.isBlank()) Ui.formatRating(m.rating)?.let { label ->
                    infoChips.addView(Ui.chip(this, label, Ui.gold), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 10 })
                }
                if (meta.background == null) m.backdropPath?.let {
                    loadPosterImage(infoBackdrop, it, "w780", targetWidth = 488, cornerRadius = 14f)
                }
            }
        }
    }
    // A short pause before updating, so moving quickly across the grid doesn't load
    // every backdrop on the way.
    if (immediate) apply() else infoHandler.postDelayed({ apply() }, 300)
}

internal fun MainActivity.typeLabel(type: String) = when (type) {
    "movie" -> "Movies"
    "series" -> "Series"
    else -> type.replaceFirstChar { it.uppercase() }
}

internal fun MainActivity.catalogLabel(catalog: StremioAddons.Catalog): String {
    val addonCount = discoverCatalogs.orEmpty().map { it.baseUrl }.distinct().size
    return if (addonCount > 1) "${catalog.name}  ·  ${catalog.addonName}" else catalog.name
}

internal fun MainActivity.selectedCatalog() =
    discoverCatalogs?.firstOrNull { it.key == discoverCatalogKey }

internal fun MainActivity.updatePickerLabels() {
    val catalog = selectedCatalog()
    typePicker.text = "${typeLabel(discoverType)}   ▾"
    catalogPicker.text = (catalog?.let { catalogLabel(it) }
        ?: if (discoverCatalogs == null) "Loading..." else "No catalogs") + "   ▾"
    genrePicker.text = (discoverGenre ?: "All genres") + "   ▾"
    genrePicker.visibility = if (catalog?.genres.isNullOrEmpty()) View.GONE else View.VISIBLE
}

internal fun MainActivity.chooseDiscoverType() {
    val catalogs = discoverCatalogs.orEmpty()
    val types = catalogs.map { it.type }.distinct()
    choose("Type", types.map(::typeLabel), types.indexOf(discoverType)) { i ->
        val type = types[i]
        if (type == discoverType) return@choose
        // Keep the same kind of list when switching (Popular movies -> Popular series).
        val current = selectedCatalog()
        val next = catalogs.firstOrNull { it.type == type && it.id == current?.id }
            ?: catalogs.firstOrNull { it.type == type }
        next?.let { selectDiscoverCatalog(it) }
    }
}

internal fun MainActivity.chooseDiscoverCatalog() {
    val options = discoverCatalogs.orEmpty().filter { it.type == discoverType }
    choose(
        "Catalog",
        options.map(::catalogLabel),
        options.indexOfFirst { it.key == discoverCatalogKey }
    ) { i -> selectDiscoverCatalog(options[i]) }
}

internal fun MainActivity.chooseDiscoverGenre() {
    val catalog = selectedCatalog() ?: return
    val options = (if (catalog.genreRequired) emptyList() else listOf<String?>(null)) +
        catalog.genres
    choose(
        "Genre",
        options.map { it ?: "All genres" },
        options.indexOf(discoverGenre)
    ) { i ->
        if (options[i] == discoverGenre) return@choose
        discoverGenre = options[i]
        updatePickerLabels()
        fetchDiscoverPage(reset = true)
    }
}

internal fun MainActivity.selectDiscoverCatalog(catalog: StremioAddons.Catalog) {
    discoverCatalogKey = catalog.key
    discoverType = catalog.type
    // Some catalogs (e.g. "New", by year) can't be listed without a genre.
    discoverGenre = if (catalog.genreRequired) catalog.genres.firstOrNull() else null
    updatePickerLabels()
    fetchDiscoverPage(reset = true)
}

internal fun MainActivity.loadDiscoverCatalogs() {
    discoverStatus.text = "Loading catalogs..."
    val request = discoverRequest
    thread {
        // Pick up addons added or removed in Stremio since the last visit.
        val syncNote = syncStremioAddons()
        val catalogs = StremioAddons.fetchCatalogs()
        Handler(Looper.getMainLooper()).post {
            if (request != discoverRequest) return@post
            discoverCatalogs = catalogs
            val first = catalogs.firstOrNull { it.type == discoverType } ?: catalogs.firstOrNull()
            if (first == null) {
                updatePickerLabels()
                discoverStatus.text = when {
                    StremioAddons.addonUrls.isEmpty() ->
                        "No addons yet. Sign in to Stremio or add an addon by URL in Settings."
                    else -> "None of your addons have catalogs to browse, " +
                        "or they couldn't be reached."
                }
            } else {
                selectDiscoverCatalog(first)
            }
            if (syncNote.startsWith("Your Stremio session")) discoverStatus.text = syncNote
        }
    }
}

internal fun MainActivity.fetchDiscoverPage(reset: Boolean) {
    val catalog = selectedCatalog() ?: return
    if (reset) {
        discoverRequest++
        discoverResults.clear()
        discoverOffset = 0
        discoverStep = 0
        discoverEmptyPages = 0
        discoverHasMore = true
        discoverFocusIndex = -1
        if (currentScreen == "discover") discoverList.adapter?.notifyDataSetChanged()
    } else if (discoverLoading || !discoverHasMore) {
        return
    }
    discoverLoading = true
    val request = discoverRequest
    val genre = discoverGenre
    val skip = discoverOffset
    discoverStatus.text = if (reset) "Loading..." else ""

    thread {
        val page = try {
            StremioAddons.fetchCatalogPage(catalog, genre, skip)
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (request != discoverRequest) return@post
            discoverLoading = false
            if (page == null) {
                discoverStatus.text = "Couldn't load this catalog. Please try again."
                return@post
            }
            // Addons sometimes repeat titles across pages.
            val known = discoverResults.map { it.id }.toHashSet()
            val fresh = page.metas.filter { known.add(it.id) }
            discoverStep = maxOf(discoverStep, page.metas.size)
            // A page of only repeats moves on a full page; the list has only run out
            // after several of those in a row, or an empty page.
            discoverOffset += if (fresh.isNotEmpty()) page.metas.size else discoverStep
            discoverEmptyPages = if (fresh.isEmpty()) discoverEmptyPages + 1 else 0
            discoverHasMore = page.hasMore && page.metas.isNotEmpty() && discoverEmptyPages < 3
            val start = discoverResults.size
            discoverResults.addAll(fresh)
            discoverStatus.text = if (discoverResults.isEmpty()) "Nothing in this catalog." else ""
            if (currentScreen == "discover") appendDiscoverCards(start)
            // Keep at least five rows loaded below the focused poster; small pages
            // (some addons send 20 titles, two and a half rows) would otherwise run dry.
            val ahead = discoverResults.size - maxOf(discoverFocusIndex, 0)
            if (discoverHasMore && ahead < 5 * discoverColumns) fetchDiscoverPage(reset = false)
        }
    }
}

internal fun MainActivity.appendDiscoverCards(start: Int) {
    val adapter = discoverList.adapter ?: return
    // The previous last poster is no longer the end of its row.
    if (start > 0) adapter.notifyItemChanged(start)
    adapter.notifyItemRangeInserted(start + 1, discoverResults.size - start)
}

internal fun MainActivity.openDiscoverItem(index: Int) {
    val meta = discoverResults.getOrNull(index) ?: return
    discoverFocusIndex = index
    openMeta(meta, discoverStatus, "discover")
}

// Opens an addon catalog item: films straight to their page; series need TMDB's id,
// which some addons give directly and the rest are looked up from the IMDb id.
internal fun MainActivity.openMeta(meta: StremioAddons.Meta, status: TextView, fromScreen: String) {
    if (meta.type != "series") {
        showFilmDetails(
            meta.name, meta.poster.orEmpty(), imdbId = meta.id.takeIf { it.startsWith("tt") }
        )
        return
    }
    meta.id.removePrefix("tmdb:").takeIf { meta.id.startsWith("tmdb:") }
        ?.toIntOrNull()?.let { tvId ->
            showTvDetails(tvId, meta.name, meta.poster.orEmpty())
            return
        }
    status.text = "Opening ${meta.name}..."
    thread {
        val tvId = try {
            TmdbApi.findTvIdByImdb(meta.id, BuildConfig.TMDB_API_KEY)
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (currentScreen != fromScreen) return@post
            status.text = ""
            if (tvId != null) showTvDetails(tvId, meta.name, meta.poster.orEmpty())
            else status.text = "Couldn't find ${meta.name} on TMDB."
        }
    }
}
