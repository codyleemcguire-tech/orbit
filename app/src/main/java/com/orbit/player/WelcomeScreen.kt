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

// Launch and first run: the splash, the welcome page and addon setup.

// The logo fades in, holds a moment, and the page fades into what comes next.
internal fun MainActivity.showSplash(then: () -> Unit) {
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

internal fun MainActivity.onboardingPage(): LinearLayout {
    useContentScroll()
    root.removeAllViews()
    root.gravity = Gravity.CENTER
    contentScroll.scrollTo(0, 0)
    return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }.also { root.addView(it, LinearLayout.LayoutParams(1100, -2).apply { gravity = Gravity.CENTER_HORIZONTAL }) }
}

internal fun MainActivity.showWelcome() {
    onboarding = true
    currentScreen = "welcome"
    val page = onboardingPage()
    page.addView(TextView(this).apply {
        text = android.text.SpannableString("orbit.").apply {
            setSpan(android.text.style.ForegroundColorSpan(Ui.accent), 5, 6, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textSize = 64f
        typeface = Ui.font("bold")
        letterSpacing = -0.03f
        includeFontPadding = false
        setTextColor(Ui.textPrimary)
    }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
    page.addView(Ui.text(this, Ui.Type.Display, "Welcome to Orbit").apply {
        gravity = Gravity.CENTER
        setPadding(0, 48, 0, 18)
    }, LinearLayout.LayoutParams(-1, -2))
    page.addView(Ui.text(this, Ui.Type.Body,
        "Films and shows from your Stremio addons, with My List and Continue watching that follow you " +
            "between devices. Setting up takes a minute.", Ui.textSecondary).apply {
        gravity = Gravity.CENTER
        textSize = 19f
    }, LinearLayout.LayoutParams(-1, -2))
    val start = Ui.button(this, "Get started", primary = true) {
        promptText("What should we call you?", "Your name (optional)", "", optional = true) { name ->
            if (name.isNotEmpty()) Profiles.rename(this, profile.id, name)
            showAddonSetup()
        }
    }
    page.addView(start, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 60 })
    page.addView(Ui.pill(this, "I use Orbit on another device") { showReceiveProfile() },
        LinearLayout.LayoutParams(-2, -2).apply { topMargin = 24 })
    start.requestFocus()
}

// Step two: where films, shows and streams come from.
internal fun MainActivity.showAddonSetup() {
    currentScreen = "setup"
    val page = onboardingPage()
    page.addView(Ui.text(this, Ui.Type.Overline, "STEP 2 OF 2", Ui.accent).apply { gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(-1, -2))
    page.addView(Ui.text(this, Ui.Type.Display, "Add your addons").apply {
        gravity = Gravity.CENTER
        setPadding(0, 10, 0, 12)
    }, LinearLayout.LayoutParams(-1, -2))
    page.addView(Ui.text(this, Ui.Type.Body,
        "Orbit finds films, shows, streams and subtitles through Stremio addons. Choose one or more; " +
            "you can change them any time in Settings.", Ui.textSecondary).apply {
        gravity = Gravity.CENTER
        textSize = 17f
        setPadding(0, 0, 0, 26)
    }, LinearLayout.LayoutParams(-1, -2))

    val status = Ui.text(this, Ui.Type.BodyStrong, "", Ui.textPrimary).apply {
        gravity = Gravity.CENTER
        setPadding(0, 10, 0, 0)
    }
    fun installed(): String {
        val names = (if (prefs.contains("stremio_auth_key")) savedAddons() else emptyList()) + localAddons()
        return if (names.isEmpty()) "No addons yet."
            else "✓  ${names.size} addon${if (names.size == 1) "" else "s"}: " + names.distinctBy { it.url }.joinToString(", ") { it.name }
    }
    val refresh: (String?) -> Unit = { note ->
        applySavedAddons()
        status.text = listOfNotNull(note?.takeIf { it.isNotBlank() && !it.endsWith("...") }, installed()).joinToString("\n")
    }

    // One card per way of adding addons: a heading and a line on what it brings.
    fun option(title: String, detail: String, onClick: () -> Unit): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 22)
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            val heading = Ui.text(context, Ui.Type.Headline, title).apply { textSize = 22f }
            val line = Ui.text(context, Ui.Type.Body, detail, Ui.textSecondary)
            addView(heading)
            addView(line.apply { setPadding(0, 8, 0, 0) })
            val paint = { focused: Boolean ->
                background = Ui.rounded(if (focused) Ui.surfaceRaised else Ui.surface, 18f,
                    stroke = if (focused) 4 else 0, strokeColor = Color.WHITE)
            }
            paint(false)
            setOnFocusChangeListener { _, focused -> paint(focused) }
            setOnClickListener { onClick() }
        }
        page.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 })
        return card
    }
    val first = option("Sign in to Stremio",
        "Brings the addons, library and watch progress from your Stremio account.") {
        showStremioLogin { note ->
            refresh(note)
            if (library.signedIn) syncLibrary()
        }
    }
    option("Add from your phone",
        "Scan a QR code and paste an addon's link on your phone.") { showPhoneLink(refresh) }
    option("Start with the basics",
        "Cinemeta for catalogs and OpenSubtitles for subtitles. Add a source of streams yourself.") {
        status.text = "Adding..."
        thread {
            // Only problems are worth a note; the addon list below shows what was added.
            val problems = starterAddons.map { addLocalAddon(it) }.filter { !it.startsWith("Added") && !it.contains("already") }
            Handler(Looper.getMainLooper()).post {
                if (currentScreen == "setup") refresh(problems.firstOrNull())
            }
        }
    }
    page.addView(status, LinearLayout.LayoutParams(-1, -2))
    val finish = Ui.button(this, "Finish", primary = true) {
        Profiles.setOnboarded(this)
        onboarding = false
        Profiles.chosenThisLaunch = true
        // Opens Home fresh, with the profile's new name in the header.
        recreate()
    }
    page.addView(finish, LinearLayout.LayoutParams(-2, -2).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        topMargin = 22
    })
    refresh(null)
    first.requestFocus()
}
