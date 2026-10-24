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

// Settings: the Stremio account and addons, subtitles, player, TMDB and Trakt panels.

internal fun MainActivity.showSettingsScreen() {
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

    val traktSection = panel()
    buildTraktSettings(traktSection)
    val torBoxSection = panel()
    buildTorBoxSettings(torBoxSection)
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
internal fun MainActivity.buildSubtitleSettings(section: LinearLayout) {
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

internal fun MainActivity.buildTmdbSettings(section: LinearLayout) {
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

internal fun MainActivity.buildPlayerSettings(section: LinearLayout) {
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
        option("Auto-play", ps.autoPlay,
            "Start a source without choosing it yourself. \u201cBest quality\u201d picks the highest " +
                "resolution, then Dolby Vision or HDR; both skip links still to be downloaded and cam copies. " +
                "Press any button on the sources page to choose yourself instead.") {
            choose("Auto-play", StreamPick.modes, StreamPick.modes.indexOf(ps.autoPlay).coerceAtLeast(0)) {
                ps.autoPlay = StreamPick.modes[it]
                render("Auto-play")
            }
        }
        if (ps.autoPlay != StreamPick.off) {
            val waits = listOf(5, 10, 15, 20, 30)
            option("Wait for addons", "${ps.autoPlayWaitS} s",
                "How long to wait for slower addons before picking from what's arrived.") {
                choose("Wait for addons", waits.map { "$it seconds" }, waits.indexOf(ps.autoPlayWaitS).coerceAtLeast(0)) {
                    ps.autoPlayWaitS = waits[it]
                    render("Wait for addons")
                }
            }
            val sizes = listOf(0, 5, 10, 20, 40)
            option("Largest file", if (ps.autoPlayMaxGb == 0) "Any size" else "${ps.autoPlayMaxGb} GB",
                "Skip bigger files when auto-playing (huge remuxes need a fast connection).") {
                choose("Largest file", sizes.map { if (it == 0) "Any size" else "Up to $it GB" },
                    sizes.indexOf(ps.autoPlayMaxGb).coerceAtLeast(0)) {
                    ps.autoPlayMaxGb = sizes[it]
                    render("Largest file")
                }
            }
            val names = listOf("") + StremioAddons.addonUrls.mapNotNull { url ->
                try { StremioAddons.manifest(url).optString("name").takeIf { it.isNotBlank() } } catch (_: Exception) { null }
            }.distinct()
            option("Auto-play from", ps.autoPlayAddon.ifBlank { "All addons" },
                "Only auto-play this addon's streams.") {
                choose("Auto-play from", names.map { it.ifBlank { "All addons" } }, names.indexOf(ps.autoPlayAddon).coerceAtLeast(0)) {
                    ps.autoPlayAddon = names[it]
                    render("Auto-play from")
                }
            }
        }
        val hours = listOf(0, 1, 6, 24)
        option("Reuse last source", if (ps.reuseLinkHours == 0) "Off" else "For ${ps.reuseLinkHours} h",
            "Play the same source again when you go back to something within this time, " +
                "instead of searching. Back from the player shows all sources.") {
            choose("Reuse last source", hours.map { if (it == 0) "Off" else "For $it hour" + if (it == 1) "" else "s" },
                hours.indexOf(ps.reuseLinkHours).coerceAtLeast(0)) {
                ps.reuseLinkHours = hours[it]
                render("Reuse last source")
            }
        }
        toggle("Next episode button", ps.nextEpisodeButton,
            "During the credits of an episode (or its last 45 seconds), offer the next episode; " +
                "OK plays it using the same source when possible.") { ps.nextEpisodeButton = it }
        toggle("Autoplay next episode", ps.autoplayNext,
            "Start the next episode automatically when one ends.") { ps.autoplayNext = it }
        val installed = installedExternalPlayers()
        option("Play with", ps.playWith,
            "Orbit's own player, or another player app on this TV" +
                (if (installed.isEmpty()) " (install Just Player, VLC or MX Player to choose one)" else "") +
                ". Where you stop in another player still saves to Continue watching and Trakt.") {
            val choices = listOf("Orbit", "Ask each time") + installed.map { it.name }
            choose("Play with", choices, choices.indexOf(ps.playWith).coerceAtLeast(0)) {
                ps.playWith = choices[it]
                render("Play with")
            }
        }
        toggle("Tunnelled playback", ps.tunneling,
            "Let the TV's hardware keep sound and picture together. Can help lip sync and " +
                "smoothness on some TVs; turn it off again if videos stutter or won't start.") { ps.tunneling = it }
        toggle("Match frame rate", ps.matchFrameRate,
            "Switch the TV to the video's frame rate (e.g. 24 Hz for films) for smoother motion. " +
                "The screen goes dark for a moment while the TV switches.") { ps.matchFrameRate = it }
        focusLabel?.let { label -> section.findViewWithTag<View>(label)?.requestFocus() }
    }
    render()
}

internal fun MainActivity.buildTraktSettings(section: LinearLayout) {
    fun render(focus: Boolean = false) {
        section.removeAllViews()
        section.addView(Ui.text(this, Ui.Type.Headline, "Trakt"))
        val user = trakt.user
        section.addView(Ui.text(this, Ui.Type.Body,
            if (trakt.signedIn) "Signed in as $user. What you watch in Orbit is added to your Trakt history, " +
                "your Trakt watchlist is part of My List, and titles paused elsewhere appear in Continue watching."
            else "Sign in to keep your Trakt history up to date as you watch, and to bring your " +
                "Trakt watchlist and paused titles into Orbit.", Ui.textSecondary).apply { setPadding(0, 12, 0, 24) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        section.addView(buttons)
        fun button(label: String, onClick: () -> Unit) = makePicker(onClick).apply { text = label }.also {
            buttons.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 })
        }
        val first = if (trakt.signedIn) {
            button("Sync now") {
                android.widget.Toast.makeText(this, "Syncing with Trakt...", android.widget.Toast.LENGTH_SHORT).show()
                syncLibrary()
            }.also {
                button("Sign out") {
                    trakt.signOut()
                    render(true)
                }
            }
        } else if (!trakt.available) {
            null
        } else button("Sign in to Trakt") { showTraktSignIn { render(true) } }
        if (focus) first?.requestFocus()
    }
    render()
}

// Trakt's sign-in for TVs: a code entered at trakt.tv/activate on a phone or computer.
internal fun MainActivity.showTraktSignIn(onDone: () -> Unit) {
    lateinit var status: TextView
    lateinit var codeView: TextView
    val qrView = ImageView(this)
    var open = true
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(48, 24, 48, 8)
        addView(qrView, LinearLayout.LayoutParams(360, 360))
        codeView = TextView(context).apply {
            text = "…"
            textSize = 40f
            letterSpacing = 0.15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 6)
        }
        addView(codeView)
        status = TextView(context).apply {
            text = "Getting a code..."
            textSize = 16f
            setTextColor(Color.rgb(195, 202, 220))
            gravity = Gravity.CENTER
        }
        addView(status)
    }
    val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Sign in to Trakt")
        .setView(content)
        .setNegativeButton("Cancel", null)
        .setOnDismissListener { open = false }
        .show()
    thread {
        val main = Handler(Looper.getMainLooper())
        try {
            val code = trakt.startSignIn()
            val size = 360
            val qr = Ui.qrCode(code.url, size)
            main.post {
                qrView.setImageBitmap(qr)
                codeView.text = code.userCode
                status.text = "On your phone or computer, go to ${code.url.removePrefix("https://")} " +
                    "(or scan the code) and enter the code above."
            }
            val until = System.currentTimeMillis() + code.expiresInS * 1000L
            var wait = code.intervalS * 1000L
            while (open && System.currentTimeMillis() < until) {
                Thread.sleep(wait)
                if (!open) return@thread
                val done = try {
                    trakt.finishSignIn(code)
                } catch (e: java.io.IOException) {
                    wait += 1000 // offline for a moment: keep trying, a little slower
                    false
                }
                if (done) {
                    main.post {
                        dialog.dismiss()
                        android.widget.Toast.makeText(this, "Signed in to Trakt as ${trakt.user}", android.widget.Toast.LENGTH_LONG).show()
                        onDone()
                        syncLibrary()
                    }
                    return@thread
                }
            }
            if (open) main.post { status.text = "The code expired. Close this and try again." }
        } catch (e: Exception) {
            main.post { status.text = e.message ?: "Couldn't reach Trakt. Check the TV's internet connection." }
        }
    }
}

// Email and password are sent to Stremio once; only the returned session key is saved.
internal fun MainActivity.showStremioLogin(onDone: (String?) -> Unit) {
    val openedOn = currentScreen
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
                    if (currentScreen == openedOn) onDone(message)
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

// TorBox: sign in (a code, or an API key sent from a phone), then cached torrent links and
// TorBox's own search join the sources page as instant links.
internal fun MainActivity.buildTorBoxSettings(section: LinearLayout) {
    fun render(focus: Boolean = false) {
        section.removeAllViews()
        section.addView(Ui.text(this, Ui.Type.Headline, "TorBox"))
        val box = torBox
        section.addView(Ui.text(this, Ui.Type.Body,
            if (box.signedIn) "Signed in${box.account?.let { " as $it" }.orEmpty()}. " +
                "Torrent links from your addons that TorBox already has are listed as \u201c\u26a1 TorBox\u201d " +
                "and play straight away, and TorBox's own search adds more (when its search service is online)."
            else "Sign in to play torrent links that are already cached on TorBox, straight away, " +
                "and to find more with TorBox's own search.", Ui.textSecondary).apply { setPadding(0, 12, 0, 24) })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        section.addView(buttons)
        fun button(label: String, onClick: () -> Unit) = makePicker(onClick).apply { text = label }.also {
            buttons.addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 18 })
        }
        val first = if (box.signedIn) {
            button("Instant links:  " + if (box.enabled) "On" else "Off") {
                box.enabled = !box.enabled
                render(true)
            }.also {
                button("Sign out") {
                    box.setKey(null)
                    render(true)
                }
            }
        } else {
            button("Sign in with a code") { showTorBoxSignIn { render(true) } }.also {
                button("Add API key from phone") {
                    showPhoneLink({ render(true) }, "Add your TorBox API key",
                        "Scan with your phone, paste your TorBox API key (torbox.app \u2192 Settings) and tap Save.",
                        "Paste your TorBox API key. It's kept on this TV only.", "TorBox API key", "Save") { key ->
                        box.setKey(key)
                        box.checkAccount()?.let { "Signed in to TorBox as $it." }
                            ?: "That key didn't work. Check it on torbox.app and try again.".also { box.setKey(null) }
                    }
                }
            }
        }
        if (focus) first.requestFocus()
    }
    render()
    // Keep the account line current (plan changes, expired keys).
    if (torBox.signedIn) thread {
        torBox.checkAccount()
        Handler(Looper.getMainLooper()).post { if (currentScreen == "settings") render() }
    }
}

// TorBox's sign-in for TVs: a code entered at torbox.app on a phone or computer.
internal fun MainActivity.showTorBoxSignIn(onDone: () -> Unit) {
    lateinit var status: TextView
    lateinit var codeView: TextView
    val qrView = ImageView(this)
    var open = true
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(48, 24, 48, 8)
        addView(qrView, LinearLayout.LayoutParams(360, 360))
        codeView = TextView(context).apply {
            text = "…"
            textSize = 40f
            letterSpacing = 0.15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 6)
        }
        addView(codeView)
        status = TextView(context).apply {
            text = "Getting a code..."
            textSize = 16f
            setTextColor(Color.rgb(195, 202, 220))
            gravity = Gravity.CENTER
        }
        addView(status)
    }
    val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Sign in to TorBox")
        .setView(content)
        .setNegativeButton("Cancel", null)
        .setOnDismissListener { open = false }
        .show()
    thread {
        val main = Handler(Looper.getMainLooper())
        try {
            val code = torBox.startSignIn()
            val qr = Ui.qrCode(code.url, 360)
            main.post {
                qrView.setImageBitmap(qr)
                codeView.text = code.code
                status.text = "Scan the code, or go to torbox.app/oauth/device on your phone and enter the number above."
            }
            val until = System.currentTimeMillis() + code.expiresInS * 1000L
            while (open && System.currentTimeMillis() < until) {
                Thread.sleep(code.intervalS * 1000L)
                if (!open) return@thread
                val done = try { torBox.finishSignIn(code) } catch (e: java.io.IOException) { false }
                if (done) {
                    val account = torBox.checkAccount()
                    main.post {
                        dialog.dismiss()
                        android.widget.Toast.makeText(this, "Signed in to TorBox" + (account?.let { " as $it" } ?: ""),
                            android.widget.Toast.LENGTH_LONG).show()
                        onDone()
                    }
                    return@thread
                }
            }
            if (open) main.post { status.text = "The code expired. Close this and try again." }
        } catch (e: Exception) {
            main.post { status.text = e.message ?: "Couldn't reach TorBox. Check the TV's internet connection." }
        }
    }
}
