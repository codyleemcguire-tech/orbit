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

class MainActivity : Activity() {
    internal var player: ExoPlayer? = null
    internal lateinit var root: LinearLayout
    internal lateinit var playerView: PlayerView
    internal var currentScreen = "home"
    internal var lastFilmTitle = ""
    internal var lastPosterFile = ""
    internal var playerReturnScreen = "home"
    internal var selectedSeasonButtonId: Int = View.NO_ID

    // The scroll view that hosts every non-player screen; the player replaces it while playing.
    internal lateinit var contentScroll: ScrollView
    internal lateinit var homePlayButton: View
    internal lateinit var homeRowsScroll: ScrollView
    internal val homeRowItems = mutableListOf<List<TmdbApi.CatalogueItem>>()
    internal var heroItem: TmdbApi.CatalogueItem? = null
    internal lateinit var heroBackdrop: ImageView
    internal lateinit var heroOverline: TextView
    internal lateinit var heroTitle: TextView
    internal lateinit var heroLogo: ImageView
    internal lateinit var heroChips: LinearLayout
    internal lateinit var heroOverview: TextView
    internal val homeRows = mutableListOf<LinearLayout>()
    internal val homeRowHeadings = mutableListOf<View>()

    // Where focus was on the home screen, so Back lands on the same poster. Row -1 = play button.
    internal var homeFocusRow = -1
    // The row's heading, so focus returns to the same row even if Continue rows
    // appear or disappear in between.
    internal var homeFocusRowName: String? = null
    internal var homeFocusIndex = 0
    internal var pendingHomeRestore = false
    internal var cachedMovies: List<TmdbApi.CatalogueItem>? = null
    internal var cachedShows: List<TmdbApi.CatalogueItem>? = null

    internal val seasonByShow = mutableMapOf<Int, Int>()
    internal var episodeFocusIndex = -1
    internal var swallowKeyUp = -1

    // Streams screen state, kept so returning from the player shows the same list.
    internal var lastFilmImdbId: String? = null

    // What the streams screen is finding sources for, and the addon id once resolved;
    // the player saves progress against them.
    data class WatchTarget(
        val type: String,
        val title: String,
        val episodeLabel: String = "",
        val poster: String = "",
        val tvId: Int = -1,
        val season: Int = -1,
        val episode: Int = -1
    )
    // The profile in use; switching profiles recreates the activity with the new one.
    internal val profile by lazy { Profiles.active(this) }
    internal val progress by lazy { WatchProgress(this, Profiles.progressName(profile.id)) }
    // My List and progress, synced with the Stremio library when signed in.
    internal val library by lazy { StremioLibrary(prefs, progress, BuildConfig.TMDB_API_KEY) }
    // Trakt scrobbling, watchlist and paused titles, when signed in.
    internal val trakt by lazy { Trakt(prefs, progress, library, BuildConfig.TMDB_API_KEY) }
    // TorBox: cached torrent links and TorBox's search (see TorBox, StreamSearch).
    internal val torBox by lazy { TorBox(prefs) }
    internal val subtitleSettings by lazy { SubtitleSettings(prefs) }
    internal val playerSettings by lazy { PlayerSettings(prefs) }
    internal val tmdbSettings by lazy { TmdbSettings(prefs) }
    internal var lastFilmTmdbId: Int? = null

    // Player overlay: the player plus a Skip intro / Next episode button over it.
    internal lateinit var playerFrame: FrameLayout
    internal lateinit var playerAction: TextView
    internal var playerActionRun: (() -> Unit)? = null
    internal var playingStream: StremioStreams.Stream? = null
    internal var playingSegments: List<IntroDb.Segment> = emptyList()
    internal val autoSkipped = mutableSetOf<String>()
    internal var nextEpisode: NextEpisode? = null
    internal var originalDisplayMode = 0

    // Addon subtitles for the playing video (ranked, best release match first), the one
    // being shown, and the delay applied to it.
    internal var playingAddonSubs: List<StremioSubtitles.Subtitle> = emptyList()
    internal var activeAddonTrack: AddonSubtitleTrack? = null
    internal var subtitleDelayMs = 0L
    // Lip sync: + plays the sound later, − earlier (NuvioTV's audio delay). Remembered,
    // since it depends on the TV and sound system rather than the video.
    internal var audioDelayMs: Long
        get() = prefs.getLong("audio_delay_ms", 0L)
        set(v) { prefs.edit().putLong("audio_delay_ms", v).apply() }
    internal val loadedAddonTracks = mutableMapOf<String, AddonSubtitleTrack>()
    internal var shownAddonLines: List<String>? = null
    internal val addonSubtitleTicker = object : Runnable {
        override fun run() {
            val track = activeAddonTrack ?: return
            val p = player ?: return
            val lines = track.at(p.currentPosition - subtitleDelayMs)
            // Redrawn only when the lines change: setting cues ten times a second made the
            // subtitle view lay itself out over and over while the video played.
            if (lines != shownAddonLines) {
                shownAddonLines = lines
                val cues = lines.map { androidx.media3.common.text.Cue.Builder().setText(it).build() }
                playerView.subtitleView?.setCues(subtitleSettings.filterCues(cues))
            }
            progressHandler.postDelayed(this, 100)
        }
    }

    // The episode after the one playing, and a stream for it once found.
    internal data class NextEpisode(
        val target: WatchTarget,
        val id: String,
        @Volatile var stream: StremioStreams.Stream? = null,
        @Volatile var subtitles: List<StremioSubtitles.Subtitle> = emptyList(),
        @Volatile var searched: Boolean = false
    )
    internal var streamsTarget: WatchTarget? = null
    internal var streamsWatchId: String? = null
    internal var streamsSubtitles: java.util.concurrent.Future<List<StremioSubtitles.Subtitle>>? = null
    internal var playingTarget: WatchTarget? = null
    internal var playingId: String? = null
    internal val progressHandler = Handler(Looper.getMainLooper())
    internal val showImdbIds = java.util.concurrent.ConcurrentHashMap<Int, String>()
    internal var streamsReturnScreen = "details"
    internal var streamsLabel = ""
    internal val streamGroups = mutableListOf<Pair<String, List<StremioStreams.Stream>>>()
    internal var streamsPending = -1
    internal var streamsNote: String? = null
    // The focused stream's link, so focus stays on it while other addons' results arrive.
    internal var streamFocusKey: String? = null
    internal var streamsStatus: TextView? = null
    internal var streamsList: LinearLayout? = null
    internal var streamsBack: View? = null
    internal var streamAddonOrder: List<String> = emptyList()
    // Selected addon tab on the streams screen; null = All.
    internal var streamTab: String? = null
    internal var streamTabs: LinearLayout? = null
    // False until the viewer presses a key on the streams screen; until then focus follows
    // the top stream as slower addons' results are inserted above the fast ones.
    internal var streamsTouched = false
    internal var streamsRequest = 0
    internal var playbackError: String? = null
    // All stream addons are asked at once; each answers within a few seconds.
    internal val streamLoader = java.util.concurrent.Executors.newFixedThreadPool(6)
    // The stream search the sources page is showing, and its listener (see StreamSearch).
    internal var streamsSession: StreamSearch.Session? = null
    internal var streamsListener: StreamSearch.Listener? = null

    // Which top-level screen details pages go back to.
    internal var detailsReturnScreen = "home"

    // Discover state, kept so returning from a title restores filters, results and position.
    // Five larger posters per row, beside the info panel.
    internal val discoverColumns = 5
    internal val discoverPosterWidth = 224
    internal var discoverCatalogs: List<StremioAddons.Catalog>? = null
    internal var discoverType = "movie"
    internal var discoverCatalogKey: String? = null
    internal var discoverGenre: String? = null
    internal val discoverResults = mutableListOf<StremioAddons.Meta>()
    internal var discoverHasMore = true
    internal var discoverLoading = false
    internal var discoverFocusIndex = -1
    // Bumped whenever the filters change, so a page that arrives late is ignored.
    internal var discoverRequest = 0
    // Paging position in the addon's list. Kept apart from the result count because addons
    // filter some titles out of a page and reshuffle lists between requests.
    internal var discoverOffset = 0
    internal var discoverStep = 0
    internal var discoverEmptyPages = 0
    internal lateinit var discoverList: androidx.recyclerview.widget.RecyclerView
    internal lateinit var discoverHeader: LinearLayout
    // Poster index to focus once it's on screen; -1 = the Type picker, -2 = nothing.
    internal var pendingDiscoverFocus = -2
    internal lateinit var discoverStatus: TextView
    internal lateinit var typePicker: TextView
    internal lateinit var catalogPicker: TextView
    internal lateinit var genrePicker: TextView

    // Older code refers to these; they now come from the design system (Ui.kt).
    internal val bg = Ui.surface
    internal val accent = Ui.accent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        StremioAddons.diskCache = cacheDir
        StreamSearch.torBox = torBox

        window.statusBarColor = Ui.background
        window.navigationBarColor = Ui.background
        // Every screen paints its own background; the window's would be painted underneath
        // for nothing, and this stick's GPU is short on time per frame.
        window.setBackgroundDrawable(null)

        // The scrolling page that hosts details, streams, settings and actor screens.
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            clipChildren = false
            clipToPadding = false
            setPadding(Ui.gutter, 36, Ui.gutter, 48)
        }
        contentScroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            background = screenBackground()
            addView(root, ViewGroup.LayoutParams(-1, -2))
        }
        playerView = PlayerView(this).apply {
            // Orbit's own controls (PlayerControls) replace Media3's.
            useController = false
            isFocusable = true
            setBackgroundColor(Color.BLACK)
            // Rebuffering mid-video shows a small spinner; the first load has the overlay.
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        playerAction = Ui.button(this, "", primary = true) { playerActionRun?.invoke() }.apply {
            isFocusable = false // pressed with OK via handlePlayerKey, not by moving focus
            visibility = View.GONE
        }
        playerFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(playerControls.view, FrameLayout.LayoutParams(-1, -1))
            addView(pauseOverlay.view, FrameLayout.LayoutParams(-1, -1))
            addView(sidePanel.view, FrameLayout.LayoutParams(-1, -1))
            addView(playerAction, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = Ui.gutter
                bottomMargin = 170
            })
            addView(buildLoadingOverlay(), FrameLayout.LayoutParams(-1, -1))
        }

        applySavedAddons()
        syncLibrary()
        // Read addon manifests in the background so the first Play is quick.
        thread { StremioAddons.loadManifests() }
        // Opening Orbit: splash, then "Who's watching?", then Home. Switching profile
        // (which recreates the activity) goes straight to Home.
        // The very first time, a welcome and addon setup take the picker's place.
        when {
            Profiles.chosenThisLaunch -> showHomeScreen()
            Profiles.isFirstRun(this) -> showSplash { showWelcome() }
            else -> showSplash { showProfilePicker() }
        }
    }

    // App-private storage for the Stremio session key, addons and settings: the profile's own.
    internal val prefs by lazy { Profiles.prefs(this, profile.id) }

    // A few downloads at a time, so a full Discover grid doesn't start a thread per poster.
    internal val imageLoader = java.util.concurrent.Executors.newFixedThreadPool(4)

    // Recently shown posters, so revisiting a screen doesn't download them again.
    internal val posterCache = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    // Poster used by every row and the Discover grid: on focus it zooms, gets a bright ring
    // and coloured glow, shows its title and year underneath, and dims the rest of its row.
    internal class PosterCard(
        val card: LinearLayout,
        val frame: FrameLayout,
        val image: ImageView,
        val caption: TextView,
        val width: Int
    )

    // Text links ("‹ BACK TO …"): a filled pill when focused.
    // Back links ("‹ Back to Discover"): quiet text that becomes a white pill on focus.
    internal fun styleLinkFocus(link: TextView) {
        link.setPadding(22, 12, 26, 12)
        link.textSize = 16f
        link.typeface = Ui.font("semibold")
        link.includeFontPadding = false
        if (Build.VERSION.SDK_INT >= 26) link.defaultFocusHighlightEnabled = false
        val paint = { focused: Boolean ->
            link.setTextColor(if (focused) Ui.background else Ui.textSecondary)
            link.background = if (focused) Ui.rounded(Color.WHITE, Ui.pillRadius) else null
        }
        paint(link.isFocused)
        link.setOnFocusChangeListener { _, focused -> paint(focused) }
    }

    internal fun chainFocus(
        first: View.OnFocusChangeListener?,
        second: (View, Boolean) -> Unit
    ) = View.OnFocusChangeListener { view, focused ->
        first?.onFocusChange(view, focused)
        second(view, focused)
    }

    // Top bar shared by HOME, DISCOVER and SETTINGS: wordmark, then the tabs. Returns the
    // tabs so each screen can point their Down at its first control.
    internal fun addHeader(activeTab: String, into: LinearLayout = root): List<TextView> {
        val tabs = mutableListOf<TextView>()
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 28)
        }
        headerRow.addView(TextView(this).apply {
            text = android.text.SpannableString("orbit.").apply {
                setSpan(
                    android.text.style.ForegroundColorSpan(Ui.accent),
                    5, 6, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            textSize = 26f
            typeface = Ui.font("bold")
            letterSpacing = -0.03f
            includeFontPadding = false
            setTextColor(Ui.textPrimary)
        }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 44 })

        listOf("home" to "Home", "discover" to "Discover", "search" to "Search", "settings" to "Settings").forEach { (tab, label) ->
            val active = tab == activeTab
            val link = TextView(this).apply {
                text = label
                textSize = 15f
                typeface = Ui.font("semibold")
                includeFontPadding = false
                setPadding(24, 12, 24, 12)
                isFocusable = true
                isClickable = true
                if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                setOnClickListener {
                    if (!active) {
                        when (tab) {
                            "home" -> showHomeScreen()
                            "discover" -> showDiscoverScreen()
                            "search" -> showSearchScreen()
                            else -> showSettingsScreen()
                        }
                    }
                }
                val paint = { focused: Boolean ->
                    setTextColor(
                        when {
                            focused -> Ui.background
                            active -> Ui.textPrimary
                            else -> Ui.textSecondary
                        }
                    )
                    background = when {
                        focused -> Ui.rounded(Color.WHITE, Ui.pillRadius)
                        active -> Ui.rounded(Color.argb(36, 255, 255, 255), Ui.pillRadius)
                        else -> null
                    }
                }
                paint(false)
                setOnFocusChangeListener { _, focused -> paint(focused) }
            }
            headerRow.addView(link, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 6 })
            tabs.add(link)
        }
        // The profile in use, at the far right; it opens "Who's watching?".
        headerRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        val avatar = makeAvatar(profile, 52).apply {
            contentDescription = "Profile: ${profile.name}"
            setOnClickListener { showProfilePicker() }
        }
        headerRow.addView(avatar, LinearLayout.LayoutParams(52, 52))
        tabs.add(avatar)
        // Left/right stay within the tabs.
        tabs.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        tabs.first().nextFocusLeftId = tabs.first().id
        tabs.last().nextFocusRightId = tabs.last().id
        into.addView(headerRow)
        return tabs
    }

    internal fun pointTabsDownTo(tabs: List<TextView>, target: View) {
        if (target.id == View.NO_ID) target.id = View.generateViewId()
        tabs.forEach { it.nextFocusDownId = target.id }
    }

    // A faint cool glow in the top-left corner over the near-black background.
    // Plain near-black: backdrops fade into it without a visible edge.
    internal fun screenBackground() = android.graphics.drawable.ColorDrawable(Ui.background)

    // Discover shows its own recycling grid; the other screens live in contentScroll.
    internal fun useContentScroll() {
        stopDiscoverFocusWatcher()
        if (contentScroll.parent == null) setContentView(contentScroll)
    }

    internal var discoverFocusWatcher: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null

    internal fun stopDiscoverFocusWatcher() {
        discoverFocusWatcher?.let {
            window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(it)
        }
        discoverFocusWatcher = null
    }

    // HOME: a fixed hero (backdrop, title, details, Play) above rows that scroll beneath it.
    // The hero follows the focused poster after a short pause, like Google TV.
    // The built HOME is kept and put back as-is when nothing on it has changed: rebuilding
    // it (hundreds of collection tiles) froze the screen for over a second on Back.
    internal var homeFrame: FrameLayout? = null
    internal var homeDirty = true

    internal val collectionItems = java.util.IdentityHashMap<TmdbApi.CatalogueItem, Pair<NuvioCollections.Collection, NuvioCollections.Folder>>()
    internal var openFolder: Pair<NuvioCollections.Collection, NuvioCollections.Folder>? = null
    internal val folderCache = mutableMapOf<String, List<NuvioCollections.Loaded>>()
    internal var folderFocus: Pair<Int, Int>? = null

    internal var pendingCover: Runnable? = null
    internal val COLLECTION_BATCH = 24

    internal val lazyLoadPass = Runnable { runLazyLoads() }
    internal val lazyLoadHandler = Handler(Looper.getMainLooper())

    // A folder, laid out like Discover: a poster grid with the info panel on the right.
    // Instead of Discover's pickers it has a tab per source (plus All), as in Nuvio.
    internal var folderLoaded: MutableList<NuvioCollections.Loaded?> = mutableListOf()
    internal var folderTab = -1 // -1 = All, else the source's index
    internal var folderShown: List<StremioAddons.Meta> = emptyList()
    internal var folderFocusIndex = -1
    internal var folderPendingFocus = -2 // index to focus once bound; -1 = the tabs
    internal var folderRequest = 0
    internal lateinit var folderList: androidx.recyclerview.widget.RecyclerView
    internal lateinit var folderHeader: LinearLayout
    internal lateinit var folderTabs: LinearLayout
    internal lateinit var folderStatus: TextView

    internal var folderUpTarget = View.NO_ID
    // All tab's titles once every source's first page is in. Later pages are appended to
    // the end, so what the viewer has already scrolled past never moves.
    internal val folderAll = mutableListOf<StremioAddons.Meta>()
    internal val folderAllIds = mutableSetOf<String>()
    internal val folderPaging = mutableSetOf<Int>()
    internal val folderAllCache = mutableMapOf<String, List<StremioAddons.Meta>>()

    internal inner class FolderAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<FolderAdapter.Holder>() {

        inner class Holder(view: View, val poster: PosterCard?) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

        override fun getItemCount() = folderShown.size + 1

        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 0) {
                (folderHeader.parent as? ViewGroup)?.removeView(folderHeader)
                folderHeader.layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2)
                return Holder(folderHeader, null).apply { setIsRecyclable(false) }
            }
            val poster = createPosterCard(dimRow = false, width = discoverPosterWidth)
            poster.caption.visibility = View.GONE
            poster.card.layoutParams =
                androidx.recyclerview.widget.RecyclerView.LayoutParams(discoverPosterWidth, -2).apply {
                    topMargin = 30
                    bottomMargin = 8
                }
            val holder = Holder(poster.card, poster)
            poster.card.setOnClickListener {
                val index = holder.bindingAdapterPosition - 1
                val meta = folderShown.getOrNull(index) ?: return@setOnClickListener
                folderFocusIndex = index
                openMeta(meta, folderStatus, "collection")
            }
            poster.card.onFocusChangeListener = chainFocus(poster.card.onFocusChangeListener) { _, focused ->
                val index = holder.bindingAdapterPosition - 1
                if (focused && index >= 0) {
                    folderFocusIndex = index
                    folderShown.getOrNull(index)?.let { showDiscoverInfo(it) }
                }
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val poster = holder.poster ?: return
            val index = position - 1
            val meta = folderShown[index]
            bindPosterCard(poster, meta.name, meta.year, meta.poster)
            val card = poster.card
            val column = index % discoverColumns
            card.nextFocusLeftId = if (column == 0) card.id else View.NO_ID
            card.nextFocusRightId =
                if (column == discoverColumns - 1 || index == folderShown.lastIndex) card.id else View.NO_ID
            // Up from the top row: the header may have scrolled away (e.g. after returning
            // from a title), so scroll it back and then focus the selected tab.
            card.setOnKeyListener(if (index < discoverColumns) View.OnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP || event.action != KeyEvent.ACTION_DOWN) {
                    return@OnKeyListener false
                }
                folderList.scrollToPosition(0)
                folderList.post { findViewById<View>(folderUpTarget)?.requestFocus() }
                true
            } else null)
            // Down from the tabs or back link lands on the first poster.
            if (index == 0) {
                for (i in 0 until folderTabs.childCount) folderTabs.getChildAt(i).nextFocusDownId = card.id
                folderHeader.getChildAt(0).nextFocusDownId = card.id
            }
            if (index == folderPendingFocus) {
                folderPendingFocus = -2
                card.post { card.requestFocus() }
            }
            // Within five rows of the end, fetch more so it's there before it's reached.
            if (index >= folderShown.size - 5 * discoverColumns) folderList.post { loadMoreFolder() }
        }
    }

    // Continue watching: one poster per unfinished title with a progress bar. Each becomes
    // a hero item so the hero can show it (and its Play resumes it).
    internal val continueItems = java.util.IdentityHashMap<TmdbApi.CatalogueItem, WatchProgress.Entry>()
    internal val continueBackdrops = java.util.concurrent.ConcurrentHashMap<String, String>()  // "" = none found

    internal val myListItems = java.util.IdentityHashMap<TmdbApi.CatalogueItem, StremioLibrary.ListItem>()

    internal val heroHandler = Handler(Looper.getMainLooper())

    // Recycling grid: only the visible rows exist as views, so scrolling stays smooth however
    // many pages are loaded. Position 0 is the header; position i + 1 is discoverResults[i].
    internal inner class DiscoverAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<DiscoverAdapter.Holder>() {

        inner class Holder(view: View, val poster: PosterCard?) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(view)

        override fun getItemCount() = discoverResults.size + 1

        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 0) {
                (discoverHeader.parent as? ViewGroup)?.removeView(discoverHeader)
                discoverHeader.layoutParams =
                    androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -2)
                return Holder(discoverHeader, null).apply { setIsRecyclable(false) }
            }
            val poster = createPosterCard(dimRow = false, width = discoverPosterWidth)
            // The info panel already names the highlighted title.
            poster.caption.visibility = View.GONE
            poster.card.layoutParams =
                androidx.recyclerview.widget.RecyclerView.LayoutParams(discoverPosterWidth, -2).apply {
                    topMargin = 30
                    bottomMargin = 8
                }
            val holder = Holder(poster.card, poster)
            poster.card.setOnClickListener {
                val index = holder.bindingAdapterPosition - 1
                if (index >= 0) openDiscoverItem(index)
            }
            poster.card.onFocusChangeListener =
                chainFocus(poster.card.onFocusChangeListener) { _, focused ->
                    val index = holder.bindingAdapterPosition - 1
                    if (focused && index >= 0) {
                        discoverFocusIndex = index
                        discoverResults.getOrNull(index)?.let { showDiscoverInfo(it) }
                    }
                }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val poster = holder.poster
            if (poster == null) {
                if (pendingDiscoverFocus == -1) {
                    pendingDiscoverFocus = -2
                    typePicker.post { typePicker.requestFocus() }
                }
                return
            }
            val index = position - 1
            val meta = discoverResults[index]
            bindPosterCard(poster, meta.name, meta.year, meta.poster)

            // Left/right stop at the row ends; up from the first row goes to the picker
            // above it (the pickers only span the left of the screen).
            val card = poster.card
            val column = index % discoverColumns
            card.nextFocusLeftId = if (column == 0) card.id else View.NO_ID
            card.nextFocusRightId =
                if (column == discoverColumns - 1 || index == discoverResults.lastIndex) card.id
                else View.NO_ID
            card.nextFocusUpId = when {
                index >= discoverColumns -> View.NO_ID
                column == 0 -> typePicker.id
                column == 1 || genrePicker.visibility != View.VISIBLE -> catalogPicker.id
                else -> genrePicker.id
            }

            if (index == pendingDiscoverFocus) {
                pendingDiscoverFocus = -2
                card.post { card.requestFocus() }
            }
            // Within five rows of the end, load more so it's ready before it's reached.
            if (index >= discoverResults.size - 5 * discoverColumns) {
                discoverList.post { fetchDiscoverPage(reset = false) }
            }
        }
    }

    // Pill button used by Discover's pickers and the Settings actions.
    internal fun makePicker(onClick: () -> Unit) = Ui.pill(this, "") { onClick() }

    internal lateinit var infoBackdrop: ImageView
    internal lateinit var infoOverline: TextView
    internal lateinit var infoTitle: TextView
    internal lateinit var infoChips: LinearLayout
    internal lateinit var infoGenres: TextView
    internal lateinit var infoOverview: TextView
    internal val infoHandler = Handler(Looper.getMainLooper())

    // A remote-friendly list dialog; picking an entry closes it.
    internal fun choose(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        if (options.isEmpty()) return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .show()
    }

    // Search: one query sent to every addon catalog that supports search; results come
    // back as one row per catalog, in the account's addon order (as Stremio shows them).
    internal var searchQuery = ""
    internal var searchRequest = 0
    internal val searchRows = mutableListOf<Pair<StremioAddons.Catalog, List<StremioAddons.Meta>>>()
    internal var searchPending = 0
    internal var searchFocus: Pair<String, Int>? = null
    internal val searchHandler = Handler(Looper.getMainLooper())
    internal var searchCatalogsCache: List<StremioAddons.Catalog>? = null
    internal var moveToSearchResults = false

    internal val streamRowsFirst = 4
    internal val streamRowBatch = 20

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != externalPlayerRequest) return
        val saved = prefs.getString("external_playing", null)?.let { org.json.JSONObject(it) } ?: return
        prefs.edit().remove("external_playing").commit()
        if (data == null) return
        val extras = data.extras ?: return
        fun number(vararg keys: String): Long = keys.firstNotNullOfOrNull { key ->
            when (val v = extras.get(key)) { is Int -> v.toLong(); is Long -> v; else -> null }
        } ?: 0L
        val vlc = saved.optBoolean("vlc")
        val duration = if (vlc) number("extra_duration") else number("duration")
        var position = if (vlc) number("extra_position") else number("position")
        // MX Player and Just Player say when the video played to the end.
        if (extras.getString("end_by") == "playback_completion") position = duration
        if (duration <= 0 || (position <= 0 && duration > 0 && extras.getString("end_by") != "playback_completion")) return
        val entry = WatchProgress.Entry(
            saved.getString("id"), saved.getString("type"), saved.optString("title"), saved.optString("episodeLabel"),
            saved.optString("poster"), saved.optInt("tvId", -1), saved.optInt("season", -1), saved.optInt("episode", -1),
            position.coerceAtMost(duration), duration, System.currentTimeMillis()
        )
        progress.save(entry)
        library.pushProgress(entry, force = true)
        Trakt.Item.from(entry.id, entry.type)?.let { trakt.scrobble("stop", it, entry.fraction * 100f) }
        homeDirty = true
    }

    internal val externalPlayerRequest = 4711

    // What Trakt was last told about the playing title ("start", "pause" or "stop").
    internal var scrobbleItem: Trakt.Item? = null
    internal var scrobbleState: String? = null

    internal val progressTicker = object : Runnable {
        override fun run() {
            saveProgress()
            progressHandler.postDelayed(this, 10_000)
        }
    }

    // Backdrop header shared by film and show pages: back link, then title, chips, tagline,
    // overview and buttons laid over a full-width backdrop that fades into the page.
    internal class DetailHero(
        val back: TextView,
        val backdrop: ImageView,
        val overline: TextView,
        val title: TextView,
        val chips: LinearLayout,
        val genres: TextView,
        val tagline: TextView,
        val overview: TextView,
        val buttons: LinearLayout,
        val logo: ImageView
    )

    internal val youtubeApps = listOf(
        "io.gh.reisxd.tizentube.cobalt",            // TizenTube
        "com.teamsmart.videomanager.tv",            // SmartTube
        "com.liskovsoft.smarttubetv.beta",
        "com.liskovsoft.smarttubetv",
        "com.google.android.youtube.tv",
        "com.google.android.youtube.googletv"
    )

    internal var lastTvId: Int = -1

    // Actor pages opened so far, newest last, so Back retraces show → actor → film → actor.
    // Each remembers the page it was opened from and where Back from that page led.
    internal data class ActorPage(
        val name: String,
        val personId: Int,
        val profilePath: String?,
        val fromTitle: String,
        val fromPoster: String,
        val fromShow: Boolean,
        val fromTvId: Int,
        val fromTmdbId: Int?,
        val fromImdbId: String?,
        val castIndex: Int,
        val previousReturn: String,
        var focusRow: Int = -1,
        var focusIndex: Int = -1
    )
    internal val actorPages = ArrayDeque<ActorPage>()

    // A one-line text dialog; onDone gets the text when it isn't blank.
    // optional: OK with nothing typed still goes on (with "").
    internal fun promptText(title: String, hint: String, initial: String, numeric: Boolean = false, optional: Boolean = false, onDone: (String) -> Unit) {
        val field = android.widget.EditText(this).apply {
            this.hint = hint
            setText(initial)
            setSelection(initial.length)
            isSingleLine = true
            inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER
                else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(field)
        }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(form)
            .setPositiveButton("OK") { _, _ ->
                val text = field.text.toString().trim()
                if (text.isNotEmpty() || optional) onDone(text)
            }
            .setNegativeButton("Cancel", null)
            .show()
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
    }

    internal var transferServer: ProfileTransfer.Server? = null

    internal lateinit var loadingOverlay: FrameLayout
    internal lateinit var loadingBackdrop: ImageView
    internal lateinit var loadingLogo: ImageView
    internal lateinit var loadingTitle: TextView
    internal lateinit var loadingSubtitle: TextView
    internal lateinit var loadingSource: TextView
    internal lateinit var loadingStatus: TextView
    internal var loadingRequest = 0
    // The player's controls and "You're watching" page (PlayerControls.kt), and what TMDB
    // knows about the playing title (logo, year, description) for them.
    internal val playerControls by lazy { PlayerControls(this, controlsHost()) }
    internal val pauseOverlay by lazy { PauseOverlay(this) }
    internal var playingDetails: TmdbMeta.Details? = null
    internal val showPauseOverlayLater = Runnable { showPauseOverlay() }
    // The player's side panel (sources, episodes, subtitles, audio) and the stream search it's
    // showing; the addon subtitles found for the playing title, kept for switching source.
    internal val sidePanel by lazy { SidePanel(this) }
    internal var panelSession: StreamSearch.Session? = null
    internal var panelListener: StreamSearch.Listener? = null
    internal var playingSubtitlesFound: List<StremioSubtitles.Subtitle> = emptyList()
    // Auto-play or the last-used source has already started for this sources page.
    internal var autoPlayStarted = false

    internal var onboarding = false

    // Catalogs and subtitles from Stremio's own official addons.
    internal val starterAddons = listOf(
        "https://v3-cinemeta.strem.io/manifest.json",
        "https://opensubtitles-v3.strem.io/manifest.json"
    )

    internal var reconnects = 0
    internal var lastReconnectAt = 0L

    internal fun isDescendantOf(view: View, parent: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    internal val extrasTicker = object : Runnable {
        override fun run() {
            updatePlayerAction()
            progressHandler.postDelayed(this, 500)
        }
    }

    internal var frameTimer: androidx.media3.exoplayer.video.VideoFrameMetadataListener? = null

    // Where playback started while its sound is held back; null once the sound is on.
    // The frame rate is often only known from the first frames played, and switching the
    // TV's mode re-syncs HDMI: with Dolby Atmos (or any bitstream to a receiver) already
    // playing, that's an audible glitch. So the first moments play silently under the
    // loading overlay, the TV switches, and playback restarts from the start point with sound.
    internal var audioHeldFrom: Long? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentScreen == "streams" && event.action == KeyEvent.ACTION_DOWN) streamsTouched = true
        if (currentScreen == "player" && handlePlayerKey(event)) return true
        if (currentScreen == "home" && handleHomeKey(event)) return true

        if (event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP &&
            currentScreen == "tv_details"
        ) {
            val focused = currentFocus
            val episodeListView = root.findViewWithTag<ViewGroup>("orbit_episode_list")
            // Up from the first episode goes to the selected season; others go to the episode above.
            if (focused != null && episodeListView != null && episodeListView.childCount > 0 &&
                isDescendantOf(focused, episodeListView.getChildAt(0))
            ) {
                val seasonButton = findViewById<View>(selectedSeasonButtonId)
                if (seasonButton != null) {
                    seasonButton.requestFocus()
                    return true
                }
            }
        }

        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when (currentScreen) {
            "player" -> {
                // First Back closes the pause page or hides the controls; the next leaves.
                when {
                    sidePanel.isShowing -> sidePanel.onBack?.invoke() ?: closeSidePanel()
                    pauseOverlay.isShowing -> pauseOverlay.hide()
                    playerControls.isShowing -> playerControls.hide()
                    else -> leavePlayer()
                }
            }
            "actor" -> leaveActor()
            "profiles" -> if (Profiles.chosenThisLaunch) showHomeScreen() else finish()
            "welcome" -> finish()
            "setup" -> showWelcome()
            "splash" -> finish()
            "transfer" -> leaveTransfer()
            "streams" -> leaveStreams()
            "details", "tv_details" -> leaveDetails()
            "discover", "search", "settings", "collection" -> showHomeScreen()
            else -> super.onBackPressed()
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app mid-video returns to the page it was started from.
        if (currentScreen == "player") leavePlayer()
        player?.release()
        player = null
    }

    override fun onDestroy() {
        transferServer?.stop()
        transferServer = null
        // Closing Orbit (not a profile switch) asks "Who's watching?" again next time.
        if (isFinishing) Profiles.chosenThisLaunch = false
        super.onDestroy()
    }
}
