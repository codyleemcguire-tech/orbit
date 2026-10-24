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

// Poster cards and rows shared by the screens, with image loading and focus styling.

// posterPath is a TMDB path ("/abc.jpg") or a full URL from an addon. targetWidth > 0
// decodes the image at that width: addons often send 600x900 posters for 190 px cards,
// and drawing those scaled down every frame is what made scrolling stutter.
internal fun MainActivity.loadPosterImage(
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
internal fun MainActivity.roundCorners(
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

internal fun MainActivity.posterPlaceholder() = Ui.rounded(Ui.surface, 14f)

// Thin bar showing how much of a title has been watched; laid over the bottom of a
// poster or episode thumbnail.
internal fun MainActivity.makeProgressBar(width: Int, fraction: Float): FrameLayout =
    FrameLayout(this).apply {
        background = Ui.rounded(Color.argb(150, 0, 0, 0), 6f)
        addView(View(context).apply { background = Ui.rounded(Ui.accent, 6f) },
            FrameLayout.LayoutParams((width * fraction).toInt().coerceAtLeast(10), -1))
    }

// A horizontal row that lets focused posters grow past its edges instead of clipping them.
internal fun MainActivity.makePosterRow(): Pair<HorizontalScrollView, LinearLayout> {
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

internal fun MainActivity.addPosterRow(scroll: HorizontalScrollView, into: LinearLayout = root) {
    // Negative margins line the first poster up with the row heading.
    into.addView(scroll, LinearLayout.LayoutParams(-1, -2).apply {
        leftMargin = -28
        rightMargin = -40
    })
}

internal fun MainActivity.makePosterCard(
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
internal fun MainActivity.createPosterCard(dimRow: Boolean = true, width: Int = 190): PosterCard {
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

internal fun MainActivity.bindPosterCard(
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
internal fun MainActivity.pinRowEnds(row: ViewGroup) {
    for (i in 0 until row.childCount) {
        val child = row.getChildAt(i)
        if (child.id == View.NO_ID) child.id = View.generateViewId()
        child.nextFocusLeftId = if (i == 0) child.id else View.NO_ID
        child.nextFocusRightId = if (i == row.childCount - 1) child.id else View.NO_ID
    }
}

internal fun MainActivity.dimOtherPosters(card: View, focused: Boolean) {
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

// Starts loading covers of folder tiles within about one screen of view. Runs after
// HOME is built and whenever anything on it scrolls.
internal fun MainActivity.runLazyLoads() {
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
