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

// Profiles: Who's watching, switching, and copying a profile between devices.

// Round avatar with the profile's initial; a white ring when focused.
internal fun MainActivity.makeAvatar(p: Profiles.Profile, size: Int): TextView = TextView(this).apply {
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
internal fun MainActivity.showProfilePicker() {
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
internal fun MainActivity.switchProfile(id: String) {
    Profiles.setActive(this, id)
    Profiles.chosenThisLaunch = true
    StremioAddons.addonUrls = emptyList()
    recreate()
}

// Profiles section of Settings. Returns its first button, which Settings focuses first.
internal fun MainActivity.buildProfilesSettings(section: LinearLayout): View {
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

internal fun MainActivity.leaveTransfer() {
    transferServer?.stop()
    transferServer = null
    if (onboarding) showWelcome() else showSettingsScreen()
}

internal fun MainActivity.transferPage(title: String): LinearLayout {
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
internal fun MainActivity.showSendProfile() {
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
internal fun MainActivity.showReceiveProfile() {
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
                    Profiles.import(this, org.json.JSONObject(profileJson), into = if (onboarding) profile.id else null)
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
                    if (onboarding) {
                        // First run: the received profile is this device's profile now.
                        Profiles.setOnboarded(this)
                        switchProfile(received.id)
                        return@post
                    }
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
