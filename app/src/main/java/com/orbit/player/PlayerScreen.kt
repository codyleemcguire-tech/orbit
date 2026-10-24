package com.orbit.player

import com.orbit.player.MainActivity.ActorPage
import com.orbit.player.MainActivity.DetailHero
import com.orbit.player.MainActivity.DiscoverAdapter
import com.orbit.player.MainActivity.FolderAdapter
import com.orbit.player.MainActivity.NextEpisode
import com.orbit.player.MainActivity.PosterCard
import com.orbit.player.MainActivity.WatchTarget
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

// The player screen: starting playback with NuvioEngine, keys, the Subtitles and audio menu,
// skip intro and next episode, frame-rate matching, the loading overlay, saving progress
// and Trakt scrobbles. MainActivity extension functions; the player's state lives there.

internal fun MainActivity.scrobble(action: String) {
    val item = scrobbleItem ?: return
    val p = player ?: return
    if (action == scrobbleState || scrobbleState == "stop") return
    val duration = p.duration.takeIf { it > 0 } ?: return
    scrobbleState = action
    trakt.scrobble(action, item, if (action == "stop" && p.playbackState == androidx.media3.common.Player.STATE_ENDED) 100f
        else p.currentPosition * 100f / duration)
}

// Saves where the viewer is in the current title (called every 10 s and on exit).
// pushNow: send to Stremio straight away (leaving the player) rather than once a minute.
internal fun MainActivity.saveProgress(finished: Boolean = false, pushNow: Boolean = false) {
    val p = player ?: return
    val id = playingId ?: return
    val target = playingTarget ?: return
    val duration = p.duration.takeIf { it > 0 } ?: return
    val position = if (finished) duration else p.currentPosition
    if (position <= 0) return
    homeDirty = true // Continue rows change
    val entry = WatchProgress.Entry(
        id, target.type, target.title, target.episodeLabel, target.poster,
        target.tvId, target.season, target.episode,
        position, duration, System.currentTimeMillis()
    )
    progress.save(entry)
    library.pushProgress(entry, force = finished || pushNow)
}

internal fun MainActivity.buildLoadingOverlay(): FrameLayout {
    loadingBackdrop = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        alpha = 0f
    }
    // Darkens the backdrop, most of all where the text sits.
    val scrim = View(this).apply {
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.argb(250, 9, 11, 16), Color.argb(200, 9, 11, 16), Color.argb(150, 9, 11, 16))
        )
    }
    loadingLogo = ImageView(this).apply {
        adjustViewBounds = true
        maxWidth = 760
        maxHeight = 220
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    loadingTitle = Ui.text(this, Ui.Type.Display, "", maxLines = 2).apply { gravity = Gravity.CENTER }
    loadingSubtitle = Ui.text(this, Ui.Type.BodyStrong, "", Ui.textSecondary, maxLines = 1).apply {
        gravity = Gravity.CENTER
        textSize = 20f
        setPadding(0, 22, 0, 0)
    }
    val spinner = android.widget.ProgressBar(this).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.accent)
    }
    loadingStatus = Ui.text(this, Ui.Type.BodyStrong, "", Ui.textPrimary, maxLines = 1).apply {
        gravity = Gravity.CENTER
        setPadding(0, 22, 0, 0)
    }
    loadingSource = Ui.text(this, Ui.Type.Label, "", Ui.textMuted, maxLines = 1).apply {
        gravity = Gravity.CENTER
        setPadding(120, 10, 120, 0)
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }
    val column = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(loadingLogo, LinearLayout.LayoutParams(-2, -2))
        addView(loadingTitle, LinearLayout.LayoutParams(-1, -2))
        addView(loadingSubtitle, LinearLayout.LayoutParams(-1, -2))
        addView(spinner, LinearLayout.LayoutParams(84, 84).apply { topMargin = 70 })
        addView(loadingStatus, LinearLayout.LayoutParams(-1, -2))
        addView(loadingSource, LinearLayout.LayoutParams(-1, -2))
    }
    val hint = Ui.text(this, Ui.Type.Caption, "Press Back to cancel", Ui.textMuted).apply { gravity = Gravity.CENTER }
    loadingOverlay = FrameLayout(this).apply {
        setBackgroundColor(Ui.background)
        visibility = View.GONE
        isClickable = true // nothing behind it reacts while it's up
        addView(loadingBackdrop, FrameLayout.LayoutParams(-1, -1))
        addView(scrim, FrameLayout.LayoutParams(-1, -1))
        addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = Ui.gutter; rightMargin = Ui.gutter })
        addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = 56 })
    }
    return loadingOverlay
}

internal fun MainActivity.showLoadingOverlay(watchId: String?, target: WatchTarget?) {
    val request = ++loadingRequest
    val trailer = target == null
    val title = target?.title ?: lastFilmTitle
    loadingTitle.text = title
    loadingTitle.visibility = View.VISIBLE
    loadingLogo.visibility = View.GONE
    loadingLogo.setImageDrawable(null)
    loadingBackdrop.animate().cancel()
    loadingBackdrop.alpha = 0f
    loadingBackdrop.setImageDrawable(null)
    loadingSubtitle.text = when {
        trailer -> "Trailer"
        target!!.episodeLabel.isNotBlank() -> target.episodeLabel
        else -> ""
    }
    loadingSubtitle.visibility = if (loadingSubtitle.text.isEmpty()) View.GONE else View.VISIBLE
    loadingStatus.text = if (trailer) "Loading trailer..." else "Loading stream..."
    // Which stream: its addon and the quality/size details addons put in the name.
    loadingSource.text = playingStream?.let { st ->
        val badges = Ui.streamBadges("${st.name} ${st.description}").joinToString("  ·  ") { it.first }
        listOf(st.addonName, badges).filter { it.isNotBlank() }.joinToString("  ·  ")
    }.orEmpty()
    loadingSource.visibility = if (loadingSource.text.isEmpty()) View.GONE else View.VISIBLE
    playerView.useController = false
    loadingOverlay.animate().cancel()
    loadingOverlay.alpha = 1f
    loadingOverlay.visibility = View.VISIBLE
    // Reassurance when a big file takes a while to start.
    loadingOverlay.postDelayed({
        if (request == loadingRequest && loadingOverlay.visibility == View.VISIBLE)
            loadingStatus.text = "Still loading — large files can take a moment..."
    }, 9000)

    // Backdrop and title logo from TMDB, found by the video's IMDb id (or the show/film page).
    if (!tmdbSettings.enabled) return
    val key = BuildConfig.TMDB_API_KEY
    val tvId = target?.tvId?.takeIf { it > 0 } ?: if (trailer && detailsTypeIsTv()) lastTvId else null
    val tmdbId = if (trailer && !detailsTypeIsTv()) lastFilmTmdbId else null
    thread {
        val details = try {
            when {
                tvId != null -> TmdbMeta.details("tv", tvId, key)
                tmdbId != null -> TmdbMeta.details("movie", tmdbId, key)
                watchId != null && watchId.startsWith("tt") ->
                    TmdbMeta.resolve(watchId.substringBefore(':'), target?.type ?: "movie", key)
                        ?.let { (t, id) -> TmdbMeta.details(t, id, key) }
                else -> tmdbDetailsFor("movie", title, null, lastFilmImdbId)
            }
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (request != loadingRequest || loadingOverlay.visibility != View.VISIBLE || details == null) return@post
            details.metadata.backdropPath?.let { path ->
                loadPosterImage(loadingBackdrop, path, "w1280", targetWidth = 1280)
                loadingBackdrop.animate().alpha(1f).setDuration(400).start()
            }
            if (tmdbSettings.logos) details.logoPath?.let { path ->
                showLogo(loadingLogo, path) {
                    if (request == loadingRequest) loadingTitle.visibility = View.GONE
                }
            }
        }
    }
}

internal fun MainActivity.hideLoadingOverlay(animate: Boolean = true) {
    loadingRequest++
    playerView.useController = true
    if (loadingOverlay.visibility != View.VISIBLE) return
    if (!animate) {
        loadingOverlay.visibility = View.GONE
        return
    }
    loadingOverlay.animate().alpha(0f).setDuration(350).withEndAction {
        loadingOverlay.visibility = View.GONE
        loadingOverlay.alpha = 1f
    }.start()
}

// ---- First run: welcome and addon setup ----

internal fun MainActivity.startPlayback(
    url: String,
    headers: Map<String, String>,
    watchId: String? = null,
    target: WatchTarget? = null,
    startAtMs: Long = 0L,
    addonSubtitles: List<StremioSubtitles.Subtitle> = emptyList()
) {
    // Moving straight on to another video (next episode): finish the last one on Trakt.
    scrobble("stop")
    playingId = watchId
    playingTarget = target
    scrobbleItem = if (target != null && watchId != null) Trakt.Item.from(watchId, target.type) else null
    scrobbleState = null
    progressHandler.removeCallbacks(progressTicker)
    progressHandler.postDelayed(progressTicker, 10_000)
    // Moving on to the next episode keeps where Back from the player leads.
    if (currentScreen != "player") playerReturnScreen = currentScreen
    currentScreen = "player"
    // Full screen: the player replaces the page instead of sitting inside its margins.
    (playerFrame.parent as? ViewGroup)?.removeView(playerFrame)
    stopDiscoverFocusWatcher()
    setContentView(playerFrame)
    playerView.requestFocus()
    showLoadingOverlay(watchId, target)
    startPlayerExtras(watchId, target)

    player?.release()
    // NuvioTV's engine and its defaults (see NuvioEngine); addon request headers go with
    // every request.
    val built = NuvioEngine.build(
        this, window.decorView.display, url, headers,
        if (".m3u8" in url) androidx.media3.common.MimeTypes.APPLICATION_M3U8 else null,
        playerSettings.tunneling
    ) { audioDelayMs * 1000L }
    reconnects = 0
    lastReconnectAt = 0L
    val subs = subtitleSettings
    subs.applyStyle(playerView.subtitleView)
    // The control bar's CC button opens Orbit's subtitle menu rather than Media3's own.
    playerView.setShowSubtitleButton(true)
    playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.setOnClickListener {
        showSubtitleMenu()
    }
    var subtitleChosen = false
    player = built.player
        .also {
            // Orbit picks the subtitle track itself (see SubtitleSettings.choose), so the
            // player starts with subtitles off; the preferred audio language goes first.
            // With Match frame rate on, the sound starts only after the TV has switched
            // mode (see holdAudioForFrameRate).
            val holdAudio = playerSettings.matchFrameRate
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, holdAudio)
                .apply { if (subs.audioLanguage.isNotBlank()) setPreferredAudioLanguage(subs.audioLanguage) }
                .build()
            if (holdAudio) holdAudioForFrameRate(it, startAtMs)
            playerView.player = it
            it.addListener(object : androidx.media3.common.Player.Listener {
                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    // Once per video, after the audio track is known; later changes made
                    // in the player's own subtitle menu are left alone.
                    if (subtitleChosen || tracks.groups.none { g -> g.type == C.TRACK_TYPE_AUDIO && g.isSelected }) return
                    subtitleChosen = true
                    val choice = subs.choose(tracks)
                    if (choice == null) {
                        // Nothing suitable in the file: an addon subtitle, if any fits.
                        subs.chooseAddon(tracks, playingAddonSubs)?.let { showAddonSubtitle(it) }
                        return
                    }
                    it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(
                            androidx.media3.common.TrackSelectionOverride(choice.group.mediaTrackGroup, choice.index)
                        )
                        .build()
                }

                // Runs after PlayerView's own listener, so the cleaned cues are what show.
                override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
                    // The player cleared the view (a seek): the addon lines go back next tick.
                    if (activeAddonTrack != null) {
                        shownAddonLines = null
                        return
                    }
                    if (subs.stripSdh) playerView.subtitleView?.setCues(subs.filterCues(cueGroup.cues))
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (reconnect(it, error)) return
                    // The message shows on the streams screen; elsewhere (trailers, …) a toast instead.
                    if (playerReturnScreen == "streams") playbackError = "Couldn't play that stream (${error.errorCodeName}). " +
                        "Try another one, or hold OK on it to open it in another app."
                    else android.widget.Toast.makeText(this@startPlayback,
                        if (playingTarget == null) "Couldn't play the trailer." else "Couldn't play that stream.",
                        android.widget.Toast.LENGTH_LONG).show()
                    leavePlayer()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state != androidx.media3.common.Player.STATE_ENDED) return
                    // A host that closes the connection early can look like the end of
                    // the file: pick up where it stopped rather than finishing the episode.
                    val duration = it.duration
                    val position = it.currentPosition
                    if (playingTarget != null && duration > 0 && position < duration - 120_000 &&
                        position < duration * 9 / 10 && reconnect(it, null)) return
                    saveProgress(finished = true)
                    scrobble("stop")
                    if (playerSettings.autoplayNext) nextEpisode?.let { playNextEpisode(it) }
                }

                override fun onRenderedFirstFrame() {
                    if (audioHeldFrom == null) hideLoadingOverlay()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // Without this the TV's screensaver (and then sleep) starts part way
                    // through a film, which stops Orbit and closes the player.
                    playerView.keepScreenOn = isPlaying
                    if (isPlaying && audioHeldFrom == null) hideLoadingOverlay()
                    if (isPlaying && audioHeldFrom == null) scrobble("start")
                }

                // Paused by the viewer (not just buffering).
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && audioHeldFrom == null &&
                        reason == androidx.media3.common.Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) scrobble("pause")
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (playerSettings.matchFrameRate) matchFrameRate(it)
                }
            })
            // Addon subtitles are shown by Orbit (see AddonSubtitleTrack), ranked so the
            // ones made for this release come first.
            playingAddonSubs = AddonSubtitleTrack.rank(
                StremioSubtitles.pick(
                    addonSubtitles, listOf(subs.language, subs.secondLanguage).filter { l -> l.isNotBlank() },
                    subs.onlyPreferred, perPreferred = 8, total = 24
                ),
                playingStream?.filename
            )
            if (startAtMs > 0) it.setMediaSource(built.mediaSource, startAtMs) else it.setMediaSource(built.mediaSource)
            it.prepare()
            it.play()
        }
}

// The connection to the file host dropped (or a read timed out): carry on from the same
// point instead of closing the player. Up to five tries; the count starts again once
// playback has run for a couple of minutes. error is null for a too-early end of file.
internal fun MainActivity.reconnect(p: ExoPlayer, error: androidx.media3.common.PlaybackException?): Boolean {
    if (playingTarget == null) return false // trailers
    // Network and server errors only; a file the TV can't decode won't get better.
    if (error != null && error.errorCode !in 2000..2999 &&
        error.errorCode != androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
    // A host refusing the link (403 for an expired debrid link, 404) won't change on a retry.
    val http = generateSequence(error?.cause) { it.cause }
        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    if (http != null && http.responseCode in 400..499 && http.responseCode != 408 && http.responseCode != 429) return false
    val now = System.currentTimeMillis()
    if (now - lastReconnectAt > 120_000) reconnects = 0
    if (reconnects >= 5) return false
    reconnects++
    lastReconnectAt = now
    val position = p.currentPosition
    loadingStatus.text = "Reconnecting..."
    android.widget.Toast.makeText(this, "Connection dropped, reconnecting...", android.widget.Toast.LENGTH_SHORT).show()
    progressHandler.postDelayed({
        if (player !== p) return@postDelayed
        p.seekTo(position)
        p.prepare()
        p.play()
    }, 1500L * reconnects)
    return true
}

// Subtitle menu in the player: Off plus every subtitle track, the current one ticked.
internal fun MainActivity.showSubtitleMenu() {
    val p = player ?: return
    val entries = mutableListOf<Pair<String, (() -> Unit)>>()
    var selected = 0
    val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
    entries += ("Audio delay (lip sync):  " + formatAudioDelay(audioDelayMs)) to { showAudioDelay() }
    val delayLabel = "Subtitle delay:  " + formatDelay(subtitleDelayMs)
    entries += delayLabel to { showSubtitleDelay() }
    entries += "Off" to {
        hideAddonSubtitle()
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
    selected = 2
    p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { group ->
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val f = group.getTrackFormat(i)
            val language = SubtitleSettings.normalize(f.language)
                ?.let { java.util.Locale(it).displayLanguage.takeIf { d -> d.isNotBlank() && d != it } ?: it }
                ?: "Unknown language"
            // Addon tracks carry "Addon · Language"; the file's own get the language plus tags.
            val name = f.label?.takeIf { "  ·  " in it } ?: listOfNotNull(
                language,
                f.label?.takeIf { it.isNotBlank() && !it.equals(language, true) },
                "Forced".takeIf { SubtitleSettings.isForced(f) && f.label?.contains("forced", true) != true },
                "SDH".takeIf { SubtitleSettings.isSdh(f) && f.label?.contains("sdh", true) != true },
                "in video"
            ).joinToString("  ·  ")
            if (!textDisabled && activeAddonTrack == null && group.isTrackSelected(i)) selected = entries.size
            entries += name to {
                hideAddonSubtitle()
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                    .build()
            }
        }
    }
    // Addon subtitles, best release match first.
    val group = AddonSubtitleTrack.releaseGroup(playingStream?.filename.orEmpty())
    playingAddonSubs.forEach { sub ->
        val language = SubtitleSettings.normalize(sub.language)
            ?.let { java.util.Locale(it).displayLanguage.takeIf { d -> d.isNotBlank() && d != it } ?: it }
            ?: sub.language
        val matches = group != null && sub.releaseName.contains(group, ignoreCase = true)
        // The release it was made for, shortened: picking the one closest to the
        // playing file is what keeps it in sync.
        val release = sub.releaseName.replace(Regex("\\.(srt|vtt|sub|ass)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^v3\\+\\|\\d+\\|"), "").replace('.', ' ').trim()
            .let { if (it.length > 46) it.take(45) + "…" else it }
        val name = listOfNotNull(
            language,
            "Forced".takeIf { sub.forced },
            "Matches your file".takeIf { matches },
            release.takeIf { it.isNotBlank() },
            sub.addonName
        ).joinToString("  ·  ")
        if (activeAddonTrack?.subtitle == sub) selected = entries.size
        entries += name to { showAddonSubtitle(sub) }
    }
    if (entries.size == 3) {
        android.widget.Toast.makeText(this, "This video has no subtitles", android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    // Number repeated names (several files from one addon in one language).
    val counts = entries.groupingBy { it.first }.eachCount()
    val seen = mutableMapOf<String, Int>()
    val names = entries.map { (name, _) ->
        if ((counts[name] ?: 0) < 2) name
        else "$name  ·  ${seen.merge(name, 1, Int::plus)}"
    }
    choose("Subtitles and audio", names, selected) { entries[it].second() }
}

// ---- Skip intro, next episode, frame-rate matching ---------------------------------

internal fun MainActivity.startPlayerExtras(watchId: String?, target: WatchTarget?) {
    playingSegments = emptyList()
    frameTimer = null
    activeAddonTrack = null
    subtitleDelayMs = 0L
    progressHandler.removeCallbacks(addonSubtitleTicker)
    autoSkipped.clear()
    nextEpisode = null
    playerAction.visibility = View.GONE
    progressHandler.removeCallbacks(extrasTicker)
    progressHandler.postDelayed(extrasTicker, 1000)
    val id = watchId ?: return
    thread {
        val segments = if (playerSettings.skipIntro != "Off") IntroDb.fetch(id) else emptyList()
        val next = if (target?.type == "series" && playerSettings.nextEpisodeButton) findNextEpisode(id, target) else null
        Handler(Looper.getMainLooper()).post {
            if (playingId != id) return@post
            playingSegments = segments
            nextEpisode = next
        }
    }
}

internal fun MainActivity.stopPlayerExtras() {
    progressHandler.removeCallbacks(extrasTicker)
    progressHandler.removeCallbacks(addonSubtitleTicker)
    activeAddonTrack = null
    playerView.subtitleView?.setCues(emptyList())
    frameTimer = null
    playerAction.visibility = View.GONE
    restoreDisplayMode()
}

// Shows (or auto-runs) the action for the current position.
internal fun MainActivity.updatePlayerAction() {
    val p = player ?: return
    val position = p.currentPosition
    val duration = p.duration.takeIf { it > 0 } ?: 0L
    val skippable = playingSegments.firstOrNull {
        (it.type == "intro" || it.type == "recap") && position >= it.startMs && position < it.endMs - 1000
    }
    if (skippable != null && playerSettings.skipIntro != "Off") {
        val key = "${skippable.type}@${skippable.startMs}"
        if (playerSettings.skipIntro == "Skip automatically" && autoSkipped.add(key)) {
            p.seekTo(skippable.endMs)
            android.widget.Toast.makeText(this, "Skipped ${skippable.type}", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        showPlayerAction(if (skippable.type == "recap") "Skip recap" else "Skip intro") { p.seekTo(skippable.endMs) }
        return
    }
    // Next episode from the start of the credits, or the last 45 s without credit times.
    val next = nextEpisode
    val creditsStart = playingSegments.firstOrNull { it.type == "outro" }?.startMs
        ?: (duration - 45_000).takeIf { duration > 0 }
    if (next != null && creditsStart != null && position >= creditsStart && duration > 0) {
        showPlayerAction("Next episode  ▸  S${next.target.season} · E${next.target.episode}") { playNextEpisode(next) }
        // Look for its stream ahead of time, so OK starts it straight away.
        if (!next.searched) {
            next.searched = true
            thread { findNextStream(next) }
        }
        return
    }
    playerAction.visibility = View.GONE
}

internal fun MainActivity.showPlayerAction(label: String, run: () -> Unit) {
    playerActionRun = run
    if (playerAction.text != "$label    OK") playerAction.text = "$label    OK"
    if (playerAction.visibility != View.VISIBLE) playerAction.visibility = View.VISIBLE
}

// The next episode in the season, else the first of the next season. Call off the
// main thread.
internal fun MainActivity.findNextEpisode(id: String, target: WatchTarget): NextEpisode? = try {
    val key = BuildConfig.TMDB_API_KEY
    val showImdb = id.substringBefore(':')
    val seasons = TmdbApi.fetchTvSeasons(target.tvId, key).filter { it.seasonNumber >= 1 }
    val current = seasons.firstOrNull { it.seasonNumber == target.season }
    val (season, episode) = when {
        current != null && target.episode < current.episodeCount -> target.season to target.episode + 1
        else -> seasons.firstOrNull { it.seasonNumber > target.season }?.let { it.seasonNumber to 1 }
    } ?: return null
    val name = TmdbApi.fetchTvEpisodes(target.tvId, season, key)
        .firstOrNull { it.episodeNumber == episode }?.name.orEmpty()
    NextEpisode(
        target.copy(season = season, episode = episode, episodeLabel = "S$season · E$episode  $name"),
        "$showImdb:$season:$episode"
    )
} catch (_: Exception) {
    null
}

// Prefers the same release (Stremio's bingeGroup) from the same addon, as Stremio does.
internal fun MainActivity.findNextStream(next: NextEpisode) {
    // Its sources page then opens with the streams already found.
    StreamSearch.prefetch("series", next.id)
    val current = playingStream
    val addons = StremioStreams.addonsFor("series", next.id)
        .sortedByDescending { it.name == current?.addonName }
    for (addon in addons) {
        val streams = try {
            StremioStreams.fetchStreams(addon, "series", next.id).filter { it.url != null }
        } catch (_: Exception) {
            continue
        }
        val pick = streams.firstOrNull { current?.bingeGroup != null && it.bingeGroup == current.bingeGroup }
            ?: streams.firstOrNull().takeIf { addon.name == current?.addonName }
        if (pick != null) {
            next.stream = pick
            break
        }
    }
    next.subtitles = if (subtitleSettings.addonSubtitles) try {
        StremioSubtitles.fetch("series", next.id)
    } catch (_: Exception) {
        emptyList()
    } else emptyList()
}

internal fun MainActivity.playNextEpisode(next: NextEpisode) {
    playerAction.visibility = View.GONE
    saveProgress()
    val play = {
        val stream = next.stream
        // Back from the next episode leads to the show, on that episode.
        lastTvId = next.target.tvId
        lastFilmTitle = next.target.title
        lastPosterFile = next.target.poster
        seasonByShow[next.target.tvId] = next.target.season
        episodeFocusIndex = next.target.episode - 1
        playerReturnScreen = "tv_details"
        if (stream?.url != null) {
            playingStream = stream
            startPlayback(stream.url, stream.headers, next.id, next.target, 0L, next.subtitles)
        } else {
            // No matching stream: choose one from the next episode's list.
            stopPlayerExtras()
            player?.release()
            player = null
            currentScreen = "tv_details"
            showStreams("series", "${next.target.title}  ·  S${next.target.season} E${next.target.episode}", next.target) { next.id }
        }
    }
    if (next.searched && next.stream != null) {
        play()
    } else {
        android.widget.Toast.makeText(this, "Finding the next episode...", android.widget.Toast.LENGTH_SHORT).show()
        thread {
            if (next.stream == null) findNextStream(next)
            next.searched = true
            Handler(Looper.getMainLooper()).post { if (currentScreen == "player") play() }
        }
    }
}

// Switches the TV to the video's frame rate (e.g. 23.976 Hz for films) so motion is
// smooth; the picture blanks briefly while the TV changes mode, so playback waits.
// MKV files usually don't state their frame rate; then it's measured from the timing of
// the first frames shown.
internal fun MainActivity.matchFrameRate(p: ExoPlayer) {
    val stated = p.videoFormat?.frameRate ?: -1f
    if (stated > 0f) {
        matchFrameRate(p, stated)
        return
    }
    if (frameTimer != null) return
    val times = java.util.Collections.synchronizedList(mutableListOf<Long>())
    val timer = androidx.media3.exoplayer.video.VideoFrameMetadataListener { presentationUs, _, _, _ ->
        if (times.size < 48) times.add(presentationUs)
        if (times.size == 48) {
            times.add(-1) // done
            // Average over the whole span: MKV times are rounded to the millisecond, so a
            // single gap can't tell 23.976 from 24.
            val sorted = times.dropLast(1).distinct().sorted()
            val span = sorted.last() - sorted.first()
            if (sorted.size < 10 || span <= 0) {
                Handler(Looper.getMainLooper()).post { if (player === p) releaseAudio(p, 0) }
                return@VideoFrameMetadataListener
            }
            val fps = FrameRate.snap((sorted.size - 1) * 1_000_000f / span)
            Handler(Looper.getMainLooper()).post {
                frameTimer?.let { t -> p.clearVideoFrameMetadataListener(t) }
                if (player !== p) return@post
                if (fps > 0f) matchFrameRate(p, fps) else releaseAudio(p, 0)
            }
        }
    }
    frameTimer = timer
    p.setVideoFrameMetadataListener(timer)
}

internal fun MainActivity.matchFrameRate(p: ExoPlayer, fps: Float) {
    val display = window.decorView.display
    val mode = display?.let { FrameRate.bestMode(it, fps) }
    if (display == null || mode == null || mode.modeId == display.mode.modeId) {
        releaseAudio(p, 0) // already right (or can't change): start the sound now
        return
    }
    if (originalDisplayMode == 0) originalDisplayMode = display.mode.modeId
    if (audioHeldFrom != null) {
        loadingStatus.text = "Matching your TV's frame rate..."
        p.playWhenReady = false
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        releaseAudio(p, 2500)
        return
    }
    val wasPlaying = p.playWhenReady
    p.playWhenReady = false
    window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
    progressHandler.postDelayed({ if (player === p && wasPlaying) p.playWhenReady = true }, 2500)
}

internal fun MainActivity.holdAudioForFrameRate(p: ExoPlayer, startAtMs: Long) {
    audioHeldFrom = startAtMs
    // Some videos never report frames (or a rate): don't keep the sound off for long.
    progressHandler.postDelayed({ releaseAudio(p, 0) }, 8000)
}

internal fun MainActivity.releaseAudio(p: ExoPlayer, delayMs: Long) {
    if (player !== p) return // a later video has its own hold
    val from = audioHeldFrom ?: return
    audioHeldFrom = null
    p.playWhenReady = false
    progressHandler.postDelayed({
        if (player !== p) return@postDelayed
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        p.seekTo(from)
        p.playWhenReady = true
    }, delayMs)
}

internal fun MainActivity.restoreDisplayMode() {
    if (originalDisplayMode == 0) return
    window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
    originalDisplayMode = 0
}

internal fun MainActivity.formatDelay(ms: Long) =
    (if (ms > 0) "+" else if (ms < 0) "−" else "") + String.format(java.util.Locale.US, "%.1f s", kotlin.math.abs(ms) / 1000.0)

// Loads (once) and shows an addon subtitle, turning the video's own subtitles off.
internal fun MainActivity.showAddonSubtitle(sub: StremioSubtitles.Subtitle) {
    val p = player ?: return
    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()
    val start = { track: AddonSubtitleTrack ->
        activeAddonTrack = track
        shownAddonLines = null
        progressHandler.removeCallbacks(addonSubtitleTicker)
        progressHandler.post(addonSubtitleTicker)
    }
    loadedAddonTracks[sub.url]?.let { start(it); return }
    thread {
        val track = AddonSubtitleTrack.load(sub)
        Handler(Looper.getMainLooper()).post {
            if (player !== p) return@post
            if (track == null) {
                android.widget.Toast.makeText(this, "Couldn't load that subtitle", android.widget.Toast.LENGTH_SHORT).show()
                return@post
            }
            loadedAddonTracks[sub.url] = track
            start(track)
        }
    }
}

internal fun MainActivity.hideAddonSubtitle() {
    activeAddonTrack = null
    progressHandler.removeCallbacks(addonSubtitleTicker)
    playerView.subtitleView?.setCues(emptyList())
}

// Shift addon subtitles: + shows them later, − earlier. Applies straight away.
internal fun MainActivity.showSubtitleDelay() {
    val value = Ui.text(this, Ui.Type.Title, formatDelay(subtitleDelayMs)).apply {
        gravity = Gravity.CENTER
        setPadding(0, 10, 0, 6)
    }
    val note = Ui.text(
        this, Ui.Type.Label,
        if (activeAddonTrack != null) "+ shows subtitles later, − earlier."
        else "Works with addon subtitles (e.g. OpenSubtitles); choose one first.",
        Ui.textSecondary
    ).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 22) }
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    listOf("−1 s" to -1000L, "−0.1 s" to -100L, "Reset" to 0L, "+0.1 s" to 100L, "+1 s" to 1000L).forEach { (label, step) ->
        buttons.addView(Ui.pill(this, label) {
            subtitleDelayMs = if (step == 0L) 0L else subtitleDelayMs + step
            value.text = formatDelay(subtitleDelayMs)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8; rightMargin = 8 })
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 20, 40, 10)
        addView(value)
        addView(note)
        addView(buttons)
    }
    android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Subtitle delay")
        .setView(content)
        .setPositiveButton("Done", null)
        .show()
    buttons.getChildAt(3)?.requestFocus()
}

internal fun MainActivity.formatAudioDelay(ms: Long) = (if (ms > 0) "+" else if (ms < 0) "−" else "") + "${kotlin.math.abs(ms)} ms"

// Lip sync: shifts the sound against the picture (NuvioTV's audio delay). Kept for next time.
internal fun MainActivity.showAudioDelay() {
    val value = Ui.text(this, Ui.Type.Title, formatAudioDelay(audioDelayMs)).apply {
        gravity = Gravity.CENTER
        setPadding(0, 10, 0, 6)
    }
    val note = Ui.text(this, Ui.Type.Label,
        "+ if the sound comes before the lips move, − if it comes after. Remembered for every video.",
        Ui.textSecondary).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, 22) }
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
    }
    listOf("−100" to -100L, "−20" to -20L, "Reset" to 0L, "+20" to 20L, "+100" to 100L).forEach { (label, step) ->
        buttons.addView(Ui.pill(this, label) {
            audioDelayMs = if (step == 0L) 0L else (audioDelayMs + step).coerceIn(-2000L, 2000L)
            value.text = formatAudioDelay(audioDelayMs)
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 8; rightMargin = 8 })
    }
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 20, 40, 10)
        addView(value)
        addView(note)
        addView(buttons)
    }
    android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Audio delay")
        .setView(content)
        .setPositiveButton("Done", null)
        // Samples already buffered carry the old timing; a seek in place re-reads them.
        .setOnDismissListener { player?.let { it.seekTo(it.currentPosition) } }
        .show()
    buttons.getChildAt(3)?.requestFocus()
}

internal fun MainActivity.leavePlayer() {
    audioHeldFrom = null
    playerView.keepScreenOn = false
    hideLoadingOverlay(animate = false)
    progressHandler.removeCallbacks(progressTicker)
    stopPlayerExtras()
    saveProgress(pushNow = true)
    scrobble("stop")
    scrobbleItem = null
    playingId = null
    playingTarget = null
    playerView.player = null
    player?.release()
    player = null
    (playerFrame.parent as? ViewGroup)?.removeView(playerFrame)
    setContentView(contentScroll)
    when (playerReturnScreen) {
        "streams" -> showStreamsScreen()
        "tv_details" -> showTvDetails(lastTvId, lastFilmTitle, lastPosterFile)
        "details" -> showFilmDetails(lastFilmTitle, lastPosterFile)
        else -> showHomeScreen()
    }
}

// While the player controls are hidden: OK pauses/resumes, left/right skip 10 seconds.
internal fun MainActivity.handlePlayerKey(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_UP && event.keyCode == swallowKeyUp) {
        swallowKeyUp = -1
        return true
    }
    if (event.action != KeyEvent.ACTION_DOWN || playerView.isControllerFullyVisible) {
        return false
    }
    val p = player ?: return false
    when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
            // With Skip intro / Next episode on screen, OK presses it.
            if (playerAction.visibility == View.VISIBLE) {
                playerActionRun?.invoke()
                swallowKeyUp = event.keyCode
                return true
            }
            if (p.isPlaying) p.pause() else p.play()
        }
        KeyEvent.KEYCODE_DPAD_LEFT -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
        KeyEvent.KEYCODE_DPAD_RIGHT -> p.seekTo(p.currentPosition + 10_000)
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_MENU -> {
            showSubtitleMenu()
            swallowKeyUp = event.keyCode
            return true
        }
        else -> return false
    }
    playerView.showController()
    // The matching key-up would otherwise press whichever control just got focus.
    swallowKeyUp = event.keyCode
    return true
}
