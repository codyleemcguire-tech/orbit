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

// The sources page: asking addons for streams (StreamSearch), the list of streams with
// addon tabs, and starting one in Orbit's player or another app.

// Streams screen: asks every stream addon at once and lists their links as they arrive,
// grouped by addon in the order of the Stremio account (as Stremio does).
internal fun MainActivity.showStreams(
    type: String,
    label: String,
    target: WatchTarget? = null,
    resolveId: () -> String?
) {
    streamsTarget = target
    streamsWatchId = null
    streamsReturnScreen = currentScreen
    streamsLabel = label
    streamGroups.clear()
    streamAddonOrder = emptyList()
    streamTab = null
    streamsTouched = false
    streamsPending = -1
    streamsNote = "Finding streams..."
    streamFocusKey = null
    playbackError = null
    autoPlayStarted = false
    val request = ++streamsRequest
    val opened = System.currentTimeMillis()
    showStreamsScreen()

    thread {
        val id = try {
            resolveId()
        } catch (_: Exception) {
            null
        }
        Handler(Looper.getMainLooper()).post {
            if (request != streamsRequest) return@post
            streamsWatchId = id
            if (id == null) {
                streamsPending = 0
                streamsNote = "Couldn't find this title's IMDb id, so your addons can't be asked for it."
                if (currentScreen == "streams") updateStreamsStatus()
                return@post
            }
            // Fetch addon subtitles now, while the viewer picks a stream.
            streamsSubtitles = if (subtitleSettings.addonSubtitles) {
                streamLoader.submit<List<StremioSubtitles.Subtitle>> {
                    try {
                        StremioSubtitles.fetch(type, id)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            } else null
            // A running or recent search for this title (see StreamSearch) shows at once.
            val session = StreamSearch.get(type, id)
            streamsSession?.let { old -> streamsListener?.let { old.stop(it) } }
            val listener = object : StreamSearch.Listener {
                override fun onAddons(addons: List<StremioStreams.Addon>) {
                    if (request != streamsRequest) return
                    streamsPending = session.pending
                    streamAddonOrder = addons.map { it.name }
                    streamsNote = if (addons.isEmpty()) "None of your Stremio addons offer streams for this." else null
                    if (currentScreen == "streams") updateStreamsStatus()
                    maybeAutoPlay(request, opened)
                }

                override fun onResult(addon: StremioStreams.Addon, streams: List<StremioStreams.Stream>) {
                    if (request != streamsRequest) return
                    streamsPending = session.pending
                    if (streams.isNotEmpty() && streamGroups.none { it.first == addon.name }) {
                        // Keep the account's addon order, whatever order answers arrive in.
                        streamGroups.add(addon.name to streams)
                        streamGroups.sortBy { streamAddonOrder.indexOf(it.first) }
                        if (currentScreen == "streams") insertStreamGroup(addon.name, streams)
                    }
                    if (currentScreen == "streams") updateStreamsStatus()
                    maybeAutoPlay(request, opened)
                }
            }
            streamsSession = session
            streamsListener = listener
            session.observe(listener)
            // The source played last time, if recent: straight to it (NuvioTV's "reuse last
            // link"). The search carries on, so Back from the player shows every source.
            val last = if (target != null) StreamPick.recall(prefs, id, playerSettings.reuseLinkHours) else null
            if (last != null && currentScreen == "streams") {
                autoPlayStarted = true
                streamsNote = "Playing the source you used last time (${last.addonName})."
                updateStreamsStatus()
                playStream(last)
                return@post
            }
            // Auto-play also decides once the wait is up, whatever's still out.
            if (playerSettings.autoPlay != StreamPick.off) {
                progressHandler.postDelayed({ maybeAutoPlay(request, opened) }, playerSettings.autoPlayWaitS * 1000L + 50)
            }
        }
    }
}

// Builds the screen once; addons' results are slotted in as they arrive (insertStreamGroup)
// rather than rebuilding the whole list each time, which briefly froze the remote.
internal fun MainActivity.showStreamsScreen() {
    useContentScroll()
    currentScreen = "streams"
    root.removeAllViews()
    // Other screens (the splash, profiles) centre their content.
    root.gravity = Gravity.TOP

    val backLink = TextView(this).apply {
        text = "‹   Back"
        isFocusable = true
        isClickable = true
        setOnClickListener { leaveStreams() }
    }
    styleLinkFocus(backLink)
    root.addView(backLink, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = -22 })
    streamsBack = backLink

    root.addView(Ui.text(this, Ui.Type.Overline, "CHOOSE A SOURCE", Ui.accent).apply {
        setPadding(0, 28, 0, 0)
    })
    root.addView(Ui.text(this, Ui.Type.Title, streamsLabel, maxLines = 1).apply {
        setPadding(0, 12, 0, 10)
    })

    val status = Ui.text(this, Ui.Type.Label, "", Ui.textSecondary).apply {
        setPadding(0, 0, 0, 22)
    }
    root.addView(status)
    streamsStatus = status

    // Addon tabs, as in Stremio: All, then one per addon that found streams.
    val tabs = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(Ui.gutter, 6, Ui.gutter, 12)
    }
    root.addView(HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        addView(tabs)
    }, LinearLayout.LayoutParams(-1, -2).apply {
        leftMargin = -Ui.gutter
        rightMargin = -Ui.gutter
    })
    streamTabs = tabs
    tabs.addView(makeStreamTab(null))

    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    root.addView(list, LinearLayout.LayoutParams(-1, -2))
    streamsList = list

    streamGroups.forEach { (addonName, streams) -> insertStreamGroup(addonName, streams) }
    updateStreamTabs()
    updateStreamsStatus()
    if (currentFocus == null || !isDescendantOf(currentFocus!!, root)) backLink.requestFocus()
}

// Auto-play (StreamPick): once every addon has answered, or the wait is up, start the chosen
// stream, unless the viewer has pressed a button on this page (they're choosing).
internal fun MainActivity.maybeAutoPlay(request: Int, opened: Long) {
    val ps = playerSettings
    if (ps.autoPlay == StreamPick.off || autoPlayStarted || streamsTouched || request != streamsRequest ||
        currentScreen != "streams" || streamsTarget == null) return
    val waitedEnough = System.currentTimeMillis() - opened >= ps.autoPlayWaitS * 1000L
    if (streamsPending != 0 && !waitedEnough) return
    val pick = StreamPick.choose(streamGroups, ps.autoPlay, ps.autoPlayMaxGb, ps.autoPlayAddon)
    autoPlayStarted = true
    if (pick == null) {
        if (streamGroups.isNotEmpty()) {
            streamsNote = "No source matched your auto-play settings; choose one below."
            updateStreamsStatus()
        }
        return
    }
    val what = listOf(pick.addonName, Ui.streamBadges("${pick.name} ${pick.description}").joinToString(" · ") { it.first })
        .filter { it.isNotBlank() }.joinToString("  ·  ")
    streamsNote = "Auto-playing: $what. Press any button to choose yourself."
    updateStreamsStatus()
    progressHandler.postDelayed({
        if (request != streamsRequest || currentScreen != "streams") return@postDelayed
        if (streamsTouched) {
            streamsNote = null
            updateStreamsStatus()
        } else playStream(pick)
    }, 1500)
}

internal fun MainActivity.updateStreamsStatus() {
    val status = streamsStatus ?: return
    val found = streamGroups.sumOf { it.second.size }
    status.text = listOfNotNull(
        playbackError,
        streamsNote,
        when {
            streamsPending > 0 -> "$found streams so far · waiting for $streamsPending " +
                (if (streamsPending == 1) "addon" else "addons") + "..."
            streamsPending == 0 && found == 0 -> "No streams found."
            streamsPending == 0 -> "$found streams · OK plays in " + when (val with = playerSettings.playWith) {
                "Orbit" -> "Orbit"
                "Ask each time" -> "the player you pick"
                else -> if (installedExternalPlayers().any { it.name == with }) with else "Orbit"
            } + ", hold OK to open in another app."
            else -> null
        }
    ).joinToString("\n")
    streamTabs?.getChildAt(0)?.let { (it as TextView).text = "All  ·  $found" }
    status.setTextColor(if (playbackError != null) Color.rgb(255, 170, 150) else Color.LTGRAY)
}

internal fun MainActivity.insertStreamGroup(addonName: String, streams: List<StremioStreams.Stream>) {
    val list = streamsList ?: return
    val group = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        tag = addonName
    }
    group.addView(Ui.text(this, Ui.Type.Overline, "${addonName.uppercase()}  ·  ${streams.size}", Ui.textMuted).apply {
        setPadding(0, 30, 0, 14)
    })
    var restore: View? = null
    // Rows are made as the viewer gets near them (as NuvioTV's lazy list does): a few per
    // addon at first, more each time the last one made is reached. Making all ~800 rows of
    // a popular film at once froze the screen for seconds.
    var made = 0
    lateinit var makeMore: (Int) -> Unit
    makeMore = { count ->
        val until = minOf(streams.size, made + count)
        for (i in made until until) {
            val stream = streams[i]
            val key = stream.url ?: stream.youtubeId
            val isLast = i == until - 1
            val row = makeStreamRow(stream) {
                streamFocusKey = key
                if (isLast && made < streams.size) group.post { makeMore(streamRowBatch) }
            }
            group.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
            if (key != null && key == streamFocusKey) restore = row
        }
        made = until
    }
    // Back from the player: enough rows to include the stream that was playing.
    val playedAt = streams.indexOfFirst { (it.url ?: it.youtubeId) == streamFocusKey }
    makeMore(maxOf(streamRowsFirst, playedAt + 1))

    // The stream at the top of the list before this group arrives: while the viewer is
    // still on it (or hasn't reached the list), focus stays on whatever is at the top.
    fun topStream(): View? {
        for (i in 0 until list.childCount) {
            val g = list.getChildAt(i) as ViewGroup
            if (g.visibility == View.VISIBLE) return g.getChildAt(1)
        }
        return null
    }
    val oldTop = topStream()
    val focusBefore = currentFocus
    val atTop = focusBefore == null || focusBefore === streamsBack || focusBefore === oldTop

    // Slot the group in at its addon's place in the account order.
    val rank = streamAddonOrder.indexOf(addonName)
    var position = list.childCount
    for (i in 0 until list.childCount) {
        if (streamAddonOrder.indexOf(list.getChildAt(i).tag as String) > rank) {
            position = i
            break
        }
    }
    list.addView(group, position)
    group.visibility = if (streamTab == null || streamTab == addonName) View.VISIBLE else View.GONE
    streamTabs?.let { tabs ->
        // Tabs follow the same order; position + 1 skips the All tab.
        tabs.addView(makeStreamTab(addonName, streams.size), position + 1)
        updateStreamTabs()
    }
    // A group added above the focused stream pushes it down; keep it on screen.
    currentFocus?.takeUnless { atTop }?.let { focused ->
        if (isDescendantOf(focused, list)) focused.post {
            focused.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, focused.width, focused.height)
            )
        }
    }

    when {
        // Back from the player: the stream that was playing.
        restore != null && group.visibility == View.VISIBLE -> restore!!.requestFocus()
        // Otherwise the list opens at its top stream, and stays there as more addons
        // answer, until the viewer moves down it or onto the tabs.
        atTop || !streamsTouched -> {
            topStream()?.requestFocus()
            // After the next layout: focusing a row scrolls it to the top edge, which
            // would hide the title and addon tabs above it.
            contentScroll.viewTreeObserver.addOnGlobalLayoutListener(object :
                android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    contentScroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    contentScroll.scrollTo(0, 0)
                }
            })
        }
    }
}

// addonName null is the All tab.
internal fun MainActivity.makeStreamTab(addonName: String?, count: Int = 0) =
    Ui.pill(this, if (addonName == null) "All" else "$addonName  ·  $count") {
        selectStreamTab(addonName)
    }.apply {
        id = View.generateViewId()
        tag = addonName
        onFocusChangeListener = View.OnFocusChangeListener { v, _ -> paintStreamTab(v as TextView) }
        paintStreamTab(this)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 }
    }

internal fun MainActivity.paintStreamTab(tab: TextView) = Ui.paintPill(tab, tab.tag == streamTab)

internal fun MainActivity.selectStreamTab(addonName: String?) {
    streamTab = addonName
    val list = streamsList ?: return
    for (i in 0 until list.childCount) {
        val group = list.getChildAt(i)
        group.visibility =
            if (addonName == null || group.tag == addonName) View.VISIBLE else View.GONE
    }
    updateStreamTabs()
    contentScroll.smoothScrollTo(0, 0)
}

// Repaints the tabs and links them with the list: Down from any tab goes to the first
// visible stream, Up from the first visible stream goes to the selected tab.
internal fun MainActivity.updateStreamTabs() {
    val tabs = streamTabs ?: return
    val list = streamsList ?: return
    var firstRow: View? = null
    for (i in 0 until list.childCount) {
        val group = list.getChildAt(i) as ViewGroup
        for (r in 1 until group.childCount) group.getChildAt(r).nextFocusUpId = View.NO_ID
        if (firstRow == null && group.visibility == View.VISIBLE) firstRow = group.getChildAt(1)
    }
    var selectedTab: View? = null
    for (i in 0 until tabs.childCount) {
        val tab = tabs.getChildAt(i) as TextView
        paintStreamTab(tab)
        tab.nextFocusLeftId = View.NO_ID
        tab.nextFocusRightId = View.NO_ID
        if (tab.tag == streamTab) selectedTab = tab
        tab.nextFocusDownId = firstRow?.let {
            if (it.id == View.NO_ID) it.id = View.generateViewId()
            it.id
        } ?: View.NO_ID
    }
    // Tabs are a row: left/right stay inside it.
    tabs.getChildAt(0)?.let { it.nextFocusLeftId = it.id }
    tabs.getChildAt(tabs.childCount - 1)?.let { it.nextFocusRightId = it.id }
    firstRow?.nextFocusUpId = selectedTab?.id ?: View.NO_ID
}

// Stream card: quality on the left; release name, source and details in the middle;
// badges (Dolby Vision, HDR, Atmos…) under them; size on the right.
internal fun MainActivity.makeStreamRow(stream: StremioStreams.Stream, onFocused: () -> Unit): LinearLayout {
    val all = "${stream.name}\n${stream.description}"
    val badges = Ui.streamBadges(all)
    val quality = badges.firstOrNull { it.first in setOf("4K", "1080p", "720p", "SD") }
    val lines = stream.description.lines().map { it.trim() }.filter { it.isNotEmpty() }
    // The release/file name is usually the first line; drop the addon's leading emoji.
    val title = (lines.firstOrNull() ?: stream.name)
        .replace(Regex("^[^\\p{L}\\p{N}\\[(]+"), "")
        .ifBlank { stream.name.ifBlank { stream.addonName } }
    val details = lines.drop(1).joinToString("   ")

    val qualityBox = Ui.text(this, Ui.Type.Headline, quality?.first ?: "—", quality?.second ?: Ui.textMuted).apply {
        gravity = Gravity.CENTER
        textSize = 18f
        background = Ui.rounded(Ui.surfaceRaised, 10f)
    }
    val middle = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(26, 0, 20, 0)
    }
    middle.addView(Ui.text(this, Ui.Type.BodyStrong, title, Ui.textPrimary, maxLines = 1).apply { textSize = 16f })
    middle.addView(Ui.text(this, Ui.Type.Caption, stream.name.ifBlank { stream.addonName }, Ui.textMuted, maxLines = 1).apply {
        setPadding(0, 8, 0, 0)
    })
    if (details.isNotBlank()) {
        middle.addView(Ui.text(this, Ui.Type.Caption, details, Ui.textSecondary, maxLines = 2).apply {
            textSize = 13f
            setLineSpacing(4f, 1f)
            setPadding(0, 8, 0, 0)
        })
    }
    val extra = badges.filter { it != quality }
    if (extra.isNotEmpty()) {
        middle.addView(Ui.chipRow(this, extra.map { (label, tone) -> Ui.chip(this, label, tone) }).apply {
            setPadding(0, 12, 0, 0)
        })
    }
    val size = Ui.text(this, Ui.Type.Label, Ui.streamSize(all) ?: "", Ui.textSecondary).apply {
        gravity = Gravity.END
    }

    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(20, 18, 28, 18)
        isFocusable = true
        isClickable = true
        isLongClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        background = Ui.rounded(Ui.surface, Ui.radius)
        addView(qualityBox, LinearLayout.LayoutParams(130, 92))
        addView(middle, LinearLayout.LayoutParams(0, -2, 1f))
        addView(size, LinearLayout.LayoutParams(-2, -2))
        setOnFocusChangeListener { view, focused ->
            view.background = Ui.rounded(
                if (focused) Ui.surfaceRaised else Ui.surface, Ui.radius,
                stroke = if (focused) 4 else 0, strokeColor = Color.WHITE
            )
            if (focused) onFocused()
        }
        setOnClickListener { playStream(stream) }
        setOnLongClickListener {
            openStreamElsewhere(stream)
            true
        }
    }
}

internal fun MainActivity.playStream(stream: StremioStreams.Stream) {
    playbackError = null
    playingStream = stream
    val url = stream.url
    if (url == null) {
        if (stream.youtubeId != null) openStreamElsewhere(stream)
        return
    }
    // Trailers aren't the title itself, so they don't count towards progress.
    val trailer = stream.youtubeId != null || stream.name.contains("trailer", ignoreCase = true)
    val id = streamsWatchId.takeUnless { trailer }
    val target = streamsTarget.takeUnless { trailer }
    val saved = id?.let { progress.get(it) }
    if (id != null) StreamPick.remember(prefs, id, stream)
    // Addon subtitles are normally fetched by now; wait up to 3 s if not.
    val play = { startAt: Long ->
        val pending = streamsSubtitles.takeUnless { trailer }
        playWithChosenPlayer(trailer, { player ->
            startExternalPlayer(player, url, stream, id, target, startAt, addonSubtitlesFrom(pending))
        }) {
            if (pending == null || pending.isDone) {
                startPlayback(url, stream.headers, id, target, startAt, addonSubtitlesFrom(pending))
            } else {
                playbackError = null
                updateStreamsStatus()
                streamsStatus?.text = "Loading subtitles..."
                thread {
                    val subs = try {
                        pending.get(3, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    Handler(Looper.getMainLooper()).post {
                        if (currentScreen == "streams") startPlayback(url, stream.headers, id, target, startAt, subs)
                    }
                }
            }
        }
    }
    if (saved == null || !saved.resumable) {
        play(0L)
        return
    }
    android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(target?.episodeLabel?.ifBlank { null } ?: target?.title ?: streamsLabel)
        .setItems(arrayOf(
            "Resume from ${WatchProgress.format(saved.positionMs)}",
            "Start from beginning"
        )) { _, which ->
            play(if (which == 0) saved.positionMs else 0L)
        }
        .show()
}

internal fun MainActivity.installedExternalPlayers(): List<PlayerSettings.Companion.ExternalPlayer> =
    PlayerSettings.externalPlayers.filter { player -> player.packages.any { isInstalled(it) } }

internal fun MainActivity.isInstalled(pkg: String) = try {
    packageManager.getPackageInfo(pkg, 0)
    true
} catch (_: Exception) {
    false
}

// Runs inOrbit, or external with the player chosen in Settings (asking first with
// "Ask each time"). Trailers always play in Orbit.
internal fun MainActivity.playWithChosenPlayer(
    trailer: Boolean,
    external: (PlayerSettings.Companion.ExternalPlayer) -> Unit,
    inOrbit: () -> Unit
) {
    val installed = installedExternalPlayers()
    val setting = playerSettings.playWith
    when {
        trailer || installed.isEmpty() || setting == "Orbit" -> inOrbit()
        setting == "Ask each time" -> {
            val names = listOf("Orbit") + installed.map { it.name }
            choose("Play with", names, 0) { if (it == 0) inOrbit() else external(installed[it - 1]) }
        }
        else -> installed.firstOrNull { it.name == setting }?.let(external) ?: inOrbit()
    }
}

// Plays in another app and asks it to report back where the viewer stopped (MX Player's
// and VLC's result extras); see onActivityResult. What's playing is saved in prefs, since
// Orbit may be closed by Android while the other player is open.
internal fun MainActivity.startExternalPlayer(
    player: PlayerSettings.Companion.ExternalPlayer,
    url: String,
    stream: StremioStreams.Stream,
    id: String?,
    target: WatchTarget?,
    startAt: Long,
    subtitles: List<StremioSubtitles.Subtitle>
) {
    val pkg = player.packages.first { isInstalled(it) }
    val title = listOfNotNull(target?.title, target?.episodeLabel?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
        .ifBlank { stream.name }
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        .setDataAndType(android.net.Uri.parse(url), "video/*")
        .setPackage(pkg)
        .putExtra("title", title)
    val subs = listOf(subtitleSettings.language, subtitleSettings.secondLanguage).filter { it.isNotBlank() }
        .let { langs -> AddonSubtitleTrack.rank(StremioSubtitles.pick(subtitles, langs, subtitleSettings.onlyPreferred,
            perPreferred = 3, total = 6), stream.filename) }
    if (player.vlcApi) {
        if (startAt > 0) intent.putExtra("position", startAt)
        subs.firstOrNull()?.let { intent.putExtra("subtitles_location", it.url) }
    } else {
        intent.putExtra("return_result", true)
        if (startAt > 0) intent.putExtra("position", startAt.toInt())
        if (stream.headers.isNotEmpty()) {
            intent.putExtra("headers", stream.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
        }
        if (subs.isNotEmpty()) {
            intent.putExtra("subs", subs.map { android.net.Uri.parse(it.url) }.toTypedArray<android.os.Parcelable>())
            intent.putExtra("subs.name", subs.map { "${SubtitleSettings.languageName(it.language)} · ${it.addonName}" }.toTypedArray())
            intent.putExtra("subs.enable", arrayOf<android.os.Parcelable>(android.net.Uri.parse(subs.first().url)))
        }
    }
    if (id != null && target != null) {
        prefs.edit().putString("external_playing", org.json.JSONObject()
            .put("id", id).put("type", target.type).put("title", target.title)
            .put("episodeLabel", target.episodeLabel).put("poster", target.poster).put("tvId", target.tvId)
            .put("season", target.season).put("episode", target.episode).put("startAt", startAt)
            .put("vlc", player.vlcApi).toString()).commit()
        Trakt.Item.from(id, target.type)?.let { item ->
            val known = progress.get(id)?.durationMs?.takeIf { it > 0 }
            trakt.scrobble("start", item, if (known != null) startAt * 100f / known else 0f)
        }
    } else prefs.edit().remove("external_playing").commit()
    try {
        @Suppress("DEPRECATION")
        startActivityForResult(intent, externalPlayerRequest)
    } catch (_: Exception) {
        prefs.edit().remove("external_playing").commit()
        playbackError = "Couldn't open ${player.name}."
        updateStreamsStatus()
    }
}

internal fun MainActivity.addonSubtitlesFrom(
    future: java.util.concurrent.Future<List<StremioSubtitles.Subtitle>>?
): List<StremioSubtitles.Subtitle> = try {
    future?.takeIf { it.isDone }?.get().orEmpty()
} catch (_: Exception) {
    emptyList()
}

// Hands the link to another app (Kodi, a YouTube app for trailers, …).
internal fun MainActivity.openStreamElsewhere(stream: StremioStreams.Stream) {
    val intent = when {
        stream.url != null -> android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(android.net.Uri.parse(stream.url), "video/*")
        stream.youtubeId != null -> android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://www.youtube.com/watch?v=${stream.youtubeId}")
        )
        else -> return
    }
    try {
        startActivity(android.content.Intent.createChooser(intent, "Open with"))
    } catch (_: Exception) {
        playbackError = "No other app on this TV can open that stream."
        updateStreamsStatus()
    }
}

internal fun MainActivity.leaveStreams() {
    streamsRequest++
    streamsListener?.let { l -> streamsSession?.stop(l) }
    streamsSession = null
    streamsListener = null
    when (streamsReturnScreen) {
        "home" -> showHomeScreen()
        "tv_details" -> showTvDetails(lastTvId, lastFilmTitle, lastPosterFile)
        else -> showFilmDetails(lastFilmTitle, lastPosterFile)
    }
}
