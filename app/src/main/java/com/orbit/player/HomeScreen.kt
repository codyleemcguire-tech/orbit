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

// Home: the hero, Continue watching, My List, collections and catalog rows, and how focus
// moves between them.

internal fun MainActivity.rememberHomeFocus(card: View) {
    val row = card.parent as? LinearLayout ?: return
    val rowIndex = homeRows.indexOfFirst { it === row }
    if (currentScreen != "home" || rowIndex < 0) return
    // Each row keeps its own last position, so moving up/down returns to it.
    row.tag = row.indexOfChild(card)
    homeFocusRow = rowIndex
    homeFocusRowName = (homeRowHeadings.getOrNull(rowIndex) as? TextView)?.text?.toString()
    homeFocusIndex = row.indexOfChild(card)
}

internal fun MainActivity.focusHomeRow(rowIndex: Int, preferredIndex: Int, animate: Boolean = true) {
    val row = homeRows.getOrNull(rowIndex) ?: return
    if (row.childCount == 0) return
    row.getChildAt(preferredIndex.coerceIn(0, row.childCount - 1)).requestFocus()
    // Bring the row's heading into view, not just the poster.
    val heading = homeRowHeadings[rowIndex]
    val scroller = homeRowsScroll
    val scrollToHeading = {
        val y = (heading.top - 8).coerceAtLeast(0)
        if (animate) scroller.smoothScrollTo(0, y) else scroller.scrollTo(0, y)
    }
    if (heading.isLaidOut) scroller.post(scrollToHeading)
    else heading.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
        override fun onLayoutChange(
            v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or: Int, ob: Int
        ) {
            v.removeOnLayoutChangeListener(this)
            scroller.post(scrollToHeading)
        }
    })
}

internal fun MainActivity.fillHomeRow(rowIndex: Int, cards: List<View>) {
    val row = homeRows[rowIndex]
    val hadFocus = row.findFocus() != null
    row.removeAllViews()
    cards.forEach { card ->
        // Folder tiles are wider than posters and say so with their own width.
        val width = (card.getTag(R.id.tile_width) as? Int) ?: 190
        row.addView(card, LinearLayout.LayoutParams(width, -2).apply {
            setMargins(0, 0, 26, 0)
        })
    }
    pinRowEnds(row)
    lazyLoadHandler.removeCallbacks(lazyLoadPass)
    lazyLoadHandler.postDelayed(lazyLoadPass, 120)
    // Live data replacing placeholders, or a row the viewer was on before leaving.
    if (hadFocus || (pendingHomeRestore && homeFocusRow == rowIndex)) {
        pendingHomeRestore = false
        focusHomeRow(rowIndex, homeFocusIndex, animate = false)
    }
}

internal fun MainActivity.showHomeScreen() {
    currentScreen = "home"
    detailsReturnScreen = "home"
    actorPages.clear()
    // Watched on another device a while ago? Pick it up (at most every five minutes).
    val lastPull = maxOf(library.lastPull, trakt.lastPull)
    if (System.currentTimeMillis() - lastPull > 5 * 60_000 && lastPull > 0) syncLibrary()
    val kept = homeFrame
    if (!homeDirty && kept != null) {
        stopDiscoverFocusWatcher()
        (kept.parent as? ViewGroup)?.removeView(kept)
        setContentView(kept)
        restoreHomeFocus()
        return
    }
    homeRows.clear()
    homeRowHeadings.clear()
    homeRowItems.clear()
    // Rows are rebuilt below; the hero is re-featured from them (or from the poster
    // that regains focus), so it always matches what's on screen.
    heroItem = null

    val frame = FrameLayout(this).apply { setBackgroundColor(Ui.background) }
    val (backdrop, backdropImage) = Ui.backdrop(this, 760)
    heroBackdrop = backdropImage
    frame.addView(backdrop, FrameLayout.LayoutParams(-1, 760))

    // Clips each child (hero, rows) to its own area, so scrolled rows can't paint over
    // the hero; clipToPadding off lets the rows reach into the side gutters.
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipToPadding = false
        setPadding(Ui.gutter, 40, Ui.gutter, 0)
    }
    frame.addView(column, FrameLayout.LayoutParams(-1, -1))
    val tabs = addHeader("home", column)

    // Hero text: fixed height so the rows below never jump when it changes.
    val hero = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
    }
    column.addView(hero, LinearLayout.LayoutParams(1040, 460))
    heroOverline = Ui.text(this, Ui.Type.Overline, "", Ui.accent)
    hero.addView(heroOverline)
    heroLogo = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxWidth = 700
        maxHeight = 180
        visibility = View.GONE
    }
    hero.addView(heroLogo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 16; bottomMargin = 18 })
    heroTitle = Ui.text(this, Ui.Type.Display, "", maxLines = 2).apply { setPadding(0, 12, 0, 16) }
    hero.addView(heroTitle)
    heroChips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    hero.addView(heroChips)
    heroOverview = Ui.text(this, Ui.Type.Body, "", Ui.textSecondary, maxLines = 2).apply {
        setPadding(0, 16, 0, 24)
    }
    hero.addView(heroOverview, LinearLayout.LayoutParams(860, -2))

    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
    }
    val heroPlay = Ui.button(this, "▶  Play", primary = true) { heroItem?.let { playHeroItem(it) } }
    listOf(heroPlay).forEach {
        it.id = View.generateViewId()
        it.onFocusChangeListener = chainFocus(it.onFocusChangeListener) { _, focused ->
            if (focused && !pendingHomeRestore) {
                homeFocusRow = -1
                homeFocusRowName = null
            }
        }
        buttons.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 })
    }
    heroPlay.nextFocusLeftId = heroPlay.id
    heroPlay.nextFocusRightId = heroPlay.id
    hero.addView(buttons)
    homePlayButton = heroPlay
    pointTabsDownTo(tabs, heroPlay)

    // Rows scroll in the space under the hero.
    val rows = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(0, 0, 0, 60)
    }
    // Clipped, so rows scrolled up disappear under the hero instead of drawing over it.
    // Posters only zoom sideways past the row edges, which the side gutters allow for.
    homeRowsScroll = ScrollView(this).apply {
        isFocusable = false
        isVerticalScrollBarEnabled = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        addView(rows, ViewGroup.LayoutParams(-1, -2))
    }
    column.addView(homeRowsScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
        topMargin = 8
        // Let zoomed posters at the screen edges spill into the side gutters.
        leftMargin = -Ui.gutter
        rightMargin = -Ui.gutter
    })
    rows.setPadding(Ui.gutter, 0, Ui.gutter, 60)

    // Continue rows first (movies, then shows; each only when it has something), then
    // the popular rows.
    val continueEntries = progress.continueWatching(limit = 40)
    val continueMovies = continueEntries.filter { it.type == "movie" }.take(20)
    val continueShows = continueEntries.filter { it.type == "series" }.take(20)
    continueItems.clear()
    collectionItems.clear()
    val collections = savedCollections()
    val myList = library.myList()
    myListItems.clear()
    val rowLabels = listOfNotNull(
        "Continue movies".takeIf { continueMovies.isNotEmpty() },
        "Continue shows".takeIf { continueShows.isNotEmpty() },
        "My List".takeIf { myList.isNotEmpty() }
    ) + collections.map { "\u0000" + it.id } + listOf("Popular movies", "Popular series")
    rowLabels.forEachIndexed { index, label ->
        // Collection rows are keyed by id (prefixed so they can't clash with a title).
        val collection = collections.firstOrNull { "\u0000" + it.id == label }
        val heading = Ui.sectionTitle(this, collection?.title ?: label).apply { setPadding(0, 16, 0, 0) }
        rows.addView(heading)
        val (scroll, row) = makePosterRow()
        homeRows.add(row)
        homeRowHeadings.add(heading)
        homeRowItems.add(emptyList())
        addPosterRow(scroll, rows)
        when (label) {
            "Continue movies" -> fillContinueRow(index, continueMovies)
            "Continue shows" -> fillContinueRow(index, continueShows)
            "My List" -> fillMyListRow(index, myList)
            in collections.map { "\u0000" + it.id } -> fillCollectionRow(index, collection!!)
            "Popular movies" -> loadHomeRow(index, "movies", cachedMovies) { TmdbApi.fetchPopularMovies(it) }
            else -> loadHomeRow(index, "shows", cachedShows) { TmdbApi.fetchPopularTvShows(it) }
        }
    }

    // Scrolling anywhere on HOME (rows or the row list) checks for covers to load. The
    // observer is shared by the whole window, so it's attached and removed with HOME.
    // Set up before HOME goes on screen: when the window is already showing (after
    // "Who's watching?"), setContentView attaches it straight away.
    val scrollWatcher = android.view.ViewTreeObserver.OnScrollChangedListener {
        lazyLoadHandler.removeCallbacks(lazyLoadPass)
        lazyLoadHandler.postDelayed(lazyLoadPass, 120)
    }
    frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            v.viewTreeObserver.addOnScrollChangedListener(scrollWatcher)
            v.post { runLazyLoads() }
        }
        override fun onViewDetachedFromWindow(v: View) {
            v.viewTreeObserver.removeOnScrollChangedListener(scrollWatcher)
        }
    })
    stopDiscoverFocusWatcher()
    setContentView(frame)
    homeFrame = frame
    homeDirty = false
    restoreHomeFocus()
    if (heroItem == null) showHero(null)
    else showHero(heroItem, immediate = true)
}

internal fun MainActivity.restoreHomeFocus() {
    // Return to the poster the viewer left from; a row that is still loading
    // picks this up in fillHomeRow once its posters arrive.
    // Find the remembered row by name; a Continue row that has since emptied falls back
    // to the hero buttons.
    homeFocusRow = homeFocusRowName?.let { name ->
        homeRowHeadings.indexOfFirst { (it as? TextView)?.text?.toString() == name }
    } ?: -1
    val row = homeRows.getOrNull(homeFocusRow)
    if (row != null && row.childCount > 0) {
        focusHomeRow(homeFocusRow, homeFocusIndex, animate = false)
    } else {
        pendingHomeRestore = row != null
        homePlayButton.requestFocus()
    }
}

internal fun MainActivity.loadHomeRow(
    index: Int,
    kind: String,
    cached: List<TmdbApi.CatalogueItem>?,
    fetch: (String) -> List<TmdbApi.CatalogueItem>
) {
    if (cached != null) {
        fillHomeItems(index, cached)
        return
    }
    val targetRow = homeRows.getOrNull(index)
    thread {
        val items = try {
            fetch(BuildConfig.TMDB_API_KEY).filter { it.title.isNotBlank() && it.posterPath != null }
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (items == null) {
                if (currentScreen == "home" && heroItem == null) {
                    heroTitle.text = "Couldn't reach TMDB"
                    heroOverview.text = "Check the TV's internet connection. Discover still shows your addons' catalogs."
                }
                return@post
            }
            if (kind == "movies") cachedMovies = items else cachedShows = items
            // Fill even when away from HOME: the built HOME is kept and shown again.
            if (homeRows.getOrNull(index) === targetRow) fillHomeItems(index, items)
        }
    }
}

internal fun MainActivity.fillHomeItems(index: Int, items: List<TmdbApi.CatalogueItem>) {
    homeRowItems[index] = items
    fillHomeRow(index, items.map { item ->
        makePosterCard(item.title, item.releaseYear, item.posterPath, dimRow = false) { openHomeItem(item) }.also { card ->
            // The hero already shows the focused title, so no caption under the poster.
            card.getChildAt(1).visibility = View.INVISIBLE
            card.onFocusChangeListener = chainFocus(card.onFocusChangeListener) { _, focused ->
                if (focused) showHero(item)
            }
        }
    })
    // The first row's first title is featured until the viewer moves onto a poster.
    if (heroItem == null && index == 0) showHero(items.firstOrNull(), immediate = true)
}

// ---- Nuvio collections ----------------------------------------------------------------

internal fun MainActivity.fillContinueRow(index: Int, entries: List<WatchProgress.Entry>) {
    val items = entries.map { entry ->
        val left = ((entry.durationMs - entry.positionMs) / 60_000).coerceAtLeast(1)
        TmdbApi.CatalogueItem(
            id = entry.tvId,
            title = entry.title,
            overview = listOf(
                entry.episodeLabel,
                "Resume from ${WatchProgress.format(entry.positionMs)}",
                "$left min left"
            ).filter { it.isNotBlank() }.joinToString("  ·  "),
            posterPath = entry.poster.ifBlank { null },
            mediaType = if (entry.type == "series") "tv" else "movie",
            backdropPath = continueBackdrops[entry.title]?.ifBlank { null }
        ).also { continueItems[it] = entry }
    }
    // Progress doesn't store a backdrop; look missing ones up once and remember them.
    val missing = entries.map { it.title }.distinct().filter { !continueBackdrops.containsKey(it) }
    if (missing.isNotEmpty()) thread {
        missing.forEach { title ->
            val path = try {
                TmdbApi.fetchMetadata(title, BuildConfig.TMDB_API_KEY)?.backdropPath
            } catch (_: Exception) {
                null
            }
            continueBackdrops[title] = path.orEmpty()
        }
        Handler(Looper.getMainLooper()).post {
            if (currentScreen == "home" && heroItem != null && continueItems.containsKey(heroItem)) {
                continueBackdrops[heroItem!!.title]?.ifBlank { null }?.let {
                    heroBackdrop.alpha = 1f
                    loadPosterImage(heroBackdrop, it, "w780", targetWidth = 960, scrims = true)
                }
            }
        }
    }
    homeRowItems[index] = items
    fillHomeRow(index, items.map { item ->
        val entry = continueItems.getValue(item)
        makePosterCard(item.title, "", item.posterPath, dimRow = false) { openHomeItem(item) }.also { card ->
            card.getChildAt(1).visibility = View.INVISIBLE
            (card.getChildAt(0) as FrameLayout).addView(
                makeProgressBar(170, entry.fraction),
                FrameLayout.LayoutParams(170, 8, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = 12
                }
            )
            card.onFocusChangeListener = chainFocus(card.onFocusChangeListener) { _, focused ->
                if (focused) showHero(item)
            }
            // Hold OK: remove from the row, or mark as watched.
            card.isLongClickable = true
            card.setOnLongClickListener {
                showContinueOptions(entry, card)
                true
            }
        }
    })
    if (heroItem == null && index == 0) showHero(items.firstOrNull(), immediate = true)
}

internal fun MainActivity.showContinueOptions(entry: WatchProgress.Entry, card: View) {
    val title = entry.title
    val options = listOf(
        if (entry.type == "series") "Remove show from Continue watching" else "Remove from Continue watching",
        "Mark as watched",
        "Cancel"
    )
    android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(listOf(title, entry.episodeLabel).filter { it.isNotBlank() }.joinToString("  ·  "))
        .setItems(options.toTypedArray()) { _, which ->
            when (which) {
                0 -> {
                    progress.remove(entry)
                    library.dismiss(entry)
                    Trakt.Item.from(entry.id, entry.type)?.let { trakt.dismiss(it) }
                }
                1 -> {
                    progress.markWatched(entry)
                    progress.get(entry.id)?.let { library.pushProgress(it, force = true) }
                    Trakt.Item.from(entry.id, entry.type)?.let { trakt.markWatched(it) }
                }
                else -> return@setItems
            }
            removeContinueCard(card)
        }
        .show()
}

// Takes the card out of its row straight away (no full HOME rebuild); a row left
// empty is rebuilt away.
internal fun MainActivity.removeContinueCard(card: View) {
    val row = card.parent as? LinearLayout ?: return
    val index = row.indexOfChild(card)
    val rowIndex = homeRows.indexOf(row)
    row.removeView(card)
    pinRowEnds(row)
    if (rowIndex >= 0) homeRowItems[rowIndex] = homeRowItems[rowIndex].filterIndexed { i, _ -> i != index }
    if (row.childCount == 0) {
        homeFocusRowName = null
        homeDirty = true
        heroItem = null
        showHomeScreen()
        return
    }
    row.getChildAt(index.coerceAtMost(row.childCount - 1)).requestFocus()
}

// Picks up where the viewer left off: back to the streams for that movie or episode,
// where choosing a stream offers to resume.
internal fun MainActivity.resumeEntry(entry: WatchProgress.Entry) {
    lastFilmTitle = entry.title
    lastPosterFile = entry.poster
    val target = WatchTarget(
        entry.type, entry.title, entry.episodeLabel, entry.poster,
        entry.tvId, entry.season, entry.episode
    )
    if (entry.type == "series") {
        lastTvId = entry.tvId
        showStreams("series", "${entry.title}  ·  S${entry.season} E${entry.episode}", target) { entry.id }
    } else {
        lastFilmImdbId = entry.id
        showStreams("movie", entry.title, target) { entry.id }
    }
}

internal fun MainActivity.openHomeItem(item: TmdbApi.CatalogueItem) {
    collectionItems[item]?.let { (collection, folder) ->
        showFolderScreen(collection, folder)
        return
    }
    myListItems[item]?.let { saved ->
        openListItem(saved)
        return
    }
    val entry = continueItems[item]
    if (entry != null && entry.type == "movie") {
        showFilmDetails(item.title, item.posterPath.orEmpty(), imdbId = entry.id)
        return
    }
    if (item.mediaType == "tv") showTvDetails(item.id, item.title, item.posterPath.orEmpty())
    else showFilmDetails(item.title, item.posterPath.orEmpty(), tmdbId = item.id.takeIf { it > 0 })
}

// ---- My List ----

internal fun MainActivity.fillMyListRow(index: Int, list: List<StremioLibrary.ListItem>) {
    val items = list.map { saved ->
        TmdbApi.CatalogueItem(
            id = saved.tmdbId,
            title = saved.name,
            overview = "",
            posterPath = saved.poster.ifBlank { null },
            mediaType = if (saved.type == "series") "tv" else "movie",
            backdropPath = saved.background.ifBlank { null }
        ).also { myListItems[it] = saved }
    }
    homeRowItems[index] = items
    fillHomeRow(index, items.map { item ->
        makePosterCard(item.title, "", item.posterPath, dimRow = false) { openHomeItem(item) }
    })
}

// Opens a saved title's page; a show needs its TMDB id, looked up if it isn't known yet.
internal fun MainActivity.openListItem(saved: StremioLibrary.ListItem) {
    if (saved.type != "series") {
        showFilmDetails(saved.name, saved.poster, imdbId = saved.id, tmdbId = saved.tmdbId.takeIf { it > 0 })
        return
    }
    if (saved.tmdbId > 0) {
        showImdbIds[saved.tmdbId] = saved.id
        showTvDetails(saved.tmdbId, saved.name, saved.poster)
        return
    }
    thread {
        val tvId = try {
            TmdbApi.findTvIdByImdb(saved.id, BuildConfig.TMDB_API_KEY)
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (currentScreen != "home") return@post
            if (tvId != null) showTvDetails(tvId, saved.name, saved.poster)
            else android.widget.Toast.makeText(this, "Couldn't open ${saved.name}.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

// Play on the hero: a film goes straight to its streams; a series opens its episodes.
internal fun MainActivity.playHeroItem(item: TmdbApi.CatalogueItem) {
    collectionItems[item]?.let { (collection, folder) ->
        showFolderScreen(collection, folder)
        return
    }
    continueItems[item]?.let {
        resumeEntry(it)
        return
    }
    if (item.mediaType == "tv") {
        showTvDetails(item.id, item.title, item.posterPath.orEmpty())
        return
    }
    lastFilmTitle = item.title
    lastPosterFile = item.posterPath.orEmpty()
    lastFilmImdbId = null
    showStreams("movie", item.title, WatchTarget("movie", item.title, poster = item.posterPath.orEmpty())) {
        TmdbApi.fetchImdbId("movie", item.id, BuildConfig.TMDB_API_KEY)
    }
}

// Waits ~350 ms so moving quickly along a row doesn't load every backdrop on the way.
internal fun MainActivity.showHero(item: TmdbApi.CatalogueItem?, immediate: Boolean = false) {
    heroHandler.removeCallbacksAndMessages(null)
    val apply = {
        if (currentScreen == "home") {
            heroItem = item
            if (item == null) {
                heroOverline.text = "LOADING"
                heroTitle.text = ""
                heroOverview.text = ""
                heroChips.removeAllViews()
            } else {
                heroOverline.text = when {
                    continueItems.containsKey(item) -> "CONTINUE WATCHING"
                    collectionItems.containsKey(item) -> "COLLECTION  ·  ${collectionItems.getValue(item).first.title.uppercase()}"
                    item.mediaType == "tv" -> "SERIES"
                    else -> "MOVIE"
                }
                heroTitle.text = item.title
                heroTitle.visibility = View.VISIBLE
                heroLogo.visibility = View.GONE
                heroLogo.setImageDrawable(null)
                // TMDB title logo in place of the text, when one exists.
                val logoType = when (item.mediaType) { "tv" -> "tv"; "movie" -> "movie"; else -> null }
                if (tmdbSettings.enabled && tmdbSettings.logos && logoType != null && item.id > 0) thread {
                    val path = try {
                        TmdbMeta.details(logoType, item.id, BuildConfig.TMDB_API_KEY)?.logoPath
                    } catch (_: Exception) {
                        null
                    } ?: return@thread
                    Handler(Looper.getMainLooper()).post {
                        if (heroItem === item) showLogo(heroLogo, path) { if (heroItem === item) heroTitle.visibility = View.GONE }
                    }
                }
                heroOverview.text = item.overview
                heroChips.removeAllViews()
                listOfNotNull(
                    item.releaseYear.takeIf { it.isNotBlank() },
                    Ui.formatRating(item.rating)
                ).forEach { label ->
                    heroChips.addView(
                        Ui.chip(this, label, if (label.startsWith("★")) Ui.gold else Ui.textSecondary),
                        LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 }
                    )
                }
                (homePlayButton as TextView).text =
                    when {
                        continueItems.containsKey(item) -> "▶  Resume"
                        collectionItems.containsKey(item) -> "▶  Open"
                        item.mediaType == "tv" -> "▶  Episodes"
                        else -> "▶  Play"
                    }
                val image = heroBackdrop
                // Keep the previous backdrop until the new one arrives, rather than
                // flashing to black in between.
                if (item.backdropPath == null && continueBackdrops[item.title].isNullOrBlank()) image.setImageDrawable(null)
                // Continue items get their backdrop looked up after the row is built.
                (item.backdropPath ?: continueBackdrops[item.title]?.ifBlank { null })?.let { path ->
                    // 960 px is plenty under the scrims, and half the decode/upload work of
                    // 1280. Shown without a fade: a full-screen alpha animation is too much
                    // for this stick's GPU while the viewer is moving along a row.
                    image.alpha = 1f
                    loadPosterImage(image, path, "w780", targetWidth = 960, scrims = true)
                }
            }
        }
    }
    if (immediate) apply() else heroHandler.postDelayed({ apply() }, 350)
}

// Brings My List and progress up to date from the Stremio library, then Trakt. Home is
// rebuilt if anything on it changed (straight away when it's the screen showing).
internal fun MainActivity.syncLibrary() {
    if (!library.signedIn && !trakt.signedIn) return
    thread {
        val fromStremio = try {
            library.pull()
        } catch (_: Exception) {
            false
        }
        val fromTrakt = try {
            trakt.pull()
        } catch (_: Exception) {
            false
        }
        val changed = fromStremio || fromTrakt
        Handler(Looper.getMainLooper()).post {
            if (!changed) return@post
            homeDirty = true
            if (currentScreen == "home") showHomeScreen()
        }
    }
}

// Up/down on the home screen moves between rows and returns to the poster last used in each.
internal fun MainActivity.handleHomeKey(event: KeyEvent): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN) return false
    val up = event.keyCode == KeyEvent.KEYCODE_DPAD_UP
    if (!up && event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
    pendingHomeRestore = false

    val focused = currentFocus
    val rowIndex = homeRows.indexOfFirst { it === focused?.parent }
    if (rowIndex >= 0) {
        val target = if (up) rowIndex - 1 else rowIndex + 1
        if (target < 0) {
            homePlayButton.requestFocus()
            homeRowsScroll.smoothScrollTo(0, 0)
        } else if (target < homeRows.size && homeRows[target].childCount > 0) {
            focusHomeRow(target, homeRows[target].tag as? Int ?: 0)
        }
        return true
    }
    if (focused === homePlayButton && !up && homeRows.isNotEmpty()) {
        focusHomeRow(0, homeRows[0].tag as? Int ?: 0)
        return true
    }
    return false
}
