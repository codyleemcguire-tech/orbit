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

// The profile's addons: synced from Stremio or added in Orbit (typed, or sent from a phone).

internal fun MainActivity.savedAddons(): List<StremioAccount.Addon> {
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
internal fun MainActivity.localAddons(): List<StremioAccount.Addon> {
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

internal fun MainActivity.saveLocalAddons(addons: List<StremioAccount.Addon>) {
    val array = org.json.JSONArray()
    addons.forEach { array.put(org.json.JSONObject().put("name", it.name).put("url", it.url)) }
    prefs.edit().putString("local_addons", array.toString()).apply()
    applySavedAddons()
    resetDiscover()
}

internal fun MainActivity.applySavedAddons() {
    val synced =
        if (prefs.contains("stremio_auth_key")) savedAddons().map { it.url } else emptyList()
    // Account addons first (Stremio's order), then Orbit's own; no duplicates.
    StremioAddons.addonUrls = (synced + localAddons().map { it.url }).distinct()
}

// Accepts what people paste from addon sites: stremio:// links, or a URL without
// /manifest.json. Returns null when it can't be a manifest URL.
internal fun MainActivity.normalizeManifestUrl(input: String): String? {
    // Pasted text can carry spaces or line breaks from wrapping.
    var url = input.trim().replace(Regex("\\s+"), "")
    if (url.startsWith("stremio://", ignoreCase = true)) url = "https://" + url.substring(10)
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) url = "https://$url"
    url = url.substringBefore('#').removeSuffix("/")
    // An addon's configure page (".../configure") rather than its manifest.
    url = url.removeSuffix("/configure").removeSuffix("/")
    if (!url.endsWith("/manifest.json")) url += "/manifest.json"
    // Configured addons (Torrentio, Comet, Debridio, …) put their settings in the path,
    // with characters such as | that aren't allowed in a URL as typed; escape those.
    url = url.map { c ->
        if (c in "|{}^`\\<>\"" || c.code > 126) java.net.URLEncoder.encode(c.toString(), "UTF-8") else c.toString()
    }.joinToString("")
    return try {
        val uri = java.net.URI(url)
        url.takeIf { uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.host.contains('.') }
    } catch (_: Exception) {
        null
    }
}

internal fun MainActivity.showAddAddon(onDone: (String?) -> Unit) {
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
internal fun MainActivity.addLocalAddon(input: String): String {
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
internal fun MainActivity.showPhoneLink(
    onDone: (String?) -> Unit,
    title: String = "Add addon from your phone",
    instructions: String = "Scan with your phone's camera, paste the addon URL and tap Add.",
    pageHeading: String? = null,
    pagePlaceholder: String = "https://…/manifest.json",
    pageButton: String = "Add to Orbit",
    handle: (String) -> String = ::addLocalAddon
) {
    val openedOn = currentScreen
    lateinit var status: TextView
    val server = PhoneLinkServer(
        pageHeading ?: "Paste an addon's manifest URL (or stremio:// link). It's added to Orbit on your TV.",
        pagePlaceholder, pageButton
    ) { url ->
        Handler(Looper.getMainLooper()).post { status.text = "Checking..." }
        val message = handle(url)
        Handler(Looper.getMainLooper()).post {
            status.text = message
            if (currentScreen == openedOn) onDone(message)
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
    val qr = Ui.qrCode(address, size)

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

internal fun MainActivity.confirmRemoveAddon(addon: StremioAccount.Addon, onDone: (String?) -> Unit) {
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

internal fun MainActivity.clearStremioSession() {
    prefs.edit()
        .remove("stremio_auth_key")
        .remove("stremio_email")
        .remove("stremio_addons")
        .apply()
    applySavedAddons()
}

// Call off the main thread. Refreshes the addon list from the signed-in Stremio
// account; if Stremio can't be reached, the last synced list stays in use.
internal fun MainActivity.syncStremioAddons(): String {
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
