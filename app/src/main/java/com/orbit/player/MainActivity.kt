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
    private var player: ExoPlayer? = null
    private lateinit var root: LinearLayout
    private lateinit var playerView: PlayerView
    private var currentScreen = "home"
    private var lastFilmTitle = ""
    private var lastPosterFile = ""
    private var playerReturnScreen = "home"
    private var selectedSeasonButtonId: Int = View.NO_ID

    // The scroll view that hosts every non-player screen; the player replaces it while playing.
    private lateinit var contentScroll: ScrollView
    private lateinit var homePlayButton: View
    private lateinit var homeRowsScroll: ScrollView
    private val homeRowItems = mutableListOf<List<TmdbTest.CatalogueItem>>()
    private var heroItem: TmdbTest.CatalogueItem? = null
    private lateinit var heroBackdrop: ImageView
    private lateinit var heroOverline: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroLogo: ImageView
    private lateinit var heroChips: LinearLayout
    private lateinit var heroOverview: TextView
    private val homeRows = mutableListOf<LinearLayout>()
    private val homeRowHeadings = mutableListOf<View>()

    // Where focus was on the home screen, so Back lands on the same poster. Row -1 = play button.
    private var homeFocusRow = -1
    // The row's heading, so focus returns to the same row even if Continue rows
    // appear or disappear in between.
    private var homeFocusRowName: String? = null
    private var homeFocusIndex = 0
    private var pendingHomeRestore = false
    private var cachedMovies: List<TmdbTest.CatalogueItem>? = null
    private var cachedShows: List<TmdbTest.CatalogueItem>? = null

    private val seasonByShow = mutableMapOf<Int, Int>()
    private var episodeFocusIndex = -1
    private var swallowKeyUp = -1

    // Streams screen state, kept so returning from the player shows the same list.
    private var lastFilmImdbId: String? = null

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
    private val profile by lazy { Profiles.active(this) }
    private val progress by lazy { WatchProgress(this, Profiles.progressName(profile.id)) }
    private val subtitleSettings by lazy { SubtitleSettings(prefs) }
    private val playerSettings by lazy { PlayerSettings(prefs) }
    private val tmdbSettings by lazy { TmdbSettings(prefs) }
    private var lastFilmTmdbId: Int? = null

    // Player overlay: the player plus a Skip intro / Next episode button over it.
    private lateinit var playerFrame: FrameLayout
    private lateinit var playerAction: TextView
    private var playerActionRun: (() -> Unit)? = null
    private var playingStream: StremioStreams.Stream? = null
    private var playingSegments: List<IntroDb.Segment> = emptyList()
    private val autoSkipped = mutableSetOf<String>()
    private var nextEpisode: NextEpisode? = null
    private var originalDisplayMode = 0

    // Addon subtitles for the playing video (ranked, best release match first), the one
    // being shown, and the delay applied to it.
    private var playingAddonSubs: List<StremioSubtitles.Subtitle> = emptyList()
    private var activeAddonTrack: AddonSubtitleTrack? = null
    private var subtitleDelayMs = 0L
    private val loadedAddonTracks = mutableMapOf<String, AddonSubtitleTrack>()
    private val addonSubtitleTicker = object : Runnable {
        override fun run() {
            val track = activeAddonTrack ?: return
            val p = player ?: return
            val lines = track.at(p.currentPosition - subtitleDelayMs)
            val cues = lines.map { androidx.media3.common.text.Cue.Builder().setText(it).build() }
            playerView.subtitleView?.setCues(subtitleSettings.filterCues(cues))
            progressHandler.postDelayed(this, 100)
        }
    }

    // The episode after the one playing, and a stream for it once found.
    private data class NextEpisode(
        val target: WatchTarget,
        val id: String,
        @Volatile var stream: StremioStreams.Stream? = null,
        @Volatile var subtitles: List<StremioSubtitles.Subtitle> = emptyList(),
        @Volatile var searched: Boolean = false
    )
    private var streamsTarget: WatchTarget? = null
    private var streamsWatchId: String? = null
    private var streamsSubtitles: java.util.concurrent.Future<List<StremioSubtitles.Subtitle>>? = null
    private var playingTarget: WatchTarget? = null
    private var playingId: String? = null
    private val progressHandler = Handler(Looper.getMainLooper())
    private val showImdbIds = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private var streamsReturnScreen = "details"
    private var streamsLabel = ""
    private val streamGroups = mutableListOf<Pair<String, List<StremioStreams.Stream>>>()
    private var streamsPending = -1
    private var streamsNote: String? = null
    // The focused stream's link, so focus stays on it while other addons' results arrive.
    private var streamFocusKey: String? = null
    private var streamsStatus: TextView? = null
    private var streamsList: LinearLayout? = null
    private var streamsBack: View? = null
    private var streamAddonOrder: List<String> = emptyList()
    // Selected addon tab on the streams screen; null = All.
    private var streamTab: String? = null
    private var streamTabs: LinearLayout? = null
    // False until the viewer presses a key on the streams screen; until then focus follows
    // the top stream as slower addons' results are inserted above the fast ones.
    private var streamsTouched = false
    private var streamsRequest = 0
    private var playbackError: String? = null
    // All stream addons are asked at once; each answers within a few seconds.
    private val streamLoader = java.util.concurrent.Executors.newFixedThreadPool(6)

    // Which top-level screen details pages go back to.
    private var detailsReturnScreen = "home"

    // Discover state, kept so returning from a title restores filters, results and position.
    // Five larger posters per row, beside the info panel.
    private val discoverColumns = 5
    private val discoverPosterWidth = 224
    private var discoverCatalogs: List<StremioAddons.Catalog>? = null
    private var discoverType = "movie"
    private var discoverCatalogKey: String? = null
    private var discoverGenre: String? = null
    private val discoverResults = mutableListOf<StremioAddons.Meta>()
    private var discoverHasMore = true
    private var discoverLoading = false
    private var discoverFocusIndex = -1
    // Bumped whenever the filters change, so a page that arrives late is ignored.
    private var discoverRequest = 0
    // Paging position in the addon's list. Kept apart from the result count because addons
    // filter some titles out of a page and reshuffle lists between requests.
    private var discoverOffset = 0
    private var discoverStep = 0
    private var discoverEmptyPages = 0
    private lateinit var discoverList: androidx.recyclerview.widget.RecyclerView
    private lateinit var discoverHeader: LinearLayout
    // Poster index to focus once it's on screen; -1 = the Type picker, -2 = nothing.
    private var pendingDiscoverFocus = -2
    private lateinit var discoverStatus: TextView
    private lateinit var typePicker: TextView
    private lateinit var catalogPicker: TextView
    private lateinit var genrePicker: TextView

    // Older code refers to these; they now come from the design system (Ui.kt).
    private val bg = Ui.surface
    private val accent = Ui.accent
    private val testUrl =
        "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)

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
            useController = true
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
            addView(playerAction, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = Ui.gutter
                bottomMargin = 170
            })
            addView(buildLoadingOverlay(), FrameLayout.LayoutParams(-1, -1))
        }

        applySavedAddons()
        // Read addon manifests in the background so the first Play is quick.
        thread { StremioAddons.loadManifests() }
        // Opening Orbit: splash, then "Who's watching?", then Home. Switching profile
        // (which recreates the activity) goes straight to Home.
        if (Profiles.chosenThisLaunch) showHomeScreen() else showSplash { showProfilePicker() }
    }

    // The logo fades in, holds a moment, and the page fades into what comes next.
    private fun showSplash(then: () -> Unit) {
        currentScreen = "splash"
        val logo = TextView(this).apply {
            text = android.text.SpannableString("orbit.").apply {
                setSpan(android.text.style.ForegroundColorSpan(Ui.accent), 5, 6, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 96f
            typeface = Ui.font("bold")
            letterSpacing = -0.03f
            includeFontPadding = false
            setTextColor(Ui.textPrimary)
            alpha = 0f
            scaleX = 0.9f
            scaleY = 0.9f
        }
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Ui.background)
            addView(logo, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        setContentView(frame)
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                logo.postDelayed({
                    if (currentScreen != "splash") return@postDelayed
                    frame.animate().alpha(0f).setDuration(220).withEndAction {
                        if (currentScreen == "splash") then()
                    }.start()
                }, 450)
            }.start()
    }

    // App-private storage for the Stremio session key, addons and settings: the profile's own.
    private val prefs by lazy { Profiles.prefs(this, profile.id) }

    private fun savedAddons(): List<StremioAccount.Addon> {
        val json = prefs.getString("stremio_addons", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                StremioAccount.Addon(item.optString("name"), item.optString("url"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Addons added in Orbit by manifest URL. They live only in Orbit, alongside the ones
    // synced from the Stremio account, and are never written to the account.
    private fun localAddons(): List<StremioAccount.Addon> {
        val json = prefs.getString("local_addons", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(json)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                StremioAccount.Addon(item.optString("name"), item.optString("url"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveLocalAddons(addons: List<StremioAccount.Addon>) {
        val array = org.json.JSONArray()
        addons.forEach { array.put(org.json.JSONObject().put("name", it.name).put("url", it.url)) }
        prefs.edit().putString("local_addons", array.toString()).apply()
        applySavedAddons()
        resetDiscover()
    }

    private fun applySavedAddons() {
        val synced =
            if (prefs.contains("stremio_auth_key")) savedAddons().map { it.url } else emptyList()
        // Account addons first (Stremio's order), then Orbit's own; no duplicates.
        StremioAddons.addonUrls = (synced + localAddons().map { it.url }).distinct()
    }

    // Accepts what people paste from addon sites: stremio:// links, or a URL without
    // /manifest.json. Returns null when it can't be a manifest URL.
    private fun normalizeManifestUrl(input: String): String? {
        var url = input.trim()
        if (url.startsWith("stremio://", ignoreCase = true)) url = "https://" + url.substring(10)
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        url = url.removeSuffix("/")
        if (!url.endsWith("/manifest.json")) url += "/manifest.json"
        return url.takeIf { android.util.Patterns.WEB_URL.matcher(it).matches() }
    }

    private fun showAddAddon(onDone: (String?) -> Unit) {
        val field = android.widget.EditText(this).apply {
            hint = "https://…/manifest.json"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
            addView(field)
        }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Add addon by manifest URL")
            .setView(form)
            .setPositiveButton("Add") { _, _ ->
                val input = field.text.toString()
                onDone("Checking addon...")
                thread {
                    val message = addLocalAddon(input)
                    Handler(Looper.getMainLooper()).post {
                        if (currentScreen == "settings") onDone(message)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else {
                false
            }
        }
    }

    // Checks and saves an addon typed on the TV or sent from the phone page. Call off the
    // main thread; returns the message to show. Only addons whose manifest really loads
    // and that offer something are saved.
    private fun addLocalAddon(input: String): String {
        val url = normalizeManifestUrl(input) ?: return "That doesn't look like an addon URL."
        if (url in StremioAddons.addonUrls) return "That addon is already installed."
        return try {
            StremioAddons.forget(url)
            val manifest = StremioAddons.manifest(url)
            val resources = manifest.optJSONArray("resources")
            val catalogs = manifest.optJSONArray("catalogs")?.length() ?: 0
            if ((resources?.length() ?: 0) == 0 && catalogs == 0) {
                "That addon offers nothing Orbit can use."
            } else {
                val name = manifest.optString("name").ifBlank { url }
                // Saved on the main thread, which owns Discover's state; wait for it so a
                // second addon sent straight after sees this one.
                val saved = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).post {
                    saveLocalAddons(localAddons() + StremioAccount.Addon(name, url))
                    saved.countDown()
                }
                saved.await(5, java.util.concurrent.TimeUnit.SECONDS)
                "Added $name."
            }
        } catch (e: Exception) {
            StremioAddons.forget(url)
            "Couldn't load that addon's manifest (${e.message})."
        }
    }

    // Shows a QR code for the phone page; the server runs only while this is open.
    private fun showPhoneLink(
        onDone: (String?) -> Unit,
        title: String = "Add addon from your phone",
        instructions: String = "Scan with your phone's camera, paste the addon URL and tap Add.",
        pageHeading: String? = null,
        pagePlaceholder: String = "https://…/manifest.json",
        pageButton: String = "Add to Orbit",
        handle: (String) -> String = ::addLocalAddon
    ) {
        lateinit var status: TextView
        val server = PhoneLinkServer(
            pageHeading ?: "Paste an addon's manifest URL (or stremio:// link). It's added to Orbit on your TV.",
            pagePlaceholder, pageButton
        ) { url ->
            Handler(Looper.getMainLooper()).post { status.text = "Checking..." }
            val message = handle(url)
            Handler(Looper.getMainLooper()).post {
                status.text = message
                if (currentScreen == "settings") onDone(message)
            }
            message
        }
        val address = try {
            server.start()
        } catch (_: Exception) {
            null
        }
        if (address == null) {
            onDone("Couldn't start the phone link. Is the TV on Wi-Fi?")
            return
        }

        val size = 520
        val matrix = com.google.zxing.qrcode.QRCodeWriter()
            .encode(address, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size) { i ->
            if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE
        }
        val qr = android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.RGB_565)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 24, 48, 8)
            addView(ImageView(context).apply { setImageBitmap(qr) }, LinearLayout.LayoutParams(size, size))
            addView(TextView(context).apply {
                text = "$instructions\nYour phone must be on the same Wi-Fi.  $address"
                textSize = 15f
                setTextColor(Color.rgb(195, 202, 220))
                gravity = Gravity.CENTER
                setPadding(0, 16, 0, 8)
            })
            status = TextView(context).apply {
                text = "Waiting for your phone..."
                textSize = 17f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                gravity = Gravity.CENTER
            }
            addView(status)
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setView(content)
            .setPositiveButton("Done", null)
            .setOnDismissListener { server.stop() }
            .show()
    }

    private fun confirmRemoveAddon(addon: StremioAccount.Addon, onDone: (String?) -> Unit) {
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Remove ${addon.name}?")
            .setMessage("It's removed from Orbit only. You can add it again with its URL.")
            .setPositiveButton("Remove") { _, _ ->
                saveLocalAddons(localAddons().filter { it.url != addon.url })
                StremioAddons.forget(addon.url)
                onDone("Removed ${addon.name}.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearStremioSession() {
        prefs.edit()
            .remove("stremio_auth_key")
            .remove("stremio_email")
            .remove("stremio_addons")
            .apply()
        applySavedAddons()
    }

    // Call off the main thread. Refreshes the addon list from the signed-in Stremio
    // account; if Stremio can't be reached, the last synced list stays in use.
    private fun syncStremioAddons(): String {
        val authKey = prefs.getString("stremio_auth_key", null) ?: return "Not signed in."
        return try {
            val addons = StremioAccount.fetchAddons(authKey)
            val array = org.json.JSONArray()
            addons.forEach { addon ->
                array.put(org.json.JSONObject().put("name", addon.name).put("url", addon.url))
            }
            prefs.edit().putString("stremio_addons", array.toString()).apply()
            applySavedAddons()
            "Synced ${addons.size} addons from Stremio."
        } catch (_: StremioAccount.SessionExpiredException) {
            clearStremioSession()
            "Your Stremio session has expired. Please sign in again in Settings."
        } catch (_: Exception) {
            "Couldn't reach Stremio, so your last synced addons are being used."
        }
    }

    // Forget Discover's catalogs so the next visit loads them from the new addon list.
    private fun resetDiscover() {
        discoverRequest++
        searchCatalogsCache = null
        discoverCatalogs = null
        discoverResults.clear()
        discoverFocusIndex = -1
        discoverLoading = false
    }

    // A few downloads at a time, so a full Discover grid doesn't start a thread per poster.
    private val imageLoader = java.util.concurrent.Executors.newFixedThreadPool(4)

    // Recently shown posters, so revisiting a screen doesn't download them again.
    private val posterCache = object : android.util.LruCache<String, android.graphics.Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.byteCount
    }

    // posterPath is a TMDB path ("/abc.jpg") or a full URL from an addon. targetWidth > 0
    // decodes the image at that width: addons often send 600x900 posters for 190 px cards,
    // and drawing those scaled down every frame is what made scrolling stutter.
    private fun loadPosterImage(
        image: ImageView,
        posterPath: String?,
        tmdbSize: String = "w342",
        targetWidth: Int = 0,
        cornerRadius: Float = 0f,
        circle: Boolean = false,
        scrims: Boolean = false,
        keepAlpha: Boolean = false
    ) {
        if (posterPath.isNullOrBlank()) return
        val address = when {
            // TMDB URLs from addons (e.g. /t/p/w600_and_h900_bestv2/) at the size we need.
            posterPath.startsWith("https://image.tmdb.org/t/p/") ->
                posterPath.replace(Regex("/t/p/[^/]+/"), "/t/p/$tmdbSize/")
            posterPath.startsWith("http") -> posterPath
            else -> "https://image.tmdb.org/t/p/$tmdbSize$posterPath"
        }
        val cacheKey = "$address@$targetWidth@$cornerRadius@$circle@$scrims@$keepAlpha"
        image.tag = cacheKey
        posterCache.get(cacheKey)?.let {
            image.setImageBitmap(it)
            image.background = null
            return
        }

        imageLoader.execute {
            try {
                val connection = URL(address).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val bytes = try {
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }

                val options = BitmapFactory.Options().apply {
                    // Title logos are transparent PNGs; everything else drops alpha to save memory.
                    inPreferredConfig = if (keepAlpha) android.graphics.Bitmap.Config.ARGB_8888
                        else android.graphics.Bitmap.Config.RGB_565
                }
                if (targetWidth > 0) {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth > targetWidth) {
                        // Skip pixels while decoding (halving per step) so a 1920 px cover
                        // never exists at full size in memory, then scale the rest of the way.
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
                        options.inSampleSize = sample
                        val sampledWidth = bounds.outWidth / sample
                        if (sampledWidth > targetWidth) {
                            options.inScaled = true
                            options.inDensity = sampledWidth
                            options.inTargetDensity = targetWidth
                        }
                    }
                }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: return@execute
                val bitmap = when {
                    circle -> {
                        // Centre square, then a circle (cast photos).
                        val side = minOf(decoded.width, decoded.height)
                        val square = android.graphics.Bitmap.createBitmap(
                            decoded, (decoded.width - side) / 2, (decoded.height - side) / 4, side, side
                        )
                        roundCorners(square, side / 2f)
                    }
                    cornerRadius > 0f -> roundCorners(decoded, cornerRadius)
                    scrims -> Ui.bakeScrims(decoded).also { decoded.recycle() }
                    else -> decoded
                }
                posterCache.put(cacheKey, bitmap)

                Handler(Looper.getMainLooper()).post {
                    if (image.tag == cacheKey) {
                        image.setImageBitmap(bitmap)
                        // The placeholder behind the poster would only add GPU work now.
                        image.background = null
                    }
                }
            } catch (_: Exception) {
                // Keep the existing image if downloading fails.
            }
        }
    }

    // Rounds the corners once while decoding, instead of clipping every poster on every
    // frame, which this stick's Mali GPU handles poorly.
    private fun roundCorners(
        source: android.graphics.Bitmap,
        radius: Float
    ): android.graphics.Bitmap {
        val rounded = android.graphics.Bitmap.createBitmap(
            source.width, source.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(
                source,
                android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        android.graphics.Canvas(rounded).drawRoundRect(
            0f, 0f, source.width.toFloat(), source.height.toFloat(), radius, radius, paint
        )
        source.recycle()
        return rounded
    }

    private fun posterPlaceholder() = Ui.rounded(Ui.surface, 14f)

    // Thin bar showing how much of a title has been watched; laid over the bottom of a
    // poster or episode thumbnail.
    private fun makeProgressBar(width: Int, fraction: Float): FrameLayout =
        FrameLayout(this).apply {
            background = Ui.rounded(Color.argb(150, 0, 0, 0), 6f)
            addView(View(context).apply { background = Ui.rounded(Ui.accent, 6f) },
                FrameLayout.LayoutParams((width * fraction).toInt().coerceAtLeast(10), -1))
        }

    // A horizontal row that lets focused posters grow past its edges instead of clipping them.
    private fun makePosterRow(): Pair<HorizontalScrollView, LinearLayout> {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(28, 30, 28, 8)
        }
        // The scroll view clips to its own bounds so tiles off screen aren't drawn (a
        // collection row can hold hundreds). The row's padding leaves room inside those
        // bounds for a focused tile's zoom.
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipChildren = true
            clipToPadding = false
            addView(row)
        }
        return scroll to row
    }

    private fun addPosterRow(scroll: HorizontalScrollView, into: LinearLayout = root) {
        // Negative margins line the first poster up with the row heading.
        into.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = -28
            rightMargin = -40
        })
    }

    // Poster used by every row and the Discover grid: on focus it zooms, gets a bright ring
    // and coloured glow, shows its title and year underneath, and dims the rest of its row.
    private class PosterCard(
        val card: LinearLayout,
        val frame: FrameLayout,
        val image: ImageView,
        val caption: TextView,
        val width: Int
    )

    private fun makePosterCard(
        title: String,
        year: String,
        tmdbPosterPath: String?,
        localPoster: String? = null,
        dimRow: Boolean = true,
        onClick: () -> Unit
    ): LinearLayout {
        val poster = createPosterCard(dimRow)
        bindPosterCard(poster, title, year, tmdbPosterPath, localPoster)
        poster.card.setOnClickListener { onClick() }
        return poster.card
    }

    // Builds an empty card; bindPosterCard fills it. The Discover grid reuses cards this way.
    // width is the poster's width in pixels; height follows the 2:3 poster shape.
    private fun createPosterCard(dimRow: Boolean = true, width: Int = 190): PosterCard {
        val frame = FrameLayout(this).apply {
            // No background or clipping: the poster bitmap is pre-rounded. The outline is
            // only there to shape the glow when focused.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 14f)
                }
            }
            if (Build.VERSION.SDK_INT >= 28) {
                outlineSpotShadowColor = accent
                outlineAmbientShadowColor = accent
            }
        }

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))

        val caption = TextView(this).apply {
            textSize = 15f
            setTextColor(Ui.textPrimary)
            typeface = Ui.font("semibold")
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            // Clears the extra height the poster gains when zoomed.
            setPadding(0, 24, 0, 0)
            alpha = 0f
        }

        val card = object : LinearLayout(this) {
            // Dimming a card fades its image and caption, which never overlap, so Android
            // can skip drawing it to an offscreen buffer first.
            override fun hasOverlappingRendering() = false
        }.apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            addView(frame, LinearLayout.LayoutParams(width, width * 3 / 2))
            addView(caption, LinearLayout.LayoutParams(width, -2))
            setOnFocusChangeListener { card, focused ->
                frame.animate()
                    .scaleX(if (focused) 1.12f else 1f)
                    .scaleY(if (focused) 1.12f else 1f)
                    .translationZ(if (focused) 28f else 0f)
                    .setDuration(160)
                    .start()
                frame.foreground = if (focused) GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 14f
                    setStroke(5, Color.WHITE)
                } else null
                caption.animate().alpha(if (focused) 1f else 0f).setDuration(160).start()
                caption.isSelected = focused
                if (dimRow) dimOtherPosters(card, focused)
                if (focused) rememberHomeFocus(card)
            }
        }
        return PosterCard(card, frame, image, caption, width)
    }

    private fun bindPosterCard(
        poster: PosterCard,
        title: String,
        year: String,
        posterPath: String?,
        localPoster: String? = null
    ) {
        poster.card.contentDescription = title
        poster.image.contentDescription = title
        poster.caption.text = if (year.isBlank()) title else "$title  ·  $year"
        poster.image.setImageDrawable(null)
        poster.image.tag = null
        poster.image.background = posterPlaceholder()
        if (posterPath != null) {
            // Decoded at the zoomed card width (x 1.12) so focus stays sharp, with the 14 px
            // on-screen corners scaled to the decoded size.
            val decodeWidth = poster.width * 112 / 100
            loadPosterImage(
                poster.image, posterPath, "w342",
                targetWidth = decodeWidth, cornerRadius = 14f * decodeWidth / poster.width
            )
        } else if (localPoster != null) {
            try {
                assets.open("posters/$localPoster").use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    poster.image.setImageBitmap(
                        roundCorners(bitmap, 14f * bitmap.width / poster.width)
                    )
                    poster.image.background = null
                }
            } catch (_: Exception) {
            }
        }
        // A reused card may still be zoomed from the title it showed before.
        if (!poster.card.isFocused) {
            poster.frame.animate().cancel()
            poster.frame.scaleX = 1f
            poster.frame.scaleY = 1f
            poster.frame.translationZ = 0f
            poster.frame.foreground = null
            poster.caption.animate().cancel()
            poster.caption.alpha = 0f
        }
    }

    // Left on the first poster and right on the last stay put, instead of focus
    // jumping to whatever is nearest in that direction (e.g. the tabs at the top).
    private fun pinRowEnds(row: ViewGroup) {
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child.id == View.NO_ID) child.id = View.generateViewId()
            child.nextFocusLeftId = if (i == 0) child.id else View.NO_ID
            child.nextFocusRightId = if (i == row.childCount - 1) child.id else View.NO_ID
        }
    }

    private fun dimOtherPosters(card: View, focused: Boolean) {
        val row = card.parent as? ViewGroup ?: return
        if (focused) {
            for (i in 0 until row.childCount) {
                val child = row.getChildAt(i)
                child.animate().alpha(if (child === card) 1f else 0.5f).setDuration(160).start()
            }
        } else {
            // Focus may only be moving to the next poster; undim only if it left the row.
            row.post {
                if (row.findFocus() == null) {
                    for (i in 0 until row.childCount) {
                        row.getChildAt(i).animate().alpha(1f).setDuration(160).start()
                    }
                }
            }
        }
    }

    // Solid buttons (Play): white ring and slight zoom when focused.
    // Older solid buttons (Play on details pages) restyled as the primary button.
    private fun styleButtonFocus(button: View) {
        val text = button as? TextView
        text?.typeface = Ui.font("semibold")
        val paint = { focused: Boolean ->
            text?.setTextColor(Ui.background)
            button.background = Ui.rounded(if (focused) Color.WHITE else Color.rgb(226, 230, 238), 12f)
            button.animate().scaleX(if (focused) 1.06f else 1f)
                .scaleY(if (focused) 1.06f else 1f).setDuration(140).start()
        }
        paint(button.isFocused)
        button.setOnFocusChangeListener(chainFocus(button.onFocusChangeListener) { _, focused ->
            paint(focused)
        })
    }

    // Text links ("‹ BACK TO …"): a filled pill when focused.
    // Back links ("‹ Back to Discover"): quiet text that becomes a white pill on focus.
    private fun styleLinkFocus(link: TextView) {
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

    private fun chainFocus(
        first: View.OnFocusChangeListener?,
        second: (View, Boolean) -> Unit
    ) = View.OnFocusChangeListener { view, focused ->
        first?.onFocusChange(view, focused)
        second(view, focused)
    }

    private fun rememberHomeFocus(card: View) {
        val row = card.parent as? LinearLayout ?: return
        val rowIndex = homeRows.indexOfFirst { it === row }
        if (currentScreen != "home" || rowIndex < 0) return
        // Each row keeps its own last position, so moving up/down returns to it.
        row.tag = row.indexOfChild(card)
        homeFocusRow = rowIndex
        homeFocusRowName = (homeRowHeadings.getOrNull(rowIndex) as? TextView)?.text?.toString()
        homeFocusIndex = row.indexOfChild(card)
    }

    private fun focusHomeRow(rowIndex: Int, preferredIndex: Int, animate: Boolean = true) {
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

    private fun fillHomeRow(rowIndex: Int, cards: List<View>) {
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

    // Top bar shared by HOME, DISCOVER and SETTINGS: wordmark, then the tabs. Returns the
    // tabs so each screen can point their Down at its first control.
    private fun addHeader(activeTab: String, into: LinearLayout = root): List<TextView> {
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

    private fun pointTabsDownTo(tabs: List<TextView>, target: View) {
        if (target.id == View.NO_ID) target.id = View.generateViewId()
        tabs.forEach { it.nextFocusDownId = target.id }
    }

    // A faint cool glow in the top-left corner over the near-black background.
    // Plain near-black: backdrops fade into it without a visible edge.
    private fun screenBackground() = android.graphics.drawable.ColorDrawable(Ui.background)

    // Discover shows its own recycling grid; the other screens live in contentScroll.
    private fun useContentScroll() {
        stopDiscoverFocusWatcher()
        if (contentScroll.parent == null) setContentView(contentScroll)
    }

    private var discoverFocusWatcher: android.view.ViewTreeObserver.OnGlobalFocusChangeListener? = null

    private fun stopDiscoverFocusWatcher() {
        discoverFocusWatcher?.let {
            window.decorView.viewTreeObserver.removeOnGlobalFocusChangeListener(it)
        }
        discoverFocusWatcher = null
    }

    // HOME: a fixed hero (backdrop, title, details, Play) above rows that scroll beneath it.
    // The hero follows the focused poster after a short pause, like Google TV.
    // The built HOME is kept and put back as-is when nothing on it has changed: rebuilding
    // it (hundreds of collection tiles) froze the screen for over a second on Back.
    private var homeFrame: FrameLayout? = null
    private var homeDirty = true

    private fun showHomeScreen() {
        currentScreen = "home"
        detailsReturnScreen = "home"
        actorPages.clear()
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
        val rowLabels = listOfNotNull(
            "Continue movies".takeIf { continueMovies.isNotEmpty() },
            "Continue shows".takeIf { continueShows.isNotEmpty() }
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
                in collections.map { "\u0000" + it.id } -> fillCollectionRow(index, collection!!)
                "Popular movies" -> loadHomeRow(index, "movies", cachedMovies) { TmdbTest.fetchPopularMovies(it) }
                else -> loadHomeRow(index, "shows", cachedShows) { TmdbTest.fetchPopularTvShows(it) }
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

    private fun restoreHomeFocus() {
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

    private fun loadHomeRow(
        index: Int,
        kind: String,
        cached: List<TmdbTest.CatalogueItem>?,
        fetch: (String) -> List<TmdbTest.CatalogueItem>
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

    private fun fillHomeItems(index: Int, items: List<TmdbTest.CatalogueItem>) {
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

    private val collectionItems = java.util.IdentityHashMap<TmdbTest.CatalogueItem, Pair<NuvioCollections.Collection, NuvioCollections.Folder>>()
    private var openFolder: Pair<NuvioCollections.Collection, NuvioCollections.Folder>? = null
    private val folderCache = mutableMapOf<String, List<NuvioCollections.Loaded>>()
    private var folderFocus: Pair<Int, Int>? = null

    private fun savedCollections(): List<NuvioCollections.Collection> = try {
        prefs.getString("nuvio_collections", null)?.let { NuvioCollections.parse(it) }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    // Imports a Nuvio export (the JSON itself, or a link to it), replacing collections with
    // the same id. Call off the main thread; returns the message to show.
    private fun importCollections(input: String): String {
        val text = input.trim()
        val json = try {
            if (text.startsWith("http")) StremioAddons.get(text, retry = false) else text
        } catch (e: Exception) {
            return "Couldn't download that link (${e.message})."
        }
        val imported = try {
            NuvioCollections.parse(json)
        } catch (e: Exception) {
            return e.message ?: "That isn't a Nuvio collections export."
        }
        val existing = try {
            prefs.getString("nuvio_collections", null)?.let { org.json.JSONArray(it) }
        } catch (_: Exception) {
            null
        } ?: org.json.JSONArray()
        val incoming = org.json.JSONArray(json.trim())
        val ids = imported.map { it.id }.toSet()
        val merged = org.json.JSONArray()
        for (i in 0 until existing.length()) {
            val c = existing.optJSONObject(i) ?: continue
            if (c.optString("id") !in ids) merged.put(c)
        }
        for (i in 0 until incoming.length()) incoming.optJSONObject(i)?.let { merged.put(it) }
        prefs.edit().putString("nuvio_collections", merged.toString()).apply()
        homeDirty = true
        folderCache.clear()
        folderAllCache.clear()
        val folders = imported.sumOf { it.folders.size }
        val trakt = imported.flatMap { c -> c.folders.flatMap { it.sources } }
            .count { it is NuvioCollections.TraktSource }
        return "Imported ${imported.size} " + (if (imported.size == 1) "collection" else "collections") +
            " ($folders folders): " + imported.joinToString(", ") { it.title } + "." +
            (if (trakt > 0) " $trakt Trakt sources will be skipped." else "")
    }

    private fun removeCollection(id: String) {
        val existing = try {
            org.json.JSONArray(prefs.getString("nuvio_collections", "[]"))
        } catch (_: Exception) {
            org.json.JSONArray()
        }
        val kept = org.json.JSONArray()
        for (i in 0 until existing.length()) {
            val c = existing.optJSONObject(i) ?: continue
            if (c.optString("id") != id) kept.put(c)
        }
        prefs.edit().putString("nuvio_collections", kept.toString()).apply()
        homeDirty = true
    }

    // Nuvio's "Export File" writes nuvio-collections.json to the stick's Downloads folder.
    // Reading another app's file there needs Android's all-files access for Orbit.
    private fun importNuvioExportFile(): String {
        if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            try {
                startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                ))
                return "Allow Orbit to read files, then press Import again."
            } catch (_: Exception) {
                return "Orbit needs permission to read Nuvio's export file, and this TV has no " +
                    "screen to grant it. Use \u201cImport from phone\u201d instead."
            }
        }
        val file = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS), "nuvio-collections.json")
        if (!file.exists()) return "No nuvio-collections.json in Downloads. In Nuvio, open Collections and press Export File first."
        return try {
            importCollections(file.readText())
        } catch (e: Exception) {
            "Couldn't read Nuvio's export file (${e.message})."
        }
    }

    // A collection on HOME: one wide tile per folder, using Nuvio's cover image or emoji.
    private fun fillCollectionRow(index: Int, collection: NuvioCollections.Collection) {
        val items = collection.folders.map { folder ->
            TmdbTest.CatalogueItem(
                id = -1,
                title = folder.title.ifBlank { collection.title },
                // TMDB and Trakt sources have names; addon sources only have catalog ids.
                overview = folder.sources.filter { it !is NuvioCollections.AddonSource }
                    .joinToString("  ·  ") { it.label }.take(160)
                    .ifBlank { if (folder.sources.size == 1) "1 source" else "${folder.sources.size} sources" },
                posterPath = null,
                mediaType = "collection",
                backdropPath = folder.backdrop ?: folder.cover
            ).also { collectionItems[it] = collection to folder }
        }
        homeRowItems[index] = items
        // Build tiles in batches: a collection can have hundreds of folders, and every
        // built tile costs time on each frame even when it's off screen. Returning to a
        // folder far along the row builds far enough to include it.
        val restoring = homeFocusRowName == collection.title
        val first = maxOf(COLLECTION_BATCH, if (restoring) homeFocusIndex + COLLECTION_BATCH / 2 else 0)
        fillHomeRow(index, items.take(first).map { makeCollectionTile(index, it) })
        if (heroItem == null && index == 0) showHero(items.firstOrNull(), immediate = true)
    }

    private fun makeCollectionTile(rowIndex: Int, item: TmdbTest.CatalogueItem): View {
        val folder = collectionItems.getValue(item).second
        return makeFolderTile(folder, item.title) { openHomeItem(item) }.also { tile ->
            tile.onFocusChangeListener = chainFocus(tile.onFocusChangeListener) { card, focused ->
                if (!focused) return@chainFocus
                showHero(item)
                // Near the end of what's built: add the next batch.
                val row = homeRows.getOrNull(rowIndex) ?: return@chainFocus
                val all = homeRowItems.getOrNull(rowIndex) ?: return@chainFocus
                if (row.indexOfChild(card) >= row.childCount - 6 && row.childCount < all.size) {
                    row.post {
                        val start = row.childCount
                        all.subList(start, minOf(all.size, start + COLLECTION_BATCH)).forEach {
                            val next = makeCollectionTile(rowIndex, it)
                            row.addView(next, LinearLayout.LayoutParams(next.getTag(R.id.tile_width) as Int, -2).apply {
                                setMargins(0, 0, 26, 0)
                            })
                        }
                        pinRowEnds(row)
                        lazyLoadHandler.post(lazyLoadPass)
                    }
                }
            }
        }
    }

    private var pendingCover: Runnable? = null
    private val COLLECTION_BATCH = 24

    // Starts loading covers of folder tiles within about one screen of view. Runs after
    // HOME is built and whenever anything on it scrolls.
    private fun runLazyLoads() {
        if (currentScreen != "home") return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val location = IntArray(2)
        homeRows.forEach { row ->
            if (!row.isShown) return@forEach
            row.getLocationOnScreen(location)
            // Whole row far above or below the screen: nothing to do.
            if (location[1] > screenH * 3 / 2 || location[1] + row.height < -screenH / 2) return@forEach
            for (i in 0 until row.childCount) {
                val tile = row.getChildAt(i)
                val load = tile.getTag(R.id.lazy_load) as? Runnable ?: continue
                tile.getLocationOnScreen(location)
                if (location[0] < screenW * 3 / 2 && location[0] + tile.width > -screenW / 2) {
                    tile.setTag(R.id.lazy_load, null)
                    load.run()
                }
            }
        }
    }

    private val lazyLoadPass = Runnable { runLazyLoads() }
    private val lazyLoadHandler = Handler(Looper.getMainLooper())

    private fun makeFolderTile(folder: NuvioCollections.Folder, title: String, onClick: () -> Unit): LinearLayout {
        val w = 340
        val h = 192
        val frame = FrameLayout(this).apply {
            background = Ui.rounded(Ui.surfaceRaised, 14f)
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 14f)
                }
            }
        }
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        if (folder.cover != null) {
            // Loaded once the tile comes near the screen (see runLazyLoads): collections can
            // hold hundreds of folders, far too many covers to fetch up front.
            pendingCover = Runnable {
                loadPosterImage(image, folder.cover, targetWidth = w * 112 / 100, cornerRadius = 16f)
            }
        } else {
            // No cover: the folder's emoji (or initial) and its title on a plain tile.
            frame.addView(Ui.text(this, Ui.Type.Display, folder.emoji ?: title.take(1)).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 40)
            }, FrameLayout.LayoutParams(-1, -1))
        }
        frame.addView(Ui.text(this, Ui.Type.BodyStrong, title, maxLines = 1).apply {
            setPadding(20, 40, 20, 16)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(200, 0, 0, 0), 0))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        val cover = pendingCover
        pendingCover = null
        return LinearLayout(this).apply {
            id = View.generateViewId()
            setTag(R.id.tile_width, w)
            if (cover != null) setTag(R.id.lazy_load, cover)
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            contentDescription = title
            addView(frame, LinearLayout.LayoutParams(w, h))
            // Same height as a poster card, so rows of either kind line up.
            addView(View(context), LinearLayout.LayoutParams(w, 285 - h + 30))
            setOnClickListener { onClick() }
            setOnFocusChangeListener { card, focused ->
                // Zoom and ring only: a shadow under a wide tile costs this GPU a lot.
                frame.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f)
                    .setDuration(160).start()
                frame.foreground = if (focused) Ui.rounded(Color.TRANSPARENT, 14f, 5, Color.WHITE) else null
                if (focused) rememberHomeFocus(card)
            }
        }
    }

    // A folder, laid out like Discover: a poster grid with the info panel on the right.
    // Instead of Discover's pickers it has a tab per source (plus All), as in Nuvio.
    private var folderLoaded: MutableList<NuvioCollections.Loaded?> = mutableListOf()
    private var folderTab = -1 // -1 = All, else the source's index
    private var folderShown: List<StremioAddons.Meta> = emptyList()
    private var folderFocusIndex = -1
    private var folderPendingFocus = -2 // index to focus once bound; -1 = the tabs
    private var folderRequest = 0
    private lateinit var folderList: androidx.recyclerview.widget.RecyclerView
    private lateinit var folderHeader: LinearLayout
    private lateinit var folderTabs: LinearLayout
    private lateinit var folderStatus: TextView

    private fun showFolderScreen(collection: NuvioCollections.Collection, folder: NuvioCollections.Folder) {
        val key = "${collection.id}/${folder.id}"
        val returning = openFolder?.let { "${it.first.id}/${it.second.id}" } == key && folderFocusIndex >= 0
        currentScreen = "collection"
        detailsReturnScreen = "collection"
        actorPages.clear()
        openFolder = collection to folder
        if (!returning) {
            folderTab = -1
            folderFocusIndex = -1
        }
        folderAll.clear()
        folderAllIds.clear()
        folderPaging.clear()
        folderAllCache[key]?.let { saved -> saved.forEach { if (folderAllIds.add(it.id)) folderAll.add(it) } }
        val request = ++folderRequest

        // Not clipped: the tab strip starts in the left margin so tabs scroll in from the
        // screen edge; clipping it at the margin cut off the All tab.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(0, 36, 0, 0)
        }
        val back = TextView(this).apply {
            id = View.generateViewId()
            text = "‹   Home"
            isFocusable = true
            isClickable = true
            setOnClickListener { showHomeScreen() }
        }
        styleLinkFocus(back)
        header.addView(back, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = -22 })
        header.addView(Ui.text(this, Ui.Type.Overline, collection.title.uppercase(), Ui.accent).apply {
            setPadding(0, 24, 0, 0)
        })
        header.addView(Ui.text(this, Ui.Type.Title, folder.title.ifBlank { collection.title }, maxLines = 1).apply {
            setPadding(0, 12, 0, 22)
        })
        folderTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(Ui.gutter, 4, Ui.gutter, 8)
        }
        header.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            clipToPadding = false
            addView(folderTabs)
        }, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = -Ui.gutter
            rightMargin = -8
        })
        folderStatus = Ui.text(this, Ui.Type.Label, "", Ui.textSecondary).apply { setPadding(0, 18, 0, 0) }
        header.addView(folderStatus)
        folderHeader = header

        folderList = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = object : androidx.recyclerview.widget.GridLayoutManager(context, discoverColumns) {
                // Same focus margin as Discover, so the zoomed poster isn't cut off.
                override fun requestChildRectangleOnScreen(
                    parent: androidx.recyclerview.widget.RecyclerView,
                    child: View,
                    rect: android.graphics.Rect,
                    immediate: Boolean,
                    focusedChildVisible: Boolean
                ): Boolean = super.requestChildRectangleOnScreen(
                    parent, child,
                    android.graphics.Rect(rect.left, rect.top - 70, rect.right, rect.bottom + 70),
                    immediate, focusedChildVisible
                )
            }.apply {
                spanSizeLookup = object : androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) = if (position == 0) discoverColumns else 1
                }
            }
            adapter = FolderAdapter()
            itemAnimator = null
            setItemViewCacheSize(2 * discoverColumns)
            setPadding(Ui.gutter, 0, 8, 40)
            clipToPadding = false
            clipChildren = false
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = screenBackground()
            addView(folderList, LinearLayout.LayoutParams(0, -1, 1f))
            addView(makeDiscoverInfoPanel(), LinearLayout.LayoutParams(560, -1))
        })

        // Moving up into the header shows all of it (window-wide listener, removed on exit).
        val watcher = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            if (currentScreen != "collection") return@OnGlobalFocusChangeListener
            var view: View? = newFocus
            while (view != null && view !== folderHeader) view = view.parent as? View
            if (view != null && folderList.computeVerticalScrollOffset() > 0) folderList.smoothScrollToPosition(0)
        }
        stopDiscoverFocusWatcher()
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener(watcher)
        discoverFocusWatcher = watcher

        folderPendingFocus = if (returning) folderFocusIndex else -1
        val cached = folderCache[key]
        folderLoaded = cached?.toMutableList() ?: MutableList(folder.sources.size) { null }
        updateFolder()
        if (folderPendingFocus >= discoverColumns) folderList.scrollToPosition(folderPendingFocus + 1)
        if (cached != null) return

        folder.sources.forEachIndexed { i, source ->
            streamLoader.execute {
                val result = NuvioCollections.load(source, BuildConfig.TMDB_API_KEY)
                Handler(Looper.getMainLooper()).post {
                    if (request != folderRequest) return@post
                    folderLoaded[i] = result
                    if (folderLoaded.all { it != null }) folderCache[key] = folderLoaded.map { it!! }
                    if (currentScreen == "collection") updateFolder()
                }
            }
        }
    }

    // Rebuilds the tabs and the grid's titles for the selected tab.
    private fun updateFolder() {
        val folder = openFolder?.second ?: return
        val sources = folder.sources
        if (folderAll.isEmpty() && folderLoaded.all { it != null }) {
            folderLoaded.forEach { l -> l!!.metas.forEach { if (folderAllIds.add(it.id)) folderAll.add(it) } }
            openFolder?.let { folderAllCache["${it.first.id}/${it.second.id}"] = folderAll.toList() }
        }
        val all = if (folderAll.isNotEmpty()) folderAll.toList()
            else folderLoaded.filterNotNull().flatMap { it.metas }.distinctBy { it.id }
        folderShown = if (folderTab < 0) all else folderLoaded.getOrNull(folderTab)?.metas.orEmpty()

        // Tabs only when there's more than one source to choose between. They're rebuilt
        // as sources load, so a focused tab hands focus to its replacement.
        val focusedTab = (currentFocus?.takeIf { it.parent === folderTabs })?.tag as? Int
        folderTabs.removeAllViews()
        if (sources.size > 1) {
            (listOf(-1) + sources.indices).forEach { i ->
                val loaded = if (i < 0) null else folderLoaded.getOrNull(i)
                val label = when {
                    i < 0 -> "All  ·  ${all.size}" + if (folderLoaded.any { it?.next != null }) "+" else ""
                    loaded == null -> "${sources[i].label}  ·  …"
                    else -> "${loaded.label.substringBefore("  ·  ")}  ·  ${loaded.metas.size}" + if (loaded.next != null) "+" else ""
                }
                folderTabs.addView(Ui.pill(this, label) {
                    folderTab = i
                    folderFocusIndex = -1
                    updateFolder()
                }.apply {
                    id = View.generateViewId()
                    tag = i
                    onFocusChangeListener = View.OnFocusChangeListener { v, focused ->
                        Ui.paintPill(v as TextView, v.tag == folderTab)
                        // The first tab always brings the strip fully back to its start.
                        if (focused && v.tag == -1) (folderTabs.parent as? HorizontalScrollView)?.smoothScrollTo(0, 0)
                    }
                    Ui.paintPill(this, i == folderTab)
                }, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 })
            }
            folderTabs.getChildAt(0)?.let { it.nextFocusLeftId = it.id }
            folderTabs.getChildAt(folderTabs.childCount - 1)?.let { it.nextFocusRightId = it.id }
            focusedTab?.let { folderTabs.findViewWithTag<View>(it)?.requestFocus() }
        }

        val pending = folderLoaded.count { it == null }
        val selected = folderLoaded.getOrNull(folderTab)
        folderStatus.text = when {
            sources.isEmpty() -> "This folder has no sources."
            folderShown.isEmpty() && pending > 0 -> "Loading ${pending} of ${sources.size} " + (if (sources.size == 1) "source..." else "sources...")
            folderShown.isEmpty() -> selected?.problem ?: folderLoaded.firstNotNullOfOrNull { it?.problem } ?: "Nothing to show here."
            pending > 0 -> "${folderShown.size} titles so far · loading $pending more " + (if (pending == 1) "source..." else "sources...")
            else -> "${folderShown.size} titles"
        }
        folderList.adapter?.notifyDataSetChanged()

        // Up from the top row goes to the selected tab, or the back link without tabs.
        val upTarget = folderTabs.findViewWithTag<View>(folderTab) ?: folderHeader.getChildAt(0)
        folderUpTarget = upTarget.id
        // Opening a folder starts on its selected tab (or the back link without tabs).
        if (folderPendingFocus == -1) {
            folderPendingFocus = -2
            upTarget.post { upTarget.requestFocus() }
        }
    }

    private var folderUpTarget = View.NO_ID
    // All tab's titles once every source's first page is in. Later pages are appended to
    // the end, so what the viewer has already scrolled past never moves.
    private val folderAll = mutableListOf<StremioAddons.Meta>()
    private val folderAllIds = mutableSetOf<String>()
    private val folderPaging = mutableSetOf<Int>()
    private val folderAllCache = mutableMapOf<String, List<StremioAddons.Meta>>()

    // Fetches the next page of the sources behind the current tab (every source on All).
    private fun loadMoreFolder() {
        val folder = openFolder?.second ?: return
        if (folderLoaded.any { it == null }) return
        val key = openFolder!!.let { "${it.first.id}/${it.second.id}" }
        val request = folderRequest
        val sources = if (folderTab < 0) folder.sources.indices.toList() else listOf(folderTab)
        sources.forEach { i ->
            val current = folderLoaded[i] ?: return@forEach
            val next = current.next ?: return@forEach
            if (!folderPaging.add(i)) return@forEach
            streamLoader.execute {
                val page = NuvioCollections.load(folder.sources[i], BuildConfig.TMDB_API_KEY, next)
                Handler(Looper.getMainLooper()).post {
                    folderPaging.remove(i)
                    if (request != folderRequest) return@post
                    val old = folderLoaded[i] ?: return@post
                    val known = old.metas.map { it.id }.toHashSet()
                    val fresh = page.metas.filter { known.add(it.id) }
                    // A page of only repeats means the source has run dry.
                    folderLoaded[i] = old.copy(metas = old.metas + fresh, next = page.next.takeIf { fresh.isNotEmpty() })
                    folderCache[key] = folderLoaded.map { it!! }
                    if (currentScreen != "collection") return@post
                    val added = fresh.filter { folderAllIds.add(it.id) }.also { folderAll.addAll(it) }
                        .takeIf { folderTab < 0 } ?: if (folderTab == i) fresh else emptyList()
                    folderAllCache[key] = folderAll.toList()
                    val start = folderShown.size
                    folderShown = if (folderTab < 0) folderAll.toList() else folderLoaded[i]!!.metas
                    // Insert rather than reset the grid, so focus stays put.
                    if (added.isNotEmpty()) {
                        folderList.adapter?.notifyItemChanged(start) // previous last poster's Right
                        folderList.adapter?.notifyItemRangeInserted(start + 1, added.size)
                    }
                    refreshFolderLabels()
                }
            }
        }
    }

    // Updates tab counts and the status line without rebuilding anything focusable.
    private fun refreshFolderLabels() {
        for (j in 0 until folderTabs.childCount) {
            val tab = folderTabs.getChildAt(j) as TextView
            val i = tab.tag as Int
            val count = if (i < 0) folderAll.size else folderLoaded.getOrNull(i)?.metas?.size ?: continue
            val more = if ((if (i < 0) folderLoaded.any { it?.next != null } else folderLoaded[i]?.next != null)) "+" else ""
            tab.text = tab.text.toString().substringBeforeLast("  ·  ") + "  ·  $count$more"
        }
        folderStatus.text = "${folderShown.size} titles" +
            if (folderPaging.isNotEmpty()) " · loading more..." else ""
    }

    private inner class FolderAdapter :
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
    private val continueItems = java.util.IdentityHashMap<TmdbTest.CatalogueItem, WatchProgress.Entry>()
    private val continueBackdrops = java.util.concurrent.ConcurrentHashMap<String, String>()  // "" = none found

    private fun fillContinueRow(index: Int, entries: List<WatchProgress.Entry>) {
        val items = entries.map { entry ->
            val left = ((entry.durationMs - entry.positionMs) / 60_000).coerceAtLeast(1)
            TmdbTest.CatalogueItem(
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
                    TmdbTest.fetchMetadata(title, BuildConfig.TMDB_API_KEY)?.backdropPath
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

    private fun showContinueOptions(entry: WatchProgress.Entry, card: View) {
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
                    0 -> progress.remove(entry)
                    1 -> progress.markWatched(entry)
                    else -> return@setItems
                }
                removeContinueCard(card)
            }
            .show()
    }

    // Takes the card out of its row straight away (no full HOME rebuild); a row left
    // empty is rebuilt away.
    private fun removeContinueCard(card: View) {
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
    private fun resumeEntry(entry: WatchProgress.Entry) {
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

    private fun openHomeItem(item: TmdbTest.CatalogueItem) {
        collectionItems[item]?.let { (collection, folder) ->
            showFolderScreen(collection, folder)
            return
        }
        val entry = continueItems[item]
        if (entry != null && entry.type == "movie") {
            showFilmDetails(item.title, item.posterPath.orEmpty(), imdbId = entry.id)
            return
        }
        if (item.mediaType == "tv") showTvDetails(item.id, item.title, item.posterPath.orEmpty())
        else showFilmDetails(item.title, item.posterPath.orEmpty())
    }

    // Play on the hero: a film goes straight to its streams; a series opens its episodes.
    private fun playHeroItem(item: TmdbTest.CatalogueItem) {
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
            TmdbTest.fetchImdbId("movie", item.id, BuildConfig.TMDB_API_KEY)
        }
    }

    private val heroHandler = Handler(Looper.getMainLooper())

    // Waits ~350 ms so moving quickly along a row doesn't load every backdrop on the way.
    private fun showHero(item: TmdbTest.CatalogueItem?, immediate: Boolean = false) {
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

    private fun backLabel() = when (detailsReturnScreen) {
        "discover" -> "‹   Discover"
        "search" -> "‹   Search"
        "collection" -> "‹   ${openFolder?.second?.title ?: "Collection"}"
        "actor" -> "‹   ${actorPages.lastOrNull()?.name ?: "Back"}"
        else -> "‹   Home"
    }

    private fun leaveDetails() {
        when (detailsReturnScreen) {
            "discover" -> showDiscoverScreen()
            "search" -> showSearchScreen()
            "collection" -> openFolder?.let { showFolderScreen(it.first, it.second) } ?: showHomeScreen()
            "actor" -> actorPages.lastOrNull()?.let { showActorProfile(it) } ?: showHomeScreen()
            else -> showHomeScreen()
        }
    }

    private fun showSettingsScreen() {
        useContentScroll()
        currentScreen = "settings"
        root.removeAllViews()
        root.gravity = Gravity.TOP
        contentScroll.scrollTo(0, 0)

        val tabs = addHeader("settings")

        root.addView(Ui.text(this, Ui.Type.Title, "Settings").apply { setPadding(0, 4, 0, 28) })

        // Each section is a panel: a heading, a short explanation, then its controls.
        fun panel(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 38, 44, 40)
            background = Ui.rounded(Ui.surface, 20f)
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 28 }) }

        val profilesSection = panel()
        val profileFirst = buildProfilesSettings(profilesSection)
        val account = panel()
        account.addView(Ui.text(this, Ui.Type.Headline, "Stremio account"))
        val status = Ui.text(this, Ui.Type.Body, "", Ui.textSecondary).apply { setPadding(0, 12, 0, 24) }
        account.addView(status)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        account.addView(buttons)

        // Synced addons as chips, a few per line.
        val addonList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        account.addView(addonList)

        val subtitlesSection = panel()
        buildSubtitleSettings(subtitlesSection)
        val playerSection = panel()
        buildPlayerSettings(playerSection)
        val tmdbSection = panel()
        buildTmdbSettings(tmdbSection)
        val localSection = panel()
        val collectionsSection = panel()

        fun addButton(label: String, onClick: () -> Unit) {
            buttons.addView(makePicker(onClick).apply { text = label },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 })
        }

        var stayInAddons = false
        var stayInCollections = false
        var firstRefresh = true
        lateinit var refresh: (String?) -> Unit
        refresh = { note ->
            val email = prefs.getString("stremio_email", null)
            buttons.removeAllViews()
            if (email == null) {
                status.text = "Not signed in. Discover shows the catalogs of the addons in " +
                    "your Stremio account, so sign in to see them."
                addButton("Sign in to Stremio") { showStremioLogin(refresh) }
            } else {
                status.text = "Signed in as $email. Your addons sync each time Orbit opens Discover."
                addButton("Sync addons now") {
                    refresh("Syncing...")
                    thread {
                        val message = syncStremioAddons()
                        Handler(Looper.getMainLooper()).post {
                            resetDiscover()
                            if (currentScreen == "settings") refresh(message)
                        }
                    }
                }
                addButton("Sign out") {
                    val authKey = prefs.getString("stremio_auth_key", null)
                    if (authKey != null) thread { StremioAccount.logout(authKey) }
                    clearStremioSession()
                    resetDiscover()
                    refresh("Signed out.")
                }
            }
            if (note != null) status.append("\n$note")
            pointTabsDownTo(tabs, profileFirst)

            val addons = if (email == null) emptyList() else savedAddons()
            addonList.removeAllViews()
            if (addons.isNotEmpty()) {
                addonList.addView(Ui.text(this, Ui.Type.Overline, "SYNCED ADDONS  ·  ${addons.size}", Ui.textMuted).apply {
                    setPadding(0, 34, 0, 16)
                })
                addons.chunked(6).forEach { line ->
                    addonList.addView(
                        Ui.chipRow(this, line.map { Ui.chip(this, it.name, Ui.textPrimary) }),
                        LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 12 }
                    )
                }
            }

            // Addons added in Orbit: Add buttons, then one row per addon (OK removes it).
            localSection.removeAllViews()
            localSection.addView(Ui.text(this, Ui.Type.Headline, "Addons added in Orbit"))
            localSection.addView(Ui.text(
                this, Ui.Type.Body,
                "Add any Stremio addon by its manifest URL or stremio:// link. These stay in " +
                    "Orbit and are used together with your Stremio account's addons.",
                Ui.textSecondary
            ).apply { setPadding(0, 12, 0, 24) })
            val addButton = makePicker {
                stayInAddons = true
                showAddAddon(refresh)
            }.apply { text = "+  Add addon by URL" }
            val phoneButton = makePicker {
                stayInAddons = true
                showPhoneLink(refresh)
            }.apply { text = "Add from phone (QR code)" }
            localSection.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(addButton, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 })
                addView(phoneButton, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 14 })
            val local = localAddons()
            if (local.isNotEmpty()) {
                localSection.addView(Ui.text(this, Ui.Type.Overline, "INSTALLED  ·  OK TO REMOVE", Ui.textMuted).apply {
                    setPadding(0, 18, 0, 14)
                })
            }
            local.forEach { addon ->
                localSection.addView(makePicker {
                    stayInAddons = true
                    confirmRemoveAddon(addon, refresh)
                }.apply {
                    text = "${addon.name}     ✕"
                }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 10 })
            }
            // Nuvio collections: import (phone or Nuvio's export file), then one row each.
            collectionsSection.removeAllViews()
            collectionsSection.addView(Ui.text(this, Ui.Type.Headline, "Nuvio collections"))
            collectionsSection.addView(Ui.text(
                this, Ui.Type.Body,
                "Show collections made in Nuvio as rows on Home. In Nuvio, open Collections and " +
                    "press Export File (or Export on Nuvio's phone page), then import them here.",
                Ui.textSecondary
            ).apply { setPadding(0, 12, 0, 24) })
            val importPhone = makePicker {
                stayInCollections = true
                showPhoneLink(
                    refresh,
                    title = "Import Nuvio collections",
                    instructions = "Scan with your phone, then paste Nuvio's collections export (or a link to it).",
                    pageHeading = "Paste the contents of nuvio-collections.json (from Nuvio's Export), or a link to the file.",
                    pagePlaceholder = "[ { \"id\": …, \"title\": …, \"folders\": [ … ] } ]",
                    pageButton = "Import to Orbit",
                    handle = ::importCollections
                )
            }.apply { text = "Import from phone (QR code)" }
            val importFile = makePicker {
                stayInCollections = true
                refresh("Reading Nuvio's export file...")
                thread {
                    val message = importNuvioExportFile()
                    Handler(Looper.getMainLooper()).post { if (currentScreen == "settings") refresh(message) }
                }
            }.apply { text = "Import Nuvio's export file" }
            collectionsSection.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(importPhone, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 })
                addView(importFile, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 14 })
            val collections = savedCollections()
            if (collections.isNotEmpty()) {
                collectionsSection.addView(Ui.text(this, Ui.Type.Overline, "ON HOME  ·  OK TO REMOVE", Ui.textMuted).apply {
                    setPadding(0, 18, 0, 14)
                })
            }
            collections.forEach { c ->
                collectionsSection.addView(makePicker {
                    stayInCollections = true
                    android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Remove ${c.title}?")
                        .setMessage("It's removed from Orbit's Home. Nuvio keeps its copy.")
                        .setPositiveButton("Remove") { _, _ ->
                            removeCollection(c.id)
                            refresh("Removed ${c.title}.")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }.apply {
                    text = "${c.title}  ·  ${c.folders.size} " + (if (c.folders.size == 1) "folder" else "folders") + "     ✕"
                }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 10 })
            }

            // After adding or removing something, stay in that section rather than jumping
            // back to the account buttons at the top.
            when {
                stayInCollections -> importPhone.requestFocus()
                stayInAddons -> addButton.requestFocus()
                firstRefresh -> profileFirst.requestFocus()
                else -> buttons.getChildAt(0)?.requestFocus()
            }
            firstRefresh = false
        }
        refresh(null)
    }

    // Subtitles section of Settings: each option is a pill showing its current value; OK
    // changes it (a list for choices, on/off for toggles).
    private fun buildSubtitleSettings(section: LinearLayout) {
        val subs = subtitleSettings
        fun render(focusLabel: String? = null) {
            section.removeAllViews()
            section.addView(Ui.text(this, Ui.Type.Headline, "Subtitles"))
            section.addView(Ui.text(
                this, Ui.Type.Body,
                "Chosen automatically when a video starts, from the subtitles inside the video " +
                    "file or your subtitle addons. You can still switch track from the player's settings button.",
                Ui.textSecondary
            ).apply { setPadding(0, 12, 0, 8) })

            fun group(title: String) = section.addView(
                Ui.text(this, Ui.Type.Overline, title, Ui.textMuted).apply { setPadding(0, 26, 0, 14) }
            )
            fun row(vararg pills: View) = section.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                pills.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 14 }) }
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 12 })
            fun option(label: String, value: String, onClick: () -> Unit) =
                makePicker(onClick).apply { text = "$label:  $value" ; tag = label }
            fun pickFrom(title: String, names: List<String>, current: String, set: (Int) -> Unit) =
                choose(title, names, names.indexOf(current)) { set(it); render(title) }
            fun languageOption(label: String, current: String, set: (String) -> Unit) =
                option(label, SubtitleSettings.languageName(current)) {
                    val codes = listOf("") + SubtitleSettings.languages.map { it.second }
                    choose(label, codes.map { SubtitleSettings.languageName(it) }, codes.indexOf(current)) {
                        set(codes[it])
                        render(label)
                    }
                }
            fun toggle(label: String, value: Boolean, set: (Boolean) -> Unit) =
                option(label, if (value) "On" else "Off") {
                    set(!value)
                    render(label)
                }

            group("LANGUAGES")
            row(
                languageOption("Subtitles", subs.language) { subs.language = it },
                languageOption("Second choice", subs.secondLanguage) { subs.secondLanguage = it }
            )
            group("BEHAVIOUR")
            row(toggle("Use forced subtitles", subs.forcedOnly) { subs.forcedOnly = it })
            section.addView(Ui.text(
                this, Ui.Type.Label,
                "When the audio is in your subtitle language, show only forced subtitles " +
                    "(signs and foreign-language lines): the video's own, else a forced file from " +
                    "your subtitle addons; if neither exists, show nothing.",
                Ui.textMuted
            ).apply { setPadding(0, 0, 0, 14) })
            row(toggle("Addon subtitles", subs.addonSubtitles) { subs.addonSubtitles = it })
            section.addView(Ui.text(
                this, Ui.Type.Label,
                "Also offer subtitles from your subtitle addons (e.g. OpenSubtitles). Used when the " +
                    "video has none in your language, and listed in the player's subtitle menu.",
                Ui.textMuted
            ).apply { setPadding(0, 0, 0, 14) })
            row(toggle("Only my languages", subs.onlyPreferred) { subs.onlyPreferred = it })
            section.addView(Ui.text(
                this, Ui.Type.Label,
                "Only add addon subtitles in your Subtitles and Second choice languages.",
                Ui.textMuted
            ).apply { setPadding(0, 0, 0, 14) })
            row(toggle("Strip SDH", subs.stripSdh) { subs.stripSdh = it })
            section.addView(Ui.text(
                this, Ui.Type.Label,
                "Hide sound descriptions like [door slams] and ♪, and speaker labels, from text subtitles.",
                Ui.textMuted
            ).apply { setPadding(0, 0, 0, 4) })
            group("STYLE")
            row(
                option("Size", subs.size) { pickFrom("Size", SubtitleSettings.sizes, subs.size) { subs.size = SubtitleSettings.sizes[it] } },
                option("Colour", subs.color) { pickFrom("Colour", SubtitleSettings.colors, subs.color) { subs.color = SubtitleSettings.colors[it] } },
                option("Background", subs.background) { pickFrom("Background", SubtitleSettings.backgrounds, subs.background) { subs.background = SubtitleSettings.backgrounds[it] } }
            )
            row(
                toggle("Outline", subs.outline) { subs.outline = it },
                toggle("Bold", subs.bold) { subs.bold = it },
                option("Position", subs.position) { pickFrom("Position", SubtitleSettings.positions, subs.position) { subs.position = SubtitleSettings.positions[it] } }
            )
            // Keep focus on the option just changed.
            focusLabel?.let { label -> section.findViewWithTag<View>(label)?.requestFocus() }
        }
        render()
    }

    private fun buildTmdbSettings(section: LinearLayout) {
        val ts = tmdbSettings
        fun render(focusLabel: String? = null) {
            section.removeAllViews()
            section.addView(Ui.text(this, Ui.Type.Headline, "TMDB enrichment"))
            section.addView(Ui.text(this, Ui.Type.Body,
                "Use TMDB as a metadata source to enhance addon data. Titles are matched by their " +
                    "IMDb or TMDB id, so details always belong to the right film or show.",
                Ui.textSecondary).apply { setPadding(0, 12, 0, 4) })
            fun toggle(label: String, value: Boolean, note: String, set: (Boolean) -> Unit) {
                section.addView(makePicker { set(!value); render(label) }.apply { text = "$label:  ${if (value) "On" else "Off"}"; tag = label },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24; bottomMargin = 8 })
                section.addView(Ui.text(this, Ui.Type.Label, note, Ui.textMuted))
            }
            toggle("TMDB enrichment", ts.enabled,
                "Details, cast, status, studios and director on film and show pages.") { ts.enabled = it }
            toggle("Title logos", ts.logos,
                "Show the title's logo artwork instead of plain text on Home and detail pages.") { ts.logos = it }
            toggle("More like this", ts.moreLikeThis,
                "A row of TMDB recommendations at the bottom of film and show pages.") { ts.moreLikeThis = it }
            toggle("Fill gaps in Discover", ts.fillDiscover,
                "When a catalog has no synopsis, backdrop, rating or genres for a title, take them from TMDB.") { ts.fillDiscover = it }
            focusLabel?.let { label -> section.findViewWithTag<View>(label)?.requestFocus() }
        }
        render()
    }

    private fun buildPlayerSettings(section: LinearLayout) {
        val ps = playerSettings
        fun render(focusLabel: String? = null) {
            section.removeAllViews()
            section.addView(Ui.text(this, Ui.Type.Headline, "Player"))
            fun option(label: String, value: String, note: String, onClick: () -> Unit) {
                section.addView(makePicker(onClick).apply { text = "$label:  $value"; tag = label },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24; bottomMargin = 8 })
                section.addView(Ui.text(this, Ui.Type.Label, note, Ui.textMuted))
            }
            fun toggle(label: String, value: Boolean, note: String, set: (Boolean) -> Unit) =
                option(label, if (value) "On" else "Off", note) { set(!value); render(label) }

            val subs = subtitleSettings
            option("Audio language", SubtitleSettings.languageName(subs.audioLanguage).let { if (it == "Off") "Video's default" else it },
                "Pick this audio track automatically when a video has several (e.g. English on a " +
                    "dual-language release).") {
                val codes = listOf("") + SubtitleSettings.languages.map { it.second }
                choose("Audio language", codes.map { if (it.isBlank()) "Video's default" else SubtitleSettings.languageName(it) },
                    codes.indexOf(subs.audioLanguage)) {
                    subs.audioLanguage = codes[it]
                    render("Audio language")
                }
            }
            option("Skip intro", ps.skipIntro,
                "Intro and recap times come from IntroDB. \u201cShow button\u201d offers Skip intro (press OK); " +
                    "\u201cSkip automatically\u201d jumps past it.") {
                choose("Skip intro", PlayerSettings.skipModes, PlayerSettings.skipModes.indexOf(ps.skipIntro)) {
                    ps.skipIntro = PlayerSettings.skipModes[it]
                    render("Skip intro")
                }
            }
            toggle("Next episode button", ps.nextEpisodeButton,
                "During the credits of an episode (or its last 45 seconds), offer the next episode; " +
                    "OK plays it using the same source when possible.") { ps.nextEpisodeButton = it }
            toggle("Autoplay next episode", ps.autoplayNext,
                "Start the next episode automatically when one ends.") { ps.autoplayNext = it }
            toggle("Match frame rate", ps.matchFrameRate,
                "Switch the TV to the video's frame rate (e.g. 24 Hz for films) for smoother motion. " +
                    "The screen goes dark for a moment while the TV switches.") { ps.matchFrameRate = it }
            focusLabel?.let { label -> section.findViewWithTag<View>(label)?.requestFocus() }
        }
        render()
    }

    // Email and password are sent to Stremio once; only the returned session key is saved.
    private fun showStremioLogin(onDone: (String?) -> Unit) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val email = android.widget.EditText(this).apply {
            hint = "Email"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = android.widget.EditText(this).apply {
            hint = "Password"
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        // The on-screen keyboard covers the form, so its ✓ key moves Email -> Password,
        // and on Password signs in, instead of needing the D-pad to leave the keyboard.
        email.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        form.addView(email)
        form.addView(password)

        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Sign in to Stremio")
            .setView(form)
            .setPositiveButton("Sign in") { _, _ ->
                val address = email.text.toString().trim()
                val secret = password.text.toString()
                if (address.isEmpty() || secret.isEmpty()) {
                    onDone("Enter your email and password.")
                    return@setPositiveButton
                }
                onDone("Signing in...")
                thread {
                    val message = try {
                        val session = StremioAccount.login(address, secret)
                        prefs.edit()
                            .putString("stremio_auth_key", session.authKey)
                            .putString("stremio_email", session.email)
                            .apply()
                        syncStremioAddons()
                    } catch (e: Exception) {
                        "Sign-in failed: ${e.message}"
                    }
                    Handler(Looper.getMainLooper()).post {
                        resetDiscover()
                        if (currentScreen == "settings") onDone(message)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else {
                false
            }
        }
    }

    // Discover: browse any addon catalog as a poster grid, filtered by type, catalog and
    // genre. Modelled on Nuvio's Discover tab, using Orbit's poster cards.
    private fun showDiscoverScreen() {
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

    // Recycling grid: only the visible rows exist as views, so scrolling stays smooth however
    // many pages are loaded. Position 0 is the header; position i + 1 is discoverResults[i].
    private inner class DiscoverAdapter :
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
    private fun makePicker(onClick: () -> Unit) = Ui.pill(this, "") { onClick() }

    private lateinit var infoBackdrop: ImageView
    private lateinit var infoOverline: TextView
    private lateinit var infoTitle: TextView
    private lateinit var infoChips: LinearLayout
    private lateinit var infoGenres: TextView
    private lateinit var infoOverview: TextView
    private val infoHandler = Handler(Looper.getMainLooper())

    // Fixed panel on the right of Discover: backdrop, type, title, chips, genres, synopsis.
    private fun makeDiscoverInfoPanel(): View {
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
    private fun formatCatalogRuntime(raw: String): String? {
        if (raw.isBlank()) return null
        val hours = Regex("(\\d+)\\s*h").find(raw)?.groupValues?.get(1)?.toInt() ?: 0
        val minutes = Regex("(\\d+)\\s*m").find(raw)?.groupValues?.get(1)?.toInt()
            ?: if (hours == 0) raw.filter { it.isDigit() }.toIntOrNull() else 0
        return Ui.formatRuntime(hours * 60 + (minutes ?: 0))
    }

    private fun showDiscoverInfo(meta: StremioAddons.Meta, immediate: Boolean = false) {
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

    private fun typeLabel(type: String) = when (type) {
        "movie" -> "Movies"
        "series" -> "Series"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    private fun catalogLabel(catalog: StremioAddons.Catalog): String {
        val addonCount = discoverCatalogs.orEmpty().map { it.baseUrl }.distinct().size
        return if (addonCount > 1) "${catalog.name}  ·  ${catalog.addonName}" else catalog.name
    }

    private fun selectedCatalog() =
        discoverCatalogs?.firstOrNull { it.key == discoverCatalogKey }

    private fun updatePickerLabels() {
        val catalog = selectedCatalog()
        typePicker.text = "${typeLabel(discoverType)}   ▾"
        catalogPicker.text = (catalog?.let { catalogLabel(it) }
            ?: if (discoverCatalogs == null) "Loading..." else "No catalogs") + "   ▾"
        genrePicker.text = (discoverGenre ?: "All genres") + "   ▾"
        genrePicker.visibility = if (catalog?.genres.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // A remote-friendly list dialog; picking an entry closes it.
    private fun choose(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        if (options.isEmpty()) return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(options.toTypedArray(), selected) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .show()
    }

    private fun chooseDiscoverType() {
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

    private fun chooseDiscoverCatalog() {
        val options = discoverCatalogs.orEmpty().filter { it.type == discoverType }
        choose(
            "Catalog",
            options.map(::catalogLabel),
            options.indexOfFirst { it.key == discoverCatalogKey }
        ) { i -> selectDiscoverCatalog(options[i]) }
    }

    private fun chooseDiscoverGenre() {
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

    private fun selectDiscoverCatalog(catalog: StremioAddons.Catalog) {
        discoverCatalogKey = catalog.key
        discoverType = catalog.type
        // Some catalogs (e.g. "New", by year) can't be listed without a genre.
        discoverGenre = if (catalog.genreRequired) catalog.genres.firstOrNull() else null
        updatePickerLabels()
        fetchDiscoverPage(reset = true)
    }

    private fun loadDiscoverCatalogs() {
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

    private fun fetchDiscoverPage(reset: Boolean) {
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

    private fun appendDiscoverCards(start: Int) {
        val adapter = discoverList.adapter ?: return
        // The previous last poster is no longer the end of its row.
        if (start > 0) adapter.notifyItemChanged(start)
        adapter.notifyItemRangeInserted(start + 1, discoverResults.size - start)
    }

    private fun openDiscoverItem(index: Int) {
        val meta = discoverResults.getOrNull(index) ?: return
        discoverFocusIndex = index
        openMeta(meta, discoverStatus, "discover")
    }

    // Opens an addon catalog item: films straight to their page; series need TMDB's id,
    // which some addons give directly and the rest are looked up from the IMDb id.
    private fun openMeta(meta: StremioAddons.Meta, status: TextView, fromScreen: String) {
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
                TmdbTest.findTvIdByImdb(meta.id, BuildConfig.TMDB_API_KEY)
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

    // Search: one query sent to every addon catalog that supports search; results come
    // back as one row per catalog, in the account's addon order (as Stremio shows them).
    private var searchQuery = ""
    private var searchRequest = 0
    private val searchRows = mutableListOf<Pair<StremioAddons.Catalog, List<StremioAddons.Meta>>>()
    private var searchPending = 0
    private var searchFocus: Pair<String, Int>? = null
    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchCatalogsCache: List<StremioAddons.Catalog>? = null
    private var moveToSearchResults = false

    private fun showSearchScreen() {
        useContentScroll()
        currentScreen = "search"
        detailsReturnScreen = "search"
        actorPages.clear()
        root.removeAllViews()
        root.gravity = Gravity.TOP
        contentScroll.scrollTo(0, 0)

        val tabs = addHeader("search")
        root.addView(Ui.text(this, Ui.Type.Title, "Search").apply { setPadding(0, 4, 0, 24) })

        val field = android.widget.EditText(this).apply {
            id = View.generateViewId()
            setText(searchQuery)
            setSelection(searchQuery.length)
            hint = "Movies and series"
            setHintTextColor(Ui.textMuted)
            setTextColor(Ui.textPrimary)
            textSize = 18f
            typeface = Ui.font("medium")
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(34, 22, 34, 22)
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            val paint = { focused: Boolean ->
                background = Ui.rounded(
                    if (focused) Ui.surfaceRaised else Ui.surface, Ui.pillRadius,
                    stroke = if (focused) 4 else 2, strokeColor = if (focused) Color.WHITE else Ui.outline
                )
            }
            paint(false)
            setOnFocusChangeListener { _, focused -> paint(focused) }
        }
        root.addView(field, LinearLayout.LayoutParams(1100, -2))
        pointTabsDownTo(tabs, field)

        val status = Ui.text(this, Ui.Type.Label, "", Ui.textSecondary).apply { setPadding(0, 22, 0, 0) }
        root.addView(status)
        val results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        root.addView(results, LinearLayout.LayoutParams(-1, -2))

        fun render() {
            results.removeAllViews()
            val found = searchRows.sumOf { it.second.size }
            status.text = when {
                searchQuery.length < 2 -> "Type at least two letters. Results come from your addons' search catalogs."
                searchPending > 0 && found == 0 -> "Searching your addons..."
                searchPending > 0 -> "$found results so far · waiting for $searchPending more..."
                found == 0 -> "Nothing found for \u201c$searchQuery\u201d."
                else -> "$found results for \u201c$searchQuery\u201d"
            }
            var firstRow: View? = null
            var restore: View? = null
            searchRows.forEach { (catalog, metas) ->
                results.addView(Ui.sectionTitle(this, "${typeLabel(catalog.type)}  ·  ${catalog.addonName}").apply {
                    setPadding(0, 30, 0, 0)
                })
                val (scroll, row) = makePosterRow()
                metas.forEachIndexed { i, meta ->
                    val card = makePosterCard(meta.name, meta.year, meta.poster, dimRow = false) {
                        searchFocus = catalog.key to i
                        openMeta(meta, status, "search")
                    }
                    row.addView(card, LinearLayout.LayoutParams(190, -2).apply { setMargins(0, 0, 26, 0) })
                    if (searchFocus == catalog.key to i) restore = card
                }
                pinRowEnds(row)
                addPosterRow(scroll, results)
                if (firstRow == null) firstRow = row.getChildAt(0)
            }
            firstRow?.let {
                if (it.id == View.NO_ID) it.id = View.generateViewId()
                field.nextFocusDownId = it.id
            }
            restore?.let {
                searchFocus = null
                it.requestFocus()
            }
            // After the search key, take the viewer to the first result once there is one.
            if (moveToSearchResults && firstRow != null && searchPending <= 0) {
                moveToSearchResults = false
                firstRow?.requestFocus()
            }
        }

        fun search(query: String) {
            searchQuery = query.trim()
            val request = ++searchRequest
            searchRows.clear()
            if (searchQuery.length < 2) {
                searchPending = 0
                render()
                return
            }
            searchPending = -1
            render()
            thread {
                val catalogs = searchCatalogsCache ?: StremioAddons.searchCatalogs()
                    .also { searchCatalogsCache = it }
                val order = catalogs.map { it.key }
                Handler(Looper.getMainLooper()).post {
                    if (request != searchRequest) return@post
                    searchPending = catalogs.size
                    render()
                }
                catalogs.forEach { catalog ->
                    streamLoader.execute {
                        val metas = try {
                            StremioAddons.fetchCatalogPage(catalog, null, 0, searchQuery).metas
                        } catch (_: Exception) {
                            emptyList()
                        }
                        Handler(Looper.getMainLooper()).post {
                            if (request != searchRequest || currentScreen != "search") return@post
                            searchPending--
                            if (metas.isNotEmpty()) {
                                searchRows.add(catalog to metas)
                                searchRows.sortBy { order.indexOf(it.first.key) }
                            }
                            // Keep typing focus in the field while results arrive.
                            render()
                        }
                    }
                }
            }
        }

        // Search a moment after typing stops, or straight away on the keyboard's search key.
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(text: android.text.Editable?) {
                searchHandler.removeCallbacksAndMessages(null)
                val query = text?.toString().orEmpty()
                if (query.trim() == searchQuery) return
                searchHandler.postDelayed({ if (currentScreen == "search") search(query) }, 700)
            }
        })
        // The on-screen keyboard's search key sends IME_ACTION_SEARCH; Enter on a remote or
        // keyboard arrives as IME_NULL with the key event.
        field.setOnEditorActionListener { _, actionId, event ->
            val enterKey = actionId == android.view.inputmethod.EditorInfo.IME_NULL &&
                event?.keyCode == KeyEvent.KEYCODE_ENTER
            if (enterKey && event?.action != KeyEvent.ACTION_DOWN) return@setOnEditorActionListener true
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || enterKey) {
                searchHandler.removeCallbacksAndMessages(null)
                search(field.text.toString())
                // Close the keyboard and move to the results.
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(field.windowToken, 0)
                moveToSearchResults = true
                true
            } else {
                false
            }
        }

        // Back from a result restores focus to that poster (render does it); otherwise
        // start in the search box.
        val returningToResult = searchFocus != null
        render()
        if (!returningToResult) field.requestFocus()
    }

    // Finds the film's IMDb id through TMDB when it wasn't opened from an addon catalog.
    private fun resolveFilmImdbId(title: String): String? {
        val key = BuildConfig.TMDB_API_KEY
        val metadata = TmdbTest.fetchMetadata(title, key) ?: return null
        if (metadata.mediaType != "movie") return null
        return TmdbTest.fetchImdbId("movie", metadata.tmdbId, key)
    }

    // Streams screen: asks every stream addon at once and lists their links as they arrive,
    // grouped by addon in the order of the Stremio account (as Stremio does).
    private fun showStreams(
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
        val request = ++streamsRequest
        showStreamsScreen()

        thread {
            val id = try {
                resolveId()
            } catch (_: Exception) {
                null
            }
            val addons = if (id == null) emptyList() else StremioStreams.addonsFor(type, id)
            Handler(Looper.getMainLooper()).post {
                if (request != streamsRequest) return@post
                streamsWatchId = id
                // Fetch addon subtitles now, while the viewer picks a stream.
                streamsSubtitles = if (id != null && subtitleSettings.addonSubtitles) {
                    streamLoader.submit<List<StremioSubtitles.Subtitle>> {
                        try {
                            StremioSubtitles.fetch(type, id)
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }
                } else null
                streamsPending = addons.size
                streamAddonOrder = addons.map { it.name }
                streamsNote = when {
                    id == null -> "Couldn't find this title's IMDb id, so your addons can't be asked for it."
                    addons.isEmpty() -> "None of your Stremio addons offer streams for this."
                    else -> null
                }
                if (currentScreen == "streams") updateStreamsStatus()
            }
            if (id == null) return@thread
            addons.forEach { addon ->
                streamLoader.execute {
                    val streams = try {
                        StremioStreams.fetchStreams(addon, type, id)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    Handler(Looper.getMainLooper()).post {
                        if (request != streamsRequest) return@post
                        streamsPending--
                        if (streams.isNotEmpty()) {
                            // Keep the account's addon order, whatever order answers arrive in.
                            streamGroups.add(addon.name to streams)
                            streamGroups.sortBy { streamAddonOrder.indexOf(it.first) }
                            if (currentScreen == "streams") insertStreamGroup(addon.name, streams)
                        }
                        if (currentScreen == "streams") updateStreamsStatus()
                    }
                }
            }
        }
    }

    // Builds the screen once; addons' results are slotted in as they arrive (insertStreamGroup)
    // rather than rebuilding the whole list each time, which briefly froze the remote.
    private fun showStreamsScreen() {
        useContentScroll()
        currentScreen = "streams"
        root.removeAllViews()

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

    private fun updateStreamsStatus() {
        val status = streamsStatus ?: return
        val found = streamGroups.sumOf { it.second.size }
        status.text = listOfNotNull(
            playbackError,
            streamsNote,
            when {
                streamsPending > 0 -> "$found streams so far · waiting for $streamsPending " +
                    (if (streamsPending == 1) "addon" else "addons") + "..."
                streamsPending == 0 && found == 0 -> "No streams found."
                streamsPending == 0 -> "$found streams · OK plays in Orbit, hold OK to open in another app."
                else -> null
            }
        ).joinToString("\n")
        streamTabs?.getChildAt(0)?.let { (it as TextView).text = "All  ·  $found" }
        status.setTextColor(if (playbackError != null) Color.rgb(255, 170, 150) else Color.LTGRAY)
    }

    private fun insertStreamGroup(addonName: String, streams: List<StremioStreams.Stream>) {
        val list = streamsList ?: return
        val group = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            tag = addonName
        }
        group.addView(Ui.text(this, Ui.Type.Overline, "${addonName.uppercase()}  ·  ${streams.size}", Ui.textMuted).apply {
            setPadding(0, 30, 0, 14)
        })
        var restore: View? = null
        streams.forEach { stream ->
            val key = stream.url ?: stream.youtubeId
            val row = makeStreamRow(stream) { streamFocusKey = key }
            group.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
            if (key != null && key == streamFocusKey) restore = row
        }

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
        currentFocus?.let { focused ->
            if (isDescendantOf(focused, list)) focused.post {
                focused.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, focused.width, focused.height)
                )
            }
        }

        val focused = currentFocus
        when {
            !streamsTouched -> {
                var top: View? = null
                for (i in 0 until list.childCount) {
                    val g = list.getChildAt(i) as ViewGroup
                    if (g.visibility == View.VISIBLE) {
                        top = g.getChildAt(1)
                        break
                    }
                }
                top?.requestFocus()
                contentScroll.post { contentScroll.scrollTo(0, 0) }
            }
            restore != null && group.visibility == View.VISIBLE -> restore!!.requestFocus()
            // First results: move from BACK onto the first stream, as before.
            focused == null || focused === streamsBack ->
                if (streamFocusKey == null && group.visibility == View.VISIBLE) {
                    group.getChildAt(1)?.requestFocus()
                }
        }
    }

    // addonName null is the All tab.
    private fun makeStreamTab(addonName: String?, count: Int = 0) =
        Ui.pill(this, if (addonName == null) "All" else "$addonName  ·  $count") {
            selectStreamTab(addonName)
        }.apply {
            id = View.generateViewId()
            tag = addonName
            onFocusChangeListener = View.OnFocusChangeListener { v, _ -> paintStreamTab(v as TextView) }
            paintStreamTab(this)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 }
        }

    private fun paintStreamTab(tab: TextView) = Ui.paintPill(tab, tab.tag == streamTab)

    private fun selectStreamTab(addonName: String?) {
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
    private fun updateStreamTabs() {
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
    private fun makeStreamRow(stream: StremioStreams.Stream, onFocused: () -> Unit): LinearLayout {
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

    private fun playStream(stream: StremioStreams.Stream) {
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
        // Addon subtitles are normally fetched by now; wait up to 3 s if not.
        val play = { startAt: Long ->
            val pending = streamsSubtitles.takeUnless { trailer }
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

    private fun addonSubtitlesFrom(
        future: java.util.concurrent.Future<List<StremioSubtitles.Subtitle>>?
    ): List<StremioSubtitles.Subtitle> = try {
        future?.takeIf { it.isDone }?.get().orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    // Saves where the viewer is in the current title (called every 10 s and on exit).
    private fun saveProgress(finished: Boolean = false) {
        val p = player ?: return
        val id = playingId ?: return
        val target = playingTarget ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        val position = if (finished) duration else p.currentPosition
        if (position <= 0) return
        homeDirty = true // Continue rows change
        progress.save(
            WatchProgress.Entry(
                id, target.type, target.title, target.episodeLabel, target.poster,
                target.tvId, target.season, target.episode,
                position, duration, System.currentTimeMillis()
            )
        )
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            saveProgress()
            progressHandler.postDelayed(this, 10_000)
        }
    }

    // Hands the link to another app (Kodi, a YouTube app for trailers, …).
    private fun openStreamElsewhere(stream: StremioStreams.Stream) {
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

    private fun leaveStreams() {
        streamsRequest++
        when (streamsReturnScreen) {
            "home" -> showHomeScreen()
            "tv_details" -> showTvDetails(lastTvId, lastFilmTitle, lastPosterFile)
            else -> showFilmDetails(lastFilmTitle, lastPosterFile)
        }
    }

    // Backdrop header shared by film and show pages: back link, then title, chips, tagline,
    // overview and buttons laid over a full-width backdrop that fades into the page.
    private class DetailHero(
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

    private fun addDetailHero(kind: String, title: String, height: Int = 800): DetailHero {
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
    private fun fillDetailHero(hero: DetailHero, details: TmdbMeta.Details, extraChips: List<String> = emptyList()) {
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

    private fun showLogo(view: ImageView, path: String, onShown: () -> Unit) {
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
    private fun tmdbDetailsFor(mediaType: String, title: String, tmdbId: Int?, imdbId: String?): TmdbMeta.Details? {
        val key = BuildConfig.TMDB_API_KEY
        val ref = tmdbId?.let { mediaType to it }
            ?: imdbId?.let { TmdbMeta.resolve(it, mediaType, key) }
            ?: TmdbMeta.search(title, mediaType, key)
            ?: return null
        return TmdbMeta.details(ref.first, ref.second, key)
    }

    // "Trailer" beside Play: the TMDB trailer, opened in the TV's YouTube app.
    private fun addTrailerButton(hero: DetailHero, youtubeKey: String?) {
        youtubeKey ?: return
        lateinit var button: TextView
        button = Ui.button(this, "▶  Trailer") { playTrailer(youtubeKey, button) }
        hero.buttons.addView(button, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = 20 })
        linkButtonRow(hero.buttons)
    }

    // Left/right move along the page's buttons and stop at the ends, rather than jumping
    // down to whatever lies below (the seasons, the cast).
    private fun linkButtonRow(row: LinearLayout) {
        val buttons = (0 until row.childCount).map { row.getChildAt(it) }
        buttons.forEach { if (it.id == View.NO_ID) it.id = View.generateViewId() }
        buttons.forEachIndexed { i, b ->
            b.nextFocusLeftId = buttons.getOrNull(i - 1)?.id ?: b.id
            b.nextFocusRightId = buttons.getOrNull(i + 1)?.id ?: b.id
        }
    }

    private val youtubeApps = listOf(
        "io.gh.reisxd.tizentube.cobalt",            // TizenTube
        "com.teamsmart.videomanager.tv",            // SmartTube
        "com.liskovsoft.smarttubetv.beta",
        "com.liskovsoft.smarttubetv",
        "com.google.android.youtube.tv",
        "com.google.android.youtube.googletv"
    )

    // Plays the trailer in Orbit's player; Back returns to the page.
    private fun playTrailer(youtubeKey: String, button: TextView) {
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
    private fun openTrailer(youtubeKey: String) {
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
    private fun addMoreLikeThis(items: List<TmdbTest.CatalogueItem>) {
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

    private fun fillDetailHero(hero: DetailHero, live: TmdbTest.FilmMetadata, extraChips: List<String> = emptyList()) {
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

    private fun showFilmDetails(
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
                TmdbTest.fetchMetadata(title, BuildConfig.TMDB_API_KEY)
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
                        if (lastFilmImdbId == null) rich.imdbId?.let { lastFilmImdbId = it; showResume() }
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



    private var lastTvId: Int = -1

    private fun showTvDetails(tvId: Int, title: String, posterPath: String, focusCastIndex: Int = -1) {
        useContentScroll()
        if (!showImdbIds.containsKey(tvId)) {
            thread {
                try {
                    TmdbTest.fetchImdbId("tv", tvId, BuildConfig.TMDB_API_KEY)
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
                    val episodes = TmdbTest.fetchTvEpisodes(
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
                                        ?: TmdbTest.fetchImdbId(
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
                val details = rich?.metadata ?: TmdbTest.fetchMetadata(title, key)
                val seasons = TmdbTest.fetchTvSeasons(tvId, key)

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
    private fun makeEpisodeCard(
        episode: TmdbTest.TvEpisode,
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

    private fun addCastSection(
        title: String,
        focusCastIndex: Int = -1,
        screen: String = "details",
        source: (() -> List<TmdbTest.CastMember>?)? = null
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
                    ?: if (screen == "details") TmdbTest.fetchCast(title, BuildConfig.TMDB_API_KEY) else emptyList()
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

    // Actor pages opened so far, newest last, so Back retraces show → actor → film → actor.
    // Each remembers the page it was opened from and where Back from that page led.
    private data class ActorPage(
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
    private val actorPages = ArrayDeque<ActorPage>()

    private fun openActor(member: TmdbTest.CastMember, index: Int, fromShow: Boolean) {
        val page = ActorPage(
            member.name, member.id, member.profilePath, lastFilmTitle, lastPosterFile, fromShow, lastTvId,
            lastFilmTmdbId, lastFilmImdbId, index, detailsReturnScreen
        )
        actorPages.addLast(page)
        showActorProfile(page)
    }

    private fun leaveActor() {
        val page = actorPages.removeLastOrNull() ?: return showHomeScreen()
        detailsReturnScreen = page.previousReturn
        if (page.fromShow) showTvDetails(page.fromTvId, page.fromTitle, page.fromPoster, page.castIndex)
        else showFilmDetails(page.fromTitle, page.fromPoster, page.castIndex, page.fromImdbId, page.fromTmdbId)
    }

    private fun showActorProfile(page: ActorPage) {
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

    // Round avatar with the profile's initial; a white ring when focused.
    private fun makeAvatar(p: Profiles.Profile, size: Int): TextView = TextView(this).apply {
        text = p.name.take(1).uppercase()
        gravity = Gravity.CENTER
        textSize = size / 5.2f
        typeface = Ui.font("bold")
        includeFontPadding = false
        setTextColor(Color.WHITE)
        isFocusable = true
        isClickable = true
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        val paint = { focused: Boolean ->
            background = Ui.rounded(p.color, 1000f, stroke = if (focused) maxOf(3, size / 30) else 0, strokeColor = Color.WHITE)
            animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f).setDuration(140).start()
        }
        paint(false)
        setOnFocusChangeListener { _, focused -> paint(focused) }
    }

    // "Who's watching?": shown at startup when there's more than one profile, and from the
    // avatar in the header.
    private fun showProfilePicker() {
        useContentScroll()
        currentScreen = "profiles"
        root.removeAllViews()
        root.gravity = Gravity.CENTER
        contentScroll.scrollTo(0, 0)

        root.addView(Ui.text(this, Ui.Type.Display, "Who's watching?").apply {
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 70)
        }, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        root.addView(row, LinearLayout.LayoutParams(-1, -2))

        fun tile(avatar: TextView, label: String, onClick: () -> Unit): LinearLayout {
            val name = Ui.text(this, Ui.Type.BodyStrong, label, Ui.textSecondary, maxLines = 1).apply {
                gravity = Gravity.CENTER
                setPadding(0, 26, 0, 0)
            }
            avatar.setOnClickListener { onClick() }
            avatar.onFocusChangeListener = chainFocus(avatar.onFocusChangeListener) { _, focused ->
                name.setTextColor(if (focused) Color.WHITE else Ui.textSecondary)
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                clipChildren = false
                addView(avatar, LinearLayout.LayoutParams(180, 180))
                addView(name, LinearLayout.LayoutParams(240, -2))
            }.also { row.addView(it, LinearLayout.LayoutParams(-2, -2).apply { setMargins(24, 0, 24, 0) }) }
        }

        val all = Profiles.list(this)
        val avatars = all.map { p ->
            val avatar = makeAvatar(p, 180)
            tile(avatar, p.name) {
                if (p.id == profile.id) {
                    Profiles.chosenThisLaunch = true
                    showHomeScreen()
                } else switchProfile(p.id)
            }
            avatar
        }
        val add = TextView(this).apply {
            text = "+"
            gravity = Gravity.CENTER
            textSize = 48f
            includeFontPadding = false
            setTextColor(Ui.textSecondary)
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            val paint = { focused: Boolean ->
                background = Ui.rounded(Ui.surface, 1000f, stroke = if (focused) 6 else 3,
                    strokeColor = if (focused) Color.WHITE else Ui.surfaceRaised)
                setTextColor(if (focused) Color.WHITE else Ui.textSecondary)
                animate().scaleX(if (focused) 1.1f else 1f).scaleY(if (focused) 1.1f else 1f).setDuration(140).start()
            }
            paint(false)
            setOnFocusChangeListener { _, focused -> paint(focused) }
        }
        tile(add, "Add profile") {
            promptText("New profile", "Name", "") { name -> switchProfile(Profiles.add(this, name).id) }
        }
        val manage = Ui.pill(this, "Manage profiles") {
            Profiles.chosenThisLaunch = true
            showSettingsScreen()
        }
        root.addView(manage, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = 80
        })
        avatars[all.indexOfFirst { it.id == profile.id }.coerceAtLeast(0)].requestFocus()
    }

    // Opens Orbit again as another profile: its own addons, settings and Continue watching.
    private fun switchProfile(id: String) {
        Profiles.setActive(this, id)
        Profiles.chosenThisLaunch = true
        StremioAddons.addonUrls = emptyList()
        recreate()
    }

    // A one-line text dialog; onDone gets the text when it isn't blank.
    private fun promptText(title: String, hint: String, initial: String, numeric: Boolean = false, onDone: (String) -> Unit) {
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
            .setPositiveButton("OK") { _, _ -> field.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onDone) }
            .setNegativeButton("Cancel", null)
            .show()
        field.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
    }

    // Profiles section of Settings. Returns its first button, which Settings focuses first.
    private fun buildProfilesSettings(section: LinearLayout): View {
        section.addView(Ui.text(this, Ui.Type.Headline, "Profiles"))
        val all = Profiles.list(this)
        section.addView(Ui.text(this, Ui.Type.Body,
            "You're using ${profile.name}. Each profile has its own Stremio account, addons, " +
                "collections, Continue watching and settings. Send a profile to copy it to another " +
                "device on your Wi-Fi.",
            Ui.textSecondary).apply { setPadding(0, 12, 0, 24) })
        fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            .also { section.addView(it, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 16 }) }
        val first = row()
        val second = row()
        fun button(into: LinearLayout, label: String, onClick: () -> Unit) =
            makePicker(onClick).apply { text = label }
                .also { into.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 }) }

        val switch = button(first, "Switch profile") { showProfilePicker() }
        button(first, "Add profile") {
            promptText("New profile", "Name", "") { name -> switchProfile(Profiles.add(this, name).id) }
        }
        button(first, "Rename ${profile.name}") {
            promptText("Rename profile", "Name", profile.name) { name ->
                Profiles.rename(this, profile.id, name)
                recreate() // the header avatar and names follow
            }
        }
        val others = all.filter { it.id != profile.id }
        if (others.isNotEmpty()) button(first, "Delete a profile") {
            android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Delete which profile?")
                .setItems(others.map { it.name }.toTypedArray()) { _, which ->
                    val victim = others[which]
                    android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Delete ${victim.name}?")
                        .setMessage("Its addons, Stremio sign-in, collections, settings and watch progress on this device are removed.")
                        .setPositiveButton("Delete") { _, _ ->
                            Profiles.delete(this, victim.id)
                            showSettingsScreen()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .show()
        }
        button(second, "Send ${profile.name} to another device") { showSendProfile() }
        button(second, "Receive a profile") { showReceiveProfile() }
        return switch
    }

    private var transferServer: ProfileTransfer.Server? = null

    private fun leaveTransfer() {
        transferServer?.stop()
        transferServer = null
        showSettingsScreen()
    }

    private fun transferPage(title: String): LinearLayout {
        useContentScroll()
        currentScreen = "transfer"
        root.removeAllViews()
        root.gravity = Gravity.TOP
        contentScroll.scrollTo(0, 0)
        val back = TextView(this).apply {
            text = "‹   Settings"
            isFocusable = true
            isClickable = true
            setOnClickListener { leaveTransfer() }
        }
        styleLinkFocus(back)
        root.addView(back, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = -22 })
        root.addView(Ui.text(this, Ui.Type.Title, title).apply { setPadding(0, 36, 0, 28) })
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 38, 44, 40)
            background = Ui.rounded(Ui.surface, 20f)
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, -2)) }
    }

    // Sending: this device waits with a PIN until the other one downloads the profile.
    private fun showSendProfile() {
        val panel = transferPage("Send ${profile.name}")
        val payload = Profiles.export(this, profile.id).toString()
        lateinit var server: ProfileTransfer.Server
        val status = Ui.text(this, Ui.Type.Body, "", Ui.textSecondary)
        server = ProfileTransfer.Server(this, payload, profile.name) { device ->
            Handler(Looper.getMainLooper()).post {
                if (transferServer !== server) return@post
                status.text = "✓  Sent to $device. You can send it to another device too, or go back."
                status.setTextColor(Ui.textPrimary)
            }
        }
        val address = server.start()
        transferServer = server
        if (address == null) {
            panel.addView(Ui.text(this, Ui.Type.Body, "This device isn't connected to a network.", Ui.textSecondary))
        } else {
            panel.addView(Ui.text(this, Ui.Type.Body,
                "On the other device, open Orbit → Settings → Profiles → Receive a profile. It finds " +
                    "this ${ProfileTransfer.deviceName(this)} on your Wi-Fi; choose it and enter this PIN:",
                Ui.textSecondary))
            panel.addView(Ui.text(this, Ui.Type.Display, server.pin.toCharArray().joinToString("  ")).apply {
                textSize = 72f
                setPadding(0, 30, 0, 30)
            })
            panel.addView(Ui.text(this, Ui.Type.Label,
                "If it isn't found, choose \"Enter address\" there and type  $address", Ui.textMuted))
            status.text = "Waiting for the other device..."
            panel.addView(status.apply { setPadding(0, 30, 0, 0) })
            panel.addView(Ui.text(this, Ui.Type.Caption,
                "Includes the Stremio sign-in. Only someone who can see this PIN can receive it.", Ui.textMuted).apply {
                setPadding(0, 18, 0, 0)
            })
        }
        root.getChildAt(0).requestFocus()
    }

    // Receiving: find devices that are sending, then download with their PIN.
    private fun showReceiveProfile() {
        val panel = transferPage("Receive a profile")
        val status = Ui.text(this, Ui.Type.Body, "", Ui.textSecondary).apply { setPadding(0, 0, 0, 24) }
        panel.addView(status)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(list)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 12 })

        fun receive(host: String, port: Int, label: String) {
            promptText("PIN shown on $label", "4-digit PIN", "", numeric = true) { pin ->
                status.text = "Receiving..."
                thread {
                    val result = try {
                        val profileJson = ProfileTransfer.fetch(this, host, port, pin)
                        Profiles.import(this, org.json.JSONObject(profileJson))
                    } catch (e: ProfileTransfer.WrongPin) {
                        "That PIN isn't right. Check the other screen and try again."
                    } catch (e: Exception) {
                        e.message ?: "Couldn't receive the profile."
                    }
                    Handler(Looper.getMainLooper()).post {
                        if (currentScreen != "transfer") return@post
                        if (result is String) {
                            status.text = result
                            return@post
                        }
                        val received = result as Profiles.Profile
                        status.text = "✓  Added the profile ${received.name} to this device."
                        status.setTextColor(Ui.textPrimary)
                        list.removeAllViews()
                        actions.removeAllViews()
                        actions.addView(Ui.pill(this, "Switch to ${received.name}") { switchProfile(received.id) },
                            LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 })
                        actions.addView(Ui.pill(this, "Done") { leaveTransfer() })
                        actions.getChildAt(0).requestFocus()
                    }
                }
            }
        }

        lateinit var search: () -> Unit
        search = {
            status.text = "Looking for devices sending a profile..."
            list.removeAllViews()
            actions.removeAllViews()
            thread {
                val found = ProfileTransfer.discover(this)
                Handler(Looper.getMainLooper()).post {
                    if (currentScreen != "transfer") return@post
                    status.text = if (found.isEmpty())
                        "No device found. On the other device choose \"Send … to another device\" in Settings → Profiles, then search again."
                    else "Choose the device to receive from:"
                    found.forEach { sender ->
                        list.addView(Ui.pill(this, "${sender.profile}  ·  from ${sender.device}") {
                            receive(sender.host, sender.port, sender.device)
                        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 14 })
                    }
                    actions.addView(Ui.pill(this, "Search again") { search() },
                        LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 })
                    actions.addView(Ui.pill(this, "Enter address") {
                        promptText("Address shown on the other device", "192.168.1.20:41234", "") { input ->
                            val host = input.substringBefore(':').trim()
                            val port = input.substringAfter(':', "").trim().toIntOrNull()
                            if (host.isEmpty() || port == null) status.text = "Type the address as shown, like 192.168.1.20:41234."
                            else receive(host, port, host)
                        }
                    })
                    (list.getChildAt(0) ?: actions.getChildAt(0)).requestFocus()
                }
            }
        }
        root.getChildAt(0).requestFocus()
        search()
    }

    // ---- Loading overlay: covers the player until the first frame is on screen ----

    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingBackdrop: ImageView
    private lateinit var loadingLogo: ImageView
    private lateinit var loadingTitle: TextView
    private lateinit var loadingSubtitle: TextView
    private lateinit var loadingSource: TextView
    private lateinit var loadingStatus: TextView
    private var loadingRequest = 0

    private fun buildLoadingOverlay(): FrameLayout {
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

    private fun showLoadingOverlay(watchId: String?, target: WatchTarget?) {
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

    private fun detailsTypeIsTv() = playerReturnScreen == "tv_details"

    private fun hideLoadingOverlay(animate: Boolean = true) {
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

    private fun playTestVideo() = startPlayback(testUrl, emptyMap())

    private fun startPlayback(
        url: String,
        headers: Map<String, String>,
        watchId: String? = null,
        target: WatchTarget? = null,
        startAtMs: Long = 0L,
        addonSubtitles: List<StremioSubtitles.Subtitle> = emptyList()
    ) {
        playingId = watchId
        playingTarget = target
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
        // Debrid links redirect to the file host and some addons need request headers.
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(30000)
            .setDefaultRequestProperties(headers)
        val renderers = androidx.media3.exoplayer.DefaultRenderersFactory(this)
            // Try other decoders when the preferred one can't handle a stream.
            .setEnableDecoderFallback(true)
        val subs = subtitleSettings
        subs.applyStyle(playerView.subtitleView)
        // The control bar's CC button opens Orbit's subtitle menu rather than Media3's own.
        playerView.setShowSubtitleButton(true)
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_subtitle)?.setOnClickListener {
            showSubtitleMenu()
        }
        var subtitleChosen = false
        player = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(http)
            )
            .build()
            .also {
                // Orbit picks the subtitle track itself (see SubtitleSettings.choose), so the
                // player starts with subtitles off; the preferred audio language goes first.
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .apply { if (subs.audioLanguage.isNotBlank()) setPreferredAudioLanguage(subs.audioLanguage) }
                    .build()
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
                        if (activeAddonTrack != null) return
                        if (subs.stripSdh) playerView.subtitleView?.setCues(subs.filterCues(cueGroup.cues))
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // The message shows on the streams screen; elsewhere (trailers, …) a toast instead.
                        if (playerReturnScreen == "streams") playbackError = "Couldn't play that stream (${error.errorCodeName}). " +
                            "Try another one, or hold OK on it to open it in another app."
                        else android.widget.Toast.makeText(this@MainActivity,
                            if (playingTarget == null) "Couldn't play the trailer." else "Couldn't play that stream.",
                            android.widget.Toast.LENGTH_LONG).show()
                        leavePlayer()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (state != androidx.media3.common.Player.STATE_ENDED) return
                        saveProgress(finished = true)
                        if (playerSettings.autoplayNext) nextEpisode?.let { playNextEpisode(it) }
                    }

                    override fun onRenderedFirstFrame() {
                        hideLoadingOverlay()
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
                val item = MediaItem.Builder().setUri(url).apply {
                    if (".m3u8" in url) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                }.build()
                if (startAtMs > 0) it.setMediaItem(item, startAtMs) else it.setMediaItem(item)
                it.prepare()
                it.play()
            }
    }

    private fun isDescendantOf(view: View, parent: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    // Subtitle menu in the player: Off plus every subtitle track, the current one ticked.
    private fun showSubtitleMenu() {
        val p = player ?: return
        val entries = mutableListOf<Pair<String, (() -> Unit)>>()
        var selected = 0
        val textDisabled = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val delayLabel = "Subtitle delay:  " + formatDelay(subtitleDelayMs)
        entries += delayLabel to { showSubtitleDelay() }
        entries += "Off" to {
            hideAddonSubtitle()
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
        selected = 1
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
        if (entries.size == 2) {
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
        choose("Subtitles", names, selected) { entries[it].second() }
    }

    // ---- Skip intro, next episode, frame-rate matching ---------------------------------

    private val extrasTicker = object : Runnable {
        override fun run() {
            updatePlayerAction()
            progressHandler.postDelayed(this, 500)
        }
    }

    private fun startPlayerExtras(watchId: String?, target: WatchTarget?) {
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

    private fun stopPlayerExtras() {
        progressHandler.removeCallbacks(extrasTicker)
        progressHandler.removeCallbacks(addonSubtitleTicker)
        activeAddonTrack = null
        playerView.subtitleView?.setCues(emptyList())
        frameTimer = null
        playerAction.visibility = View.GONE
        restoreDisplayMode()
    }

    // Shows (or auto-runs) the action for the current position.
    private fun updatePlayerAction() {
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

    private fun showPlayerAction(label: String, run: () -> Unit) {
        playerActionRun = run
        if (playerAction.text != "$label    OK") playerAction.text = "$label    OK"
        if (playerAction.visibility != View.VISIBLE) playerAction.visibility = View.VISIBLE
    }

    // The next episode in the season, else the first of the next season. Call off the
    // main thread.
    private fun findNextEpisode(id: String, target: WatchTarget): NextEpisode? = try {
        val key = BuildConfig.TMDB_API_KEY
        val showImdb = id.substringBefore(':')
        val seasons = TmdbTest.fetchTvSeasons(target.tvId, key).filter { it.seasonNumber >= 1 }
        val current = seasons.firstOrNull { it.seasonNumber == target.season }
        val (season, episode) = when {
            current != null && target.episode < current.episodeCount -> target.season to target.episode + 1
            else -> seasons.firstOrNull { it.seasonNumber > target.season }?.let { it.seasonNumber to 1 }
        } ?: return null
        val name = TmdbTest.fetchTvEpisodes(target.tvId, season, key)
            .firstOrNull { it.episodeNumber == episode }?.name.orEmpty()
        NextEpisode(
            target.copy(season = season, episode = episode, episodeLabel = "S$season · E$episode  $name"),
            "$showImdb:$season:$episode"
        )
    } catch (_: Exception) {
        null
    }

    // Prefers the same release (Stremio's bingeGroup) from the same addon, as Stremio does.
    private fun findNextStream(next: NextEpisode) {
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

    private fun playNextEpisode(next: NextEpisode) {
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
    private fun matchFrameRate(p: ExoPlayer) {
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
                if (sorted.size < 10 || span <= 0) return@VideoFrameMetadataListener
                val fps = FrameRate.snap((sorted.size - 1) * 1_000_000f / span)
                Handler(Looper.getMainLooper()).post {
                    frameTimer?.let { t -> p.clearVideoFrameMetadataListener(t) }
                    if (player === p && fps > 0f) matchFrameRate(p, fps)
                }
            }
        }
        frameTimer = timer
        p.setVideoFrameMetadataListener(timer)
    }

    private var frameTimer: androidx.media3.exoplayer.video.VideoFrameMetadataListener? = null

    private fun matchFrameRate(p: ExoPlayer, fps: Float) {
        val display = window.decorView.display ?: return
        val mode = FrameRate.bestMode(display, fps) ?: return
        if (mode.modeId == display.mode.modeId) return
        if (originalDisplayMode == 0) originalDisplayMode = display.mode.modeId
        val wasPlaying = p.playWhenReady
        p.playWhenReady = false
        window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
        progressHandler.postDelayed({ if (player === p && wasPlaying) p.playWhenReady = true }, 2500)
    }

    private fun restoreDisplayMode() {
        if (originalDisplayMode == 0) return
        window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
        originalDisplayMode = 0
    }

    private fun formatDelay(ms: Long) =
        (if (ms > 0) "+" else if (ms < 0) "−" else "") + String.format(java.util.Locale.US, "%.1f s", kotlin.math.abs(ms) / 1000.0)

    // Loads (once) and shows an addon subtitle, turning the video's own subtitles off.
    private fun showAddonSubtitle(sub: StremioSubtitles.Subtitle) {
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        val start = { track: AddonSubtitleTrack ->
            activeAddonTrack = track
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

    private fun hideAddonSubtitle() {
        activeAddonTrack = null
        progressHandler.removeCallbacks(addonSubtitleTicker)
        playerView.subtitleView?.setCues(emptyList())
    }

    // Shift addon subtitles: + shows them later, − earlier. Applies straight away.
    private fun showSubtitleDelay() {
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

    private fun leavePlayer() {
        hideLoadingOverlay(animate = false)
        progressHandler.removeCallbacks(progressTicker)
        stopPlayerExtras()
        saveProgress()
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
    private fun handlePlayerKey(event: KeyEvent): Boolean {
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

    // Up/down on the home screen moves between rows and returns to the poster last used in each.
    private fun handleHomeKey(event: KeyEvent): Boolean {
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
                // First Back hides the controls, the next one leaves the player.
                if (playerView.isControllerFullyVisible) playerView.hideController()
                else leavePlayer()
            }
            "actor" -> leaveActor()
            "profiles" -> if (Profiles.chosenThisLaunch) showHomeScreen() else finish()
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
