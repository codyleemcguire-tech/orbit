package com.orbit.player

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.orbit.player.MainActivity.WatchTarget
import kotlin.concurrent.thread

// What the player's side panels (SidePanel) list: sources for what's playing (or another
// episode), the show's episodes by season, and the subtitle and audio menus. The video keeps
// playing behind them; Back closes a panel (or returns to the one it was opened from).

internal fun MainActivity.openSidePanel(back: (() -> Unit)? = null) {
    playerControls.hide()
    playerAction.visibility = android.view.View.GONE
    stopPanelSearch()
    sidePanel.onBack = back
}

internal fun MainActivity.closeSidePanel() {
    stopPanelSearch()
    sidePanel.hide()
    playerView.requestFocus()
}

private fun MainActivity.stopPanelSearch() {
    panelListener?.let { l -> panelSession?.stop(l) }
    panelListener = null
    panelSession = null
}

// Sources for an id (the playing title, or an episode picked in the Episodes panel). Choosing
// one switches to it: at the same point for the same title, else where it was left off.
internal fun MainActivity.showSourcesPanel(type: String, id: String, target: WatchTarget, back: (() -> Unit)? = null) {
    openSidePanel(back)
    val sameTitle = id == playingId
    sidePanel.show("Sources", if (sameTitle) "Switch to another source; the video carries on from here."
        else listOf(target.title, target.episodeLabel).filter { it.isNotBlank() }.joinToString("  ·  "))
    sidePanel.setTabs(emptyList(), 0) {}
    val session = StreamSearch.get(type, id)
    var focused = false
    fun build() {
        val order = session.addons?.map { it.name }.orEmpty()
        val found = session.results.filter { it.second.isNotEmpty() }.sortedBy { order.indexOf(it.first.name) }
        val rows = mutableListOf<SidePanel.Row>()
        var current = -1
        found.forEach { (addon, streams) ->
            rows += SidePanel.Row("${addon.name}  ·  ${streams.size}", header = true)
            streams.forEach { stream ->
                if (stream.url == null && stream.infoHash == null) return@forEach
                val release = stream.description.lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
                val details = listOfNotNull(
                    stream.name.takeIf { it.isNotBlank() && it != release },
                    Ui.streamBadges("${stream.name} ${stream.description}").joinToString(" · ") { it.first }.takeIf { it.isNotBlank() }
                ).joinToString("  ·  ")
                val isPlaying = sameTitle && stream.url == playingStream?.url
                if (isPlaying) current = rows.size
                rows += SidePanel.Row(
                    release.ifBlank { stream.name }, details, checked = isPlaying,
                    trailing = Ui.streamSize("${stream.name} ${stream.description}").orEmpty()
                ) { switchToStream(stream, type, id, target) }
            }
        }
        val pending = session.pending
        sidePanel.setStatus(when {
            pending != 0 && rows.isEmpty() -> "Finding sources..."
            pending > 0 -> "Still asking $pending ${if (pending == 1) "addon" else "addons"}..."
            rows.isEmpty() -> "No sources found."
            else -> ""
        })
        val focusAt = if (!focused && rows.isNotEmpty()) {
            focused = true
            current.takeIf { it >= 0 } ?: rows.indexOfFirst { !it.header }
        } else null
        sidePanel.setRows(rows, focusAt)
    }
    val listener = object : StreamSearch.Listener {
        override fun onAddons(addons: List<StremioStreams.Addon>) = build()
        override fun onResult(addon: StremioStreams.Addon, streams: List<StremioStreams.Stream>) = build()
    }
    panelSession = session
    panelListener = listener
    build()
    session.observe(listener)
}

private fun MainActivity.switchToStream(stream: StremioStreams.Stream, type: String, id: String, target: WatchTarget) {
    if (stream.url == null && stream.infoHash != null) {
        resolveTorBox(stream, target, { note -> note?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() } }) { resolved ->
            if (currentScreen == "player") switchToStream(resolved, type, id, target)
        }
        return
    }
    val url = stream.url ?: return
    val sameTitle = id == playingId
    val startAt = if (sameTitle) player?.currentPosition ?: 0L
        else progress.get(id)?.takeIf { it.resumable }?.positionMs ?: 0L
    closeSidePanel()
    saveProgress(pushNow = true)
    if (!sameTitle && target.tvId > 0) {
        // Back from this episode leads to the show, on it.
        lastTvId = target.tvId
        lastFilmTitle = target.title
        lastPosterFile = target.poster
        seasonByShow[target.tvId] = target.season
        episodeFocusIndex = target.episode - 1
        playerReturnScreen = "tv_details"
    }
    playingStream = stream
    if (sameTitle) {
        startPlayback(url, stream.headers, id, target, startAt, playingSubtitlesFound)
        return
    }
    // Another episode: its addon subtitles first (briefly), as the sources page does.
    Toast.makeText(this, "Loading ${target.episodeLabel.substringBefore("  ")}...", Toast.LENGTH_SHORT).show()
    thread {
        val subs = if (subtitleSettings.addonSubtitles) try {
            streamLoader.submit<List<StremioSubtitles.Subtitle>> { StremioSubtitles.fetch(type, id) }
                .get(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: Exception) {
            emptyList()
        } else emptyList()
        Handler(Looper.getMainLooper()).post {
            if (currentScreen == "player") startPlayback(url, stream.headers, id, target, startAt, subs)
        }
    }
}

// The show's episodes, a season at a time (seasons as tabs). Picking one lists its sources.
internal fun MainActivity.showEpisodesPanel(season: Int? = null, focusTab: Boolean = false) {
    val target = playingTarget?.takeIf { it.type == "series" && it.tvId > 0 } ?: return
    val showImdb = playingId?.substringBefore(':') ?: return
    val shownSeason = season ?: target.season
    openSidePanel()
    sidePanel.show(target.title, "Episodes")
    sidePanel.setStatus("Loading episodes...")
    if (season == null) sidePanel.setRows(emptyList())
    val key = BuildConfig.TMDB_API_KEY
    thread {
        val seasons = try {
            TmdbApi.fetchTvSeasons(target.tvId, key).filter { it.seasonNumber >= 1 }
        } catch (_: Exception) {
            emptyList()
        }
        val episodes = try {
            TmdbApi.fetchTvEpisodes(target.tvId, shownSeason, key)
        } catch (_: Exception) {
            emptyList()
        }
        val watched = progress.forShow(target.tvId)
        Handler(Looper.getMainLooper()).post {
            if (currentScreen != "player" || !sidePanel.isShowing) return@post
            val tab = seasons.indexOfFirst { it.seasonNumber == shownSeason }
            sidePanel.setTabs(seasons.map { it.name.ifBlank { "Season ${it.seasonNumber}" } }, tab) { i ->
                showEpisodesPanel(seasons[i].seasonNumber, focusTab = true)
            }
            var current = -1
            val rows = episodes.mapIndexed { i, ep ->
                val isPlaying = shownSeason == target.season && ep.episodeNumber == target.episode
                if (isPlaying) current = i
                val state = watched[shownSeason to ep.episodeNumber]?.let { e ->
                    when {
                        e.watched -> "Watched"
                        e.resumable -> "${WatchProgress.format(e.positionMs)} in"
                        else -> null
                    }
                }
                val details = listOfNotNull(
                    Ui.formatRuntime(ep.runtimeMinutes),
                    ep.airDate.takeIf { it.isNotBlank() },
                    ep.overview.takeIf { it.isNotBlank() }
                ).joinToString("  ·  ")
                SidePanel.Row("E${ep.episodeNumber}  ·  ${ep.name}", details, checked = isPlaying,
                    trailing = if (isPlaying) "Playing" else state.orEmpty()) {
                    val pick = target.copy(
                        season = shownSeason, episode = ep.episodeNumber,
                        episodeLabel = "S$shownSeason · E${ep.episodeNumber}  ${ep.name}"
                    )
                    showSourcesPanel("series", "$showImdb:$shownSeason:${ep.episodeNumber}", pick) {
                        showEpisodesPanel(shownSeason)
                    }
                }
            }
            sidePanel.setStatus(if (rows.isEmpty()) "Couldn't load this season's episodes." else "")
            sidePanel.setRows(rows, if (focusTab) null else current.takeIf { it >= 0 } ?: 0)
            if (focusTab && tab >= 0) sidePanel.focusTab(tab)
        }
    }
}

// A list menu (subtitles, audio) in the side panel: "Name  ·  detail  ·  detail" entries
// show the name with the details beneath.
internal fun MainActivity.showListPanel(title: String, names: List<String>, selected: Int, onPick: (Int) -> Unit) {
    openSidePanel()
    sidePanel.show(title)
    sidePanel.setTabs(emptyList(), 0) {}
    sidePanel.setStatus("")
    val rows = names.mapIndexed { i, name ->
        val parts = name.split("  ·  ")
        SidePanel.Row(parts.first(), parts.drop(1).joinToString("  ·  "), checked = i == selected) {
            closeSidePanel()
            onPick(i)
        }
    }
    sidePanel.setRows(rows, selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0)))
}
