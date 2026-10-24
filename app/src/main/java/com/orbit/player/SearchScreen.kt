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

// Search across the addons' catalogs.

internal fun MainActivity.showSearchScreen() {
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
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
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
