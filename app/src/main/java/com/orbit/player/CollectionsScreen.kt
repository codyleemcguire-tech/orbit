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

// Nuvio collections: importing them, their Home rows and folder pages.

internal fun MainActivity.savedCollections(): List<NuvioCollections.Collection> = try {
    prefs.getString("nuvio_collections", null)?.let { NuvioCollections.parse(it) }.orEmpty()
} catch (_: Exception) {
    emptyList()
}

// Imports a Nuvio export (the JSON itself, or a link to it), replacing collections with
// the same id. Call off the main thread; returns the message to show.
internal fun MainActivity.importCollections(input: String): String {
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

internal fun MainActivity.removeCollection(id: String) {
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
internal fun MainActivity.importNuvioExportFile(): String {
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
internal fun MainActivity.fillCollectionRow(index: Int, collection: NuvioCollections.Collection) {
    val items = collection.folders.map { folder ->
        TmdbApi.CatalogueItem(
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

internal fun MainActivity.makeCollectionTile(rowIndex: Int, item: TmdbApi.CatalogueItem): View {
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

internal fun MainActivity.makeFolderTile(folder: NuvioCollections.Folder, title: String, onClick: () -> Unit): LinearLayout {
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

internal fun MainActivity.showFolderScreen(collection: NuvioCollections.Collection, folder: NuvioCollections.Folder) {
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
internal fun MainActivity.updateFolder() {
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

// Fetches the next page of the sources behind the current tab (every source on All).
internal fun MainActivity.loadMoreFolder() {
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
internal fun MainActivity.refreshFolderLabels() {
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
