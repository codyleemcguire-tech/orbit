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

// Film and show pages, cast, actors' pages, trailers and "More like this".

// "+ My List" on a film or show page. imdbId finds the title's IMDb id (looked up
// off the main thread when the page doesn't know it yet).
internal fun MainActivity.addMyListButton(hero: DetailHero, type: String, title: String, poster: String, tmdbId: () -> Int, imdbId: () -> String?) {
    lateinit var button: TextView
    fun paint(id: String?) {
        button.text = if (library.inList(id)) "✓  My List" else "+  My List"
    }
    button = Ui.button(this, "+  My List") {
        val screen = currentScreen
        thread {
            val id = try {
                imdbId()
            } catch (_: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post {
                if (currentScreen != screen) return@post
                if (id == null) {
                    android.widget.Toast.makeText(this, "Couldn't find this title's id.", android.widget.Toast.LENGTH_SHORT).show()
                    return@post
                }
                val added = library.toggle(id, type, title, poster, tmdbId())
                trakt.setInWatchlist(id, type, added)
                homeDirty = true
                paint(id)
                android.widget.Toast.makeText(this,
                    if (added) "Added to My List" else "Removed from My List", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    hero.buttons.addView(button, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 20 })
    linkButtonRow(hero.buttons)
    // Show the right label once the id is known.
    val screen = currentScreen
    thread {
        val id = try { imdbId() } catch (_: Exception) { null }
        Handler(Looper.getMainLooper()).post { if (currentScreen == screen) paint(id) }
    }
}

internal fun MainActivity.backLabel() = when (detailsReturnScreen) {
    "discover" -> "‹   Discover"
    "search" -> "‹   Search"
    "collection" -> "‹   ${openFolder?.second?.title ?: "Collection"}"
    "actor" -> "‹   ${actorPages.lastOrNull()?.name ?: "Back"}"
    else -> "‹   Home"
}

internal fun MainActivity.leaveDetails() {
    when (detailsReturnScreen) {
        "discover" -> showDiscoverScreen()
        "search" -> showSearchScreen()
        "collection" -> openFolder?.let { showFolderScreen(it.first, it.second) } ?: showHomeScreen()
        "actor" -> actorPages.lastOrNull()?.let { showActorProfile(it) } ?: showHomeScreen()
        else -> showHomeScreen()
    }
}

// Finds the film's IMDb id through TMDB when it wasn't opened from an addon catalog.
internal fun MainActivity.resolveFilmImdbId(title: String): String? {
    val key = BuildConfig.TMDB_API_KEY
    val metadata = TmdbApi.fetchMetadata(title, key) ?: return null
    if (metadata.mediaType != "movie") return null
    return TmdbApi.fetchImdbId("movie", metadata.tmdbId, key)
}

internal fun MainActivity.addDetailHero(kind: String, title: String, height: Int = 800): DetailHero {
    val (frame, backdrop) = Ui.backdrop(this, height)
    root.addView(frame, LinearLayout.LayoutParams(-1, height).apply {
        leftMargin = -Ui.gutter
        rightMargin = -Ui.gutter
        topMargin = -36
    })
    val back = TextView(this).apply {
        text = backLabel()
        isFocusable = true
        isClickable = true
        setOnClickListener { leaveDetails() }
    }
    styleLinkFocus(back)
    frame.addView(back, FrameLayout.LayoutParams(-2, -2).apply {
        leftMargin = Ui.gutter - 22
        topMargin = 40
    })

    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.BOTTOM
        clipChildren = false
        clipToPadding = false
        setPadding(Ui.gutter, 0, Ui.gutter, 28)
    }
    frame.addView(column, FrameLayout.LayoutParams(1180, -1))
    val overline = Ui.text(this, Ui.Type.Overline, kind, Ui.accent)
    val heading = Ui.text(this, Ui.Type.Display, title, maxLines = 2).apply { setPadding(0, 12, 0, 18) }
    val chips = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val genres = Ui.text(this, Ui.Type.Label, "", Ui.textSecondary).apply { setPadding(0, 14, 0, 0) }
    val tagline = Ui.text(this, Ui.Type.BodyStrong, "", Ui.textPrimary, maxLines = 1).apply {
        setPadding(0, 22, 0, 0)
        visibility = View.GONE
    }
    val overview = Ui.text(this, Ui.Type.Body, "", Ui.textSecondary, maxLines = 4).apply {
        setPadding(0, 12, 0, 28)
    }
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
    }
    // TMDB title logo, shown in place of the title text once it has loaded.
    val logo = ImageView(this).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        maxWidth = 700
        maxHeight = 150
        visibility = View.GONE
    }
    column.addView(overline)
    column.addView(logo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 18; bottomMargin = 22 })
    listOf(heading, chips, genres, tagline).forEach { column.addView(it) }
    column.addView(overview, LinearLayout.LayoutParams(1000, -2))
    column.addView(buttons)
    return DetailHero(back, backdrop, overline, heading, chips, genres, tagline, overview, buttons, logo)
}

// Fills a detail header from TMDB details once they arrive.
// The richer TMDB details: logo, status, studios and director on top of the basics.
internal fun MainActivity.fillDetailHero(hero: DetailHero, details: TmdbMeta.Details, extraChips: List<String> = emptyList()) {
    fillDetailHero(hero, details.metadata, listOfNotNull(details.status.ifBlank { null }) + extraChips)
    val credits = listOfNotNull(
        details.director?.let { if (details.mediaType == "tv") "Created by $it" else "Directed by $it" },
        details.studios.joinToString(", ").ifBlank { null }
    ).joinToString("  ·  ")
    // Credits on their own line under the genres.
    if (credits.isNotBlank()) hero.genres.text = listOf(hero.genres.text.toString(), credits).filter { it.isNotBlank() }.joinToString("\n")
    hero.genres.setLineSpacing(10f, 1f)
    details.logoPath?.takeIf { tmdbSettings.logos }?.let { path ->
        showLogo(hero.logo, path) { hero.title.visibility = View.GONE }
    }
}

internal fun MainActivity.showLogo(view: ImageView, path: String, onShown: () -> Unit) {
    view.setImageDrawable(null)
    loadPosterImage(view, path, "w500", targetWidth = 760, keepAlpha = true)
    view.post(object : Runnable {
        var tries = 0
        override fun run() {
            if (view.drawable != null) {
                view.visibility = View.VISIBLE
                onShown()
            } else if (tries++ < 40) view.postDelayed(this, 100)
        }
    })
}

// Finds TMDB's details for a title: by TMDB id, else IMDb id, else (last resort) name.
internal fun MainActivity.tmdbDetailsFor(mediaType: String, title: String, tmdbId: Int?, imdbId: String?): TmdbMeta.Details? {
    val key = BuildConfig.TMDB_API_KEY
    val ref = tmdbId?.let { mediaType to it }
        ?: imdbId?.let { TmdbMeta.resolve(it, mediaType, key) }
        ?: TmdbMeta.search(title, mediaType, key)
        ?: return null
    return TmdbMeta.details(ref.first, ref.second, key)
}

// "Trailer" beside Play: the TMDB trailer, opened in the TV's YouTube app.
internal fun MainActivity.addTrailerButton(hero: DetailHero, youtubeKey: String?) {
    youtubeKey ?: return
    lateinit var button: TextView
    button = Ui.button(this, "▶  Trailer") { playTrailer(youtubeKey, button) }
    hero.buttons.addView(button, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 20 })
    linkButtonRow(hero.buttons)
}

// Left/right move along the page's buttons and stop at the ends, rather than jumping
// down to whatever lies below (the seasons, the cast).
internal fun MainActivity.linkButtonRow(row: LinearLayout) {
    val buttons = (0 until row.childCount).map { row.getChildAt(it) }
    buttons.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
    buttons.forEachIndexed { i, b ->
        b.nextFocusLeftId = buttons.getOrNull(i - 1)?.id ?: b.id
        b.nextFocusRightId = buttons.getOrNull(i + 1)?.id ?: b.id
    }
}

// Plays the trailer in Orbit's player; Back returns to the page.
internal fun MainActivity.playTrailer(youtubeKey: String, button: TextView) {
    val screen = currentScreen
    val title = lastFilmTitle
    button.text = "Loading trailer..."
    thread {
        val url = try {
            YouTubeTrailer.resolve(youtubeKey)
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            button.text = "▶  Trailer"
            if (currentScreen != screen || lastFilmTitle != title) return@post
            if (url != null) {
                playingStream = null
                startPlayback(url, emptyMap())
            } else openTrailer(youtubeKey)
        }
    }
}

// Fallback when YouTube won't give Orbit the video: hand it to a YouTube app.
internal fun MainActivity.openTrailer(youtubeKey: String) {
    val intent = android.content.Intent(
        android.content.Intent.ACTION_VIEW,
        android.net.Uri.parse("https://www.youtube.com/watch?v=$youtubeKey")
    )
    // A YouTube app rather than whatever else claims YouTube links (Downloader, browsers).
    val app = youtubeApps.firstOrNull { pkg ->
        packageManager.queryIntentActivities(android.content.Intent(intent).setPackage(pkg), 0).isNotEmpty()
    }
    try {
        startActivity(if (app != null) intent.setPackage(app) else android.content.Intent.createChooser(intent, "Play trailer with"))
    } catch (_: Exception) {
        android.widget.Toast.makeText(this, "No app on this TV can play YouTube trailers.", android.widget.Toast.LENGTH_LONG).show()
    }
}

// "More like this": TMDB recommendations, opening their own pages.
internal fun MainActivity.addMoreLikeThis(items: List<TmdbApi.CatalogueItem>) {
    if (items.isEmpty() || !tmdbSettings.moreLikeThis) return
    root.addView(Ui.sectionTitle(this, "More like this"))
    val (scroll, row) = makePosterRow()
    items.take(20).forEach { item ->
        row.addView(makePosterCard(item.title, item.releaseYear, item.posterPath, dimRow = false) {
            if (item.mediaType == "tv") showTvDetails(item.id, item.title, item.posterPath.orEmpty())
            else showFilmDetails(item.title, item.posterPath.orEmpty(), tmdbId = item.id)
        }, LinearLayout.LayoutParams(190, -2).apply { setMargins(0, 0, 26, 0) })
    }
    pinRowEnds(row)
    addPosterRow(scroll)
}

internal fun MainActivity.fillDetailHero(hero: DetailHero, live: TmdbApi.FilmMetadata, extraChips: List<String> = emptyList()) {
    hero.chips.removeAllViews()
    (listOfNotNull(
        live.releaseYear.takeIf { it.isNotBlank() },
        Ui.formatRuntime(live.runtimeMinutes),
        Ui.formatRating(live.rating)
    ) + extraChips).forEach { label ->
        hero.chips.addView(
            Ui.chip(this, label, if (label.startsWith("★")) Ui.gold else Ui.textSecondary),
            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 }
        )
    }
    hero.genres.text = live.genres.joinToString("  ·  ")
    if (live.tagline.isNotBlank()) {
        hero.tagline.text = live.tagline
        hero.tagline.visibility = View.VISIBLE
    }
    if (live.overview.isNotBlank()) hero.overview.text = live.overview
    live.backdropPath?.let { path ->
        hero.backdrop.alpha = 1f
        loadPosterImage(hero.backdrop, path, "w780", targetWidth = 960, scrims = true)
    }
}

internal fun MainActivity.showFilmDetails(
    title: String,
    posterFile: String,
    focusCastIndex: Int = -1,
    imdbId: String? = null,
    tmdbId: Int? = null
) {
    useContentScroll()
    // Keep known ids while returning to the same film (from cast, streams, player).
    if (title != lastFilmTitle) lastFilmTmdbId = null
    if (tmdbId != null) lastFilmTmdbId = tmdbId
    if (imdbId != null) lastFilmImdbId = imdbId
    else if (title != lastFilmTitle) lastFilmImdbId = null
    // Start finding streams now, so they're usually ready when Play is pressed.
    lastFilmImdbId?.let { StreamSearch.prefetch("movie", it) }
    // Look the IMDb id up now, so Play doesn't have to wait for it.
    if (lastFilmImdbId == null) {
        thread {
            val found = try {
                resolveFilmImdbId(title)
            } catch (_: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post {
                if (found != null && lastFilmTitle == title && lastFilmImdbId == null) {
                    lastFilmImdbId = found
                    StreamSearch.prefetch("movie", found)
                }
            }
        }
    }
    currentScreen = "details"
    lastFilmTitle = title
    lastPosterFile = posterFile
    root.removeAllViews()
    contentScroll.scrollTo(0, 0)

    val hero = addDetailHero("MOVIE", title, height = 820)
    hero.overview.text = "Loading details..."
    val playButton = Ui.button(this, "▶  Play", primary = true) {
        showStreams("movie", title, WatchTarget("movie", title, poster = posterFile)) {
            lastFilmImdbId ?: resolveFilmImdbId(title)?.also { lastFilmImdbId = it }
        }
    }
    hero.buttons.addView(playButton)
    linkButtonRow(hero.buttons)
    addMyListButton(hero, "movie", title, posterFile, { lastFilmTmdbId ?: 0 }) {
        lastFilmImdbId?.takeIf { lastFilmTitle == title }
            ?: lastFilmTmdbId?.let { TmdbApi.fetchImdbId("movie", it, BuildConfig.TMDB_API_KEY) }
            ?: resolveFilmImdbId(title)
    }
    // Play becomes Resume once the film's id (and so its saved progress) is known.
    val showResume = {
        lastFilmImdbId?.let { progress.get(it) }?.takeIf { it.resumable }?.let {
            playButton.text = "▶  Resume  ${WatchProgress.format(it.positionMs)}"
        }
    }
    showResume()
    if (lastFilmImdbId == null) playButton.postDelayed({ if (lastFilmTitle == title) showResume() }, 2500)

    val knownTmdb = lastFilmTmdbId
    val knownImdb = lastFilmImdbId
    val enrich = tmdbSettings.enabled
    // One TMDB lookup feeds the header, the cast row and More like this.
    val details = if (enrich) streamLoader.submit<TmdbMeta.Details?> {
        try {
            tmdbDetailsFor("movie", title, knownTmdb, knownImdb)
        } catch (_: Exception) {
            null
        }
    } else null
    addCastSection(title, focusCastIndex, source = details?.let { f -> { f.get()?.cast } })

    thread {
        val rich = try {
            details?.get()
        } catch (_: Exception) {
            null
        }
        val live = if (rich != null) null else try {
            TmdbApi.fetchMetadata(title, BuildConfig.TMDB_API_KEY)
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (currentScreen != "details" || lastFilmTitle != title) return@post
            when {
                rich != null -> {
                    fillDetailHero(hero, rich)
                    addTrailerButton(hero, rich.trailerKey)
                    // TMDB's exact ids also make Play find the right streams.
                    if (lastFilmImdbId == null) rich.imdbId?.let {
                        lastFilmImdbId = it
                        StreamSearch.prefetch("movie", it)
                        showResume()
                    }
                    lastFilmTmdbId = rich.tmdbId
                    addMoreLikeThis(rich.recommendations)
                }
                live != null -> fillDetailHero(hero, live)
                else -> hero.overview.text = "Details aren't available for this title."
            }
        }
    }

    // Returning from an actor page focuses that cast member once the cast loads.
    if (focusCastIndex < 0) playButton.requestFocus() else hero.back.requestFocus()
}

internal fun MainActivity.showTvDetails(tvId: Int, title: String, posterPath: String, focusCastIndex: Int = -1) {
    useContentScroll()
    if (!showImdbIds.containsKey(tvId)) {
        thread {
            try {
                TmdbApi.fetchImdbId("tv", tvId, BuildConfig.TMDB_API_KEY)
                    ?.let { showImdbIds[tvId] = it }
            } catch (_: Exception) {
            }
        }
    }
    lastTvId = tvId
    currentScreen = "tv_details"
    lastFilmTitle = title
    lastPosterFile = posterPath
    root.removeAllViews()
    root.gravity = Gravity.TOP
    contentScroll.scrollTo(0, 0)

    val hero = addDetailHero("SERIES", title, height = 820)
    hero.overview.maxLines = 3
    hero.overview.text = "Loading details..."
    // Plays the first episode of the selected season (set once episodes load).
    var playFirst: (() -> Unit)? = null
    val playButton = Ui.button(this, "▶  Play", primary = true) { playFirst?.invoke() }
    hero.buttons.addView(playButton)
    addMyListButton(hero, "series", title, posterPath, { tvId }) {
        showImdbIds[tvId] ?: TmdbApi.fetchImdbId("tv", tvId, BuildConfig.TMDB_API_KEY)?.also { showImdbIds[tvId] = it }
    }
    val backButton = hero.back

    root.addView(Ui.sectionTitle(this, "Seasons").apply { setPadding(0, 8, 0, 0) })
    val seasonRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(Ui.gutter, 18, Ui.gutter, 6)
    }
    val seasonScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
    }
    seasonScroll.addView(seasonRow)
    root.addView(seasonScroll, LinearLayout.LayoutParams(-1, -2).apply {
        leftMargin = -Ui.gutter
        rightMargin = -Ui.gutter
    })

    root.addView(Ui.sectionTitle(this, "Episodes"))
    val episodeList = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = "orbit_episode_list"
        setPadding(0, 18, 0, 0)
    }
    root.addView(episodeList)

    val status = Ui.text(this, Ui.Type.Label, "Loading seasons...", Ui.textMuted).apply {
        setPadding(0, 8, 0, 0)
    }
    root.addView(status)

    // Series cast (everyone across all seasons), by TMDB id, below the episodes.
    val castRow = addCastSection(title, focusCastIndex, screen = "tv_details") {
        TmdbMeta.details("tv", tvId, BuildConfig.TMDB_API_KEY)?.cast
    }

    var selectedSeasonNumber = -1
    val seasonButtons = mutableMapOf<Int, TextView>()

    fun loadSeason(seasonNumber: Int) {
        selectedSeasonNumber = seasonNumber
        seasonByShow[tvId] = seasonNumber
        selectedSeasonButtonId = seasonButtons[seasonNumber]?.id ?: View.NO_ID
        seasonButtons.forEach { (number, seasonButton) ->
            Ui.paintPill(seasonButton, selected = number == selectedSeasonNumber)
        }
        status.text = "Loading episodes..."
        status.visibility = View.VISIBLE
        episodeList.removeAllViews()

        thread {
            try {
                val episodes = TmdbApi.fetchTvEpisodes(
                    tvId, seasonNumber, BuildConfig.TMDB_API_KEY
                )
                Handler(Looper.getMainLooper()).post {
                    if (currentScreen != "tv_details" || lastFilmTitle != title) return@post
                    episodeList.removeAllViews()
                    status.text = if (episodes.isEmpty()) "No episodes found for this season." else ""
                    status.visibility = if (episodes.isEmpty()) View.VISIBLE else View.GONE

                    val episodeCards = mutableListOf<LinearLayout>()
                    val watchedEpisodes = progress.forShow(tvId)
                    episodes.forEachIndexed { index, episode ->
                        val card = makeEpisodeCard(episode, watchedEpisodes[seasonNumber to episode.episodeNumber]) {
                            episodeFocusIndex = index
                            val label = "$title  ·  S${seasonNumber} E${episode.episodeNumber}"
                            val target = WatchTarget(
                                "series", title,
                                "S$seasonNumber · E${episode.episodeNumber}  ${episode.name}",
                                posterPath, tvId, seasonNumber, episode.episodeNumber
                            )
                            showStreams("series", label, target) {
                                val showImdb = showImdbIds[tvId]
                                    ?: TmdbApi.fetchImdbId(
                                        "tv", tvId, BuildConfig.TMDB_API_KEY
                                    )?.also { showImdbIds[tvId] = it }
                                showImdb?.let { "$it:$seasonNumber:${episode.episodeNumber}" }
                            }
                        }
                        card.nextFocusUpId = seasonButtons[selectedSeasonNumber]?.id ?: View.NO_ID
                        episodeList.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                            bottomMargin = 14
                        })
                        episodeCards.add(card)
                    }

                    episodes.firstOrNull()?.let { first ->
                        playButton.text = "▶  Play  S$seasonNumber · E${first.episodeNumber}"
                        playFirst = { episodeCards.first().performClick() }
                    }

                    val selectedSeasonButton = seasonButtons[seasonNumber]
                    if (episodeCards.isNotEmpty() && selectedSeasonButton != null) {
                        selectedSeasonButton.nextFocusDownId = episodeCards.first().id
                        episodeCards.forEachIndexed { index, card ->
                            // Up goes to the episode above; only the first goes back to the seasons.
                            card.nextFocusUpId =
                                if (index > 0) episodeCards[index - 1].id else selectedSeasonButton.id
                            // The last episode leads on down to Cast and More like this.
                            card.nextFocusDownId =
                                if (index < episodeCards.lastIndex) episodeCards[index + 1].id
                                else castRow.getChildAt(0)?.id ?: View.NO_ID
                        }
                    }

                    // Back from the player lands on the episode that was played.
                    if (episodeFocusIndex in episodeCards.indices) {
                        episodeCards[episodeFocusIndex].requestFocus()
                    } else currentFocus?.let { focused ->
                        // Episodes arriving above push a focused cast member down: keep it in view.
                        focused.post {
                            val rect = android.graphics.Rect()
                            focused.getDrawingRect(rect)
                            focused.requestRectangleOnScreen(rect, true)
                        }
                    }
                    episodeFocusIndex = -1
                }
            } catch (_: Exception) {
                Handler(Looper.getMainLooper()).post {
                    if (currentScreen == "tv_details" && lastFilmTitle == title) {
                        status.text = "Couldn't load episodes. Please try again."
                        status.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    thread {
        try {
            val key = BuildConfig.TMDB_API_KEY
            // By TMDB id when enrichment is on (exact), by name otherwise.
            val rich = if (tmdbSettings.enabled) try {
                TmdbMeta.details("tv", tvId, key)
            } catch (_: Exception) {
                null
            } else null
            val details = rich?.metadata ?: TmdbApi.fetchMetadata(title, key)
            val seasons = TmdbApi.fetchTvSeasons(tvId, key)

            Handler(Looper.getMainLooper()).post {
                if (currentScreen != "tv_details" || lastFilmTitle != title) return@post

                val regular = seasons.count { it.seasonNumber >= 1 }
                val seasonChip = listOfNotNull(regular.takeIf { it > 0 }?.let { if (it == 1) "1 season" else "$it seasons" })
                if (rich != null) {
                    fillDetailHero(hero, rich, seasonChip)
                    addTrailerButton(hero, rich.trailerKey)
                    rich.imdbId?.let { showImdbIds[tvId] = it }
                    addMoreLikeThis(rich.recommendations)
                } else if (details != null) {
                    fillDetailHero(hero, details, seasonChip)
                } else {
                    hero.overview.text = ""
                }

                seasonRow.removeAllViews()
                if (seasons.isEmpty()) {
                    status.text = "No seasons found."
                } else {
                    status.text = ""
                    val createdSeasonButtons = mutableListOf<TextView>()
                    seasons.forEach { season ->
                        val label = if (season.seasonNumber == 0) "Specials" else "Season ${season.seasonNumber}"
                        val button = Ui.pill(this, label) { loadSeason(season.seasonNumber) }.apply {
                            id = View.generateViewId()
                        }
                        button.onFocusChangeListener = chainFocus(button.onFocusChangeListener) { view, hasFocus ->
                            if (hasFocus) seasonScroll.post {
                                seasonScroll.smoothScrollTo((view.left - Ui.gutter).coerceAtLeast(0), 0)
                            }
                        }
                        seasonRow.addView(button, LinearLayout.LayoutParams(-2, -2).apply {
                            rightMargin = 12
                        })
                        seasonButtons[season.seasonNumber] = button
                        createdSeasonButtons.add(button)
                    }

                    createdSeasonButtons.forEachIndexed { index, button ->
                        button.nextFocusLeftId =
                            if (index > 0) createdSeasonButtons[index - 1].id else button.id
                        button.nextFocusRightId =
                            if (index < createdSeasonButtons.lastIndex) createdSeasonButtons[index + 1].id
                            else button.id
                        button.nextFocusUpId = playButton.id
                    }

                    // This show's last season, else Season 1 rather than Specials.
                    val seasonToRestore = seasons.firstOrNull {
                        it.seasonNumber == seasonByShow[tvId]
                    } ?: seasons.firstOrNull { it.seasonNumber >= 1 } ?: seasons.first()
                    selectedSeasonButtonId =
                        seasonButtons[seasonToRestore.seasonNumber]?.id ?: View.NO_ID
                    playButton.nextFocusDownId = selectedSeasonButtonId
                    loadSeason(seasonToRestore.seasonNumber)
                    seasonButtons[seasonToRestore.seasonNumber]?.let { selected ->
                        seasonScroll.post {
                            seasonScroll.scrollTo((selected.left - Ui.gutter).coerceAtLeast(0), 0)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            Handler(Looper.getMainLooper()).post {
                if (currentScreen == "tv_details" && lastFilmTitle == title) {
                    status.text = "Couldn't load show information."
                }
            }
        }
    }

    playButton.id = View.generateViewId()
    linkButtonRow(hero.buttons)
    if (episodeFocusIndex < 0 && focusCastIndex < 0) playButton.requestFocus() else backButton.requestFocus()
}

// Episode card: thumbnail on the left; number, runtime, title and synopsis beside it.
internal fun MainActivity.makeEpisodeCard(
    episode: TmdbApi.TvEpisode,
    watch: WatchProgress.Entry?,
    onClick: () -> Unit
): LinearLayout {
    val still = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = Ui.rounded(Ui.surfaceRaised, 10f)
    }
    episode.stillPath?.let { loadPosterImage(still, it, "w300", targetWidth = 300, cornerRadius = 10f) }
    // Thumbnail with a progress bar for a part-watched episode.
    val stillFrame = FrameLayout(this).apply {
        addView(still, FrameLayout.LayoutParams(-1, -1))
        if (watch != null && watch.resumable) {
            addView(makeProgressBar(256, watch.fraction),
                FrameLayout.LayoutParams(256, 8, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = 12
                })
        }
    }

    val text = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(28, 4, 12, 0)
    }
    val meta = listOfNotNull(
        "Episode ${episode.episodeNumber}",
        Ui.formatRuntime(episode.runtimeMinutes),
        episode.airDate.takeIf { it.length >= 4 }?.take(4)
    ).joinToString("  ·  ")
    val metaRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    metaRow.addView(Ui.text(this, Ui.Type.Caption, meta.uppercase(), Ui.textMuted))
    when {
        watch?.watched == true -> metaRow.addView(Ui.chip(this, "✓ Watched", Ui.accent),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 16 })
        watch?.resumable == true -> metaRow.addView(
            Ui.chip(this, "${((watch.durationMs - watch.positionMs) / 60_000).coerceAtLeast(1)}m left", Ui.textSecondary),
            LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 16 })
    }
    text.addView(metaRow)
    val name = Ui.text(this, Ui.Type.BodyStrong, episode.name, Ui.textPrimary, maxLines = 1).apply {
        textSize = 17f
        setPadding(0, 10, 0, 8)
    }
    text.addView(name)
    if (episode.overview.isNotBlank()) {
        text.addView(Ui.text(this, Ui.Type.Body, episode.overview, Ui.textSecondary, maxLines = 2).apply {
            textSize = 14f
        })
    }

    return LinearLayout(this).apply {
        id = View.generateViewId()
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16, 16, 16, 16)
        isFocusable = true
        isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        background = Ui.rounded(Ui.surface, Ui.radius)
        addView(stillFrame, LinearLayout.LayoutParams(288, 162))
        addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        setOnFocusChangeListener { view, focused ->
            view.background = Ui.rounded(
                if (focused) Ui.surfaceRaised else Ui.surface, Ui.radius,
                stroke = if (focused) 4 else 0, strokeColor = Color.WHITE
            )
        }
        setOnClickListener { onClick() }
    }
}

internal fun MainActivity.addCastSection(
    title: String,
    focusCastIndex: Int = -1,
    screen: String = "details",
    source: (() -> List<TmdbApi.CastMember>?)? = null
): LinearLayout {
    root.addView(Ui.sectionTitle(this, "Cast"))
    val loading = Ui.text(this, Ui.Type.Label, "Loading cast...", Ui.textMuted).apply {
        setPadding(0, 16, 0, 0)
    }
    root.addView(loading)

    val castScroll = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        visibility = View.GONE
    }
    val castRow = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(Ui.gutter, 24, Ui.gutter, 12)
    }
    castScroll.addView(castRow)
    root.addView(castScroll, LinearLayout.LayoutParams(-1, -2).apply {
        leftMargin = -Ui.gutter
        rightMargin = -Ui.gutter
    })

    thread {
        val cast = try {
            source?.invoke()?.takeIf { it.isNotEmpty() }
                ?: if (screen == "details") TmdbApi.fetchCast(title, BuildConfig.TMDB_API_KEY) else emptyList()
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (currentScreen != screen || lastFilmTitle != title) return@post
            if (cast.isNullOrEmpty()) {
                loading.text = if (cast == null) "Cast unavailable. Check your connection."
                else "No cast information found."
                return@post
            }
            loading.visibility = View.GONE
            castScroll.visibility = View.VISIBLE
            cast.take(15).forEachIndexed { index, member ->
                // Round photo with a ring on focus, name and role underneath.
                val photoFrame = FrameLayout(this).apply {
                    background = Ui.rounded(Ui.surface, 1000f)
                }
                val photo = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = member.name
                }
                photoFrame.addView(photo, FrameLayout.LayoutParams(-1, -1))
                if (member.profilePath != null) {
                    loadPosterImage(photo, member.profilePath, "w185", targetWidth = 160, circle = true)
                } else {
                    photoFrame.addView(
                        Ui.text(this, Ui.Type.Title, member.name.take(1), Ui.textMuted).apply {
                            gravity = Gravity.CENTER
                        },
                        FrameLayout.LayoutParams(-1, -1)
                    )
                }
                val name = Ui.text(this, Ui.Type.Label, member.name, Ui.textPrimary, maxLines = 1).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, 14, 0, 4)
                }
                val role = Ui.text(this, Ui.Type.Caption, member.character, Ui.textMuted, maxLines = 2).apply {
                    gravity = Gravity.CENTER
                }
                val card = LinearLayout(this).apply {
                    id = View.generateViewId()
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    clipChildren = false
                    isFocusable = true
                    isClickable = true
                    if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                    addView(photoFrame, LinearLayout.LayoutParams(150, 150))
                    addView(name, LinearLayout.LayoutParams(180, -2))
                    addView(role, LinearLayout.LayoutParams(180, -2))
                    setOnFocusChangeListener { _, focused ->
                        photoFrame.foreground = if (focused) Ui.rounded(Color.TRANSPARENT, 1000f, 5, Color.WHITE) else null
                        photoFrame.animate().scaleX(if (focused) 1.1f else 1f)
                            .scaleY(if (focused) 1.1f else 1f).setDuration(150).start()
                        name.setTextColor(if (focused) Color.WHITE else Ui.textPrimary)
                    }
                    setOnClickListener {
                        openActor(member, index, fromShow = screen == "tv_details")
                    }
                }
                castRow.addView(card, LinearLayout.LayoutParams(180, -2).apply { rightMargin = 22 })
            }
            pinRowEnds(castRow)
            // Down from the last episode goes to the first cast member.
            root.findViewWithTag<LinearLayout>("orbit_episode_list")?.let { list ->
                list.getChildAt(list.childCount - 1)?.nextFocusDownId = castRow.getChildAt(0).id
            }
            if (focusCastIndex in 0 until castRow.childCount) {
                castRow.getChildAt(focusCastIndex).requestFocus()
            }
        }
    }
    return castRow
}

internal fun MainActivity.openActor(member: TmdbApi.CastMember, index: Int, fromShow: Boolean) {
    val page = ActorPage(
        member.name, member.id, member.profilePath, lastFilmTitle, lastPosterFile, fromShow, lastTvId,
        lastFilmTmdbId, lastFilmImdbId, index, detailsReturnScreen
    )
    actorPages.addLast(page)
    showActorProfile(page)
}

internal fun MainActivity.leaveActor() {
    val page = actorPages.removeLastOrNull() ?: return showHomeScreen()
    detailsReturnScreen = page.previousReturn
    if (page.fromShow) showTvDetails(page.fromTvId, page.fromTitle, page.fromPoster, page.castIndex)
    else showFilmDetails(page.fromTitle, page.fromPoster, page.castIndex, page.fromImdbId, page.fromTmdbId)
}

internal fun MainActivity.showActorProfile(page: ActorPage) {
    useContentScroll()
    currentScreen = "actor"
    root.removeAllViews()
    root.gravity = Gravity.TOP
    contentScroll.scrollTo(0, 0)

    val backButton = TextView(this).apply {
        text = "‹   ${page.fromTitle}"
        isFocusable = true
        isClickable = true
        setOnClickListener { leaveActor() }
    }
    styleLinkFocus(backButton)
    root.addView(backButton, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = -22 })

    // Portrait on the left; name, birth and biography beside it; their films and shows below.
    val layout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 36, 0, 0)
    }
    root.addView(layout)
    val portrait = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        contentDescription = page.name
        background = Ui.rounded(Ui.surface, 20f)
        clipToOutline = true
    }
    layout.addView(portrait, LinearLayout.LayoutParams(260, 390))

    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(56, 0, 0, 0)
    }
    layout.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
    column.addView(Ui.text(this, Ui.Type.Overline, "CAST  ·  ${page.fromTitle}".uppercase(), Ui.accent, maxLines = 1))
    column.addView(Ui.text(this, Ui.Type.Display, page.name, maxLines = 2).apply { setPadding(0, 12, 0, 10) })
    val born = Ui.text(this, Ui.Type.Label, "", Ui.textMuted, maxLines = 1).apply {
        setPadding(0, 0, 0, 18)
        visibility = View.GONE
    }
    column.addView(born)
    val biography = Ui.text(this, Ui.Type.Body, "Loading...", Ui.textSecondary, maxLines = 7)
    column.addView(biography)

    page.profilePath?.let { loadPosterImage(portrait, it, "w342", targetWidth = 260, cornerRadius = 20f) }

    thread {
        val person = try {
            TmdbMeta.person(page.personId, page.name, BuildConfig.TMDB_API_KEY)
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (currentScreen != "actor" || actorPages.lastOrNull() !== page) return@post
            if (person == null) {
                biography.text = "Details for ${page.name} aren't available."
                return@post
            }
            if (page.profilePath == null) person.profilePath?.let {
                loadPosterImage(portrait, it, "w342", targetWidth = 260, cornerRadius = 20f)
            }
            if (person.born.isNotBlank()) {
                born.text = person.born
                born.visibility = View.VISIBLE
            }
            biography.text = person.biography.ifBlank { "No biography is available." }
            // One poster row each for films and shows; a title opens its own page.
            val rows = listOf("Movies" to person.movies, "TV shows" to person.shows).filter { it.second.isNotEmpty() }
            rows.forEachIndexed { rowIndex, (heading, items) ->
                root.addView(Ui.sectionTitle(this, "$heading  ·  ${items.size}"))
                val (scroll, row) = makePosterRow()
                items.forEachIndexed { index, item ->
                    row.addView(makePosterCard(item.title, item.releaseYear, item.posterPath, dimRow = false) {
                        page.focusRow = rowIndex
                        page.focusIndex = index
                        detailsReturnScreen = "actor"
                        if (item.mediaType == "tv") showTvDetails(item.id, item.title, item.posterPath.orEmpty())
                        else showFilmDetails(item.title, item.posterPath.orEmpty(), tmdbId = item.id)
                    }, LinearLayout.LayoutParams(190, -2).apply { setMargins(0, 0, 26, 0) })
                }
                pinRowEnds(row)
                addPosterRow(scroll)
                if (rowIndex == page.focusRow) row.getChildAt(page.focusIndex)?.let { card ->
                    card.requestFocus()
                    scroll.post { scroll.scrollTo((card.left - Ui.gutter).coerceAtLeast(0), 0) }
                }
            }
            if (rows.isEmpty()) root.addView(Ui.text(this, Ui.Type.Body, "No films or shows found.", Ui.textMuted).apply {
                setPadding(0, 40, 0, 0)
            })
        }
    }
    backButton.requestFocus()
}

// ---- Profiles ----

internal fun MainActivity.detailsTypeIsTv() = playerReturnScreen == "tv_details"
