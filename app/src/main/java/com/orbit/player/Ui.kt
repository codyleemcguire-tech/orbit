package com.orbit.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// Orbit's design system: colours, type scale, spacing and the shared building blocks.
// "Cinema dark": near-black surfaces, one blue accent, Inter throughout, white focus rings.
object Ui {
    // Surfaces, darkest to lightest.
    val background = Color.rgb(9, 11, 16)
    val surface = Color.rgb(19, 23, 32)
    val surfaceRaised = Color.rgb(29, 35, 48)
    val outline = Color.rgb(44, 52, 70)

    // Text.
    val textPrimary = Color.rgb(242, 244, 248)
    val textSecondary = Color.rgb(168, 176, 194)
    val textMuted = Color.rgb(112, 120, 140)

    val accent = Color.rgb(108, 140, 255)
    val accentSoft = Color.rgb(32, 44, 86)
    val gold = Color.rgb(245, 197, 66)
    val danger = Color.rgb(255, 150, 130)
    val focusRing = Color.WHITE

    // Side margin every screen lines up to (the TV's safe area is ~5%).
    const val gutter = 64
    const val radius = 14f
    const val pillRadius = 100f

    private var fonts: Map<String, Typeface> = emptyMap()

    fun init(context: Context) {
        if (fonts.isNotEmpty()) return
        fun load(id: Int, fallback: Int) = try {
            context.resources.getFont(id)
        } catch (_: Exception) {
            Typeface.create("sans-serif", fallback)
        }
        fonts = mapOf(
            "regular" to load(R.font.inter_regular, Typeface.NORMAL),
            "medium" to load(R.font.inter_medium, Typeface.NORMAL),
            "semibold" to load(R.font.inter_semibold, Typeface.BOLD),
            "bold" to load(R.font.inter_bold, Typeface.BOLD)
        )
    }

    fun font(weight: String): Typeface = fonts[weight] ?: Typeface.DEFAULT

    // Type scale (sp). Names follow their role, not their size.
    enum class Type(val size: Float, val weight: String, val tracking: Float = 0f) {
        // This stick draws 2 px per sp, so these are about twice as tall on screen.
        Display(38f, "bold", -0.02f),
        Title(28f, "bold", -0.01f),
        Headline(19f, "semibold"),
        Body(15f, "regular"),
        BodyStrong(15f, "semibold"),
        Label(14f, "medium"),
        Caption(12f, "medium", 0.02f),
        Overline(12f, "semibold", 0.16f)
    }

    fun text(
        context: Context,
        type: Type,
        value: CharSequence = "",
        color: Int = textPrimary,
        maxLines: Int = Int.MAX_VALUE
    ) = TextView(context).apply {
        text = value
        textSize = type.size
        typeface = font(type.weight)
        letterSpacing = type.tracking
        setTextColor(color)
        includeFontPadding = false
        if (maxLines != Int.MAX_VALUE) {
            this.maxLines = maxLines
            ellipsize = TextUtils.TruncateAt.END
        }
        if (type == Type.Body) setLineSpacing(6f, 1f)
    }

    fun rounded(color: Int, radius: Float = Ui.radius, stroke: Int = 0, strokeColor: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }

    // Buttons: primary (white, like Apple TV's), secondary (translucent). Focus brightens,
    // rings and slightly enlarges them.
    fun button(
        context: Context,
        label: String,
        primary: Boolean = false,
        onClick: () -> Unit
    ) = TextView(context).apply {
        text = label
        textSize = 15f
        typeface = font("semibold")
        gravity = Gravity.CENTER
        includeFontPadding = false
        isFocusable = true
        isClickable = true
        setPadding(34, 18, 38, 18)
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        setOnClickListener { onClick() }
        val paint = { focused: Boolean ->
            setTextColor(if (primary || focused) Ui.background else textPrimary)
            background = rounded(
                when {
                    focused -> Color.WHITE
                    primary -> Color.rgb(226, 230, 238)
                    else -> Color.argb(46, 255, 255, 255)
                },
                12f
            )
            animate().scaleX(if (focused) 1.06f else 1f).scaleY(if (focused) 1.06f else 1f)
                .setDuration(140).start()
        }
        paint(false)
        setOnFocusChangeListener { _, focused -> paint(focused) }
    }

    // Small pill used for filters, tabs and settings actions. selected = filled accent.
    fun pill(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        typeface = font("semibold")
        includeFontPadding = false
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        setPadding(28, 14, 28, 14)
        if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        setOnClickListener { onClick() }
        paintPill(this, selected = false)
        setOnFocusChangeListener { v, _ -> paintPill(v as TextView, v.isSelected) }
    }

    fun paintPill(pill: TextView, selected: Boolean) {
        pill.isSelected = selected
        val focused = pill.isFocused
        pill.setTextColor(if (focused) Ui.background else textPrimary)
        pill.background = rounded(
            when {
                focused -> Color.WHITE
                selected -> accentSoft
                else -> surface
            },
            pillRadius,
            stroke = if (selected && !focused) 3 else 2,
            strokeColor = if (selected && !focused) accent else outline
        )
    }

    // Metadata chip: "2026", "2h 25m", "★ 7.8", "4K", "HDR".
    fun chip(context: Context, label: String, tone: Int = textSecondary, filled: Boolean = false) =
        TextView(context).apply {
            text = label
            textSize = 13f
            typeface = font("semibold")
            includeFontPadding = false
            setTextColor(if (filled) Ui.background else tone)
            setPadding(14, 7, 14, 7)
            background = if (filled) rounded(tone, 8f)
            else rounded(Color.argb(28, 255, 255, 255), 8f, 2, Color.argb(40, 255, 255, 255))
        }

    fun chipRow(context: Context, chips: List<View>) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        chips.forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 }) }
    }

    // Section heading above a row of posters or a list.
    fun sectionTitle(context: Context, label: String) =
        text(context, Type.Headline, label).apply { setPadding(0, 32, 0, 0) }

    // Full-width backdrop with scrims fading it into the page on the left and bottom,
    // so text laid over it stays readable. Returns the frame and its image view.
    // The scrims are painted into the backdrop bitmap when it loads (see bakeScrims), so
    // the GPU draws one layer per frame instead of three.
    fun backdrop(context: Context, height: Int): Pair<FrameLayout, ImageView> {
        val frame = FrameLayout(context)
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        frame.layoutParams = LinearLayout.LayoutParams(-1, height)
        return frame to image
    }

    // Darkens a backdrop toward the page colour on the left (behind text) and at the
    // bottom (where it meets the page), matching the old overlay gradients.
    fun bakeScrims(source: android.graphics.Bitmap): android.graphics.Bitmap {
        val out = source.copy(android.graphics.Bitmap.Config.RGB_565, true)
        val canvas = android.graphics.Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()
        val paint = android.graphics.Paint()
        paint.shader = android.graphics.LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(background, Color.argb(225, 9, 11, 16), Color.argb(60, 9, 11, 16), 0),
            null, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = android.graphics.LinearGradient(
            0f, h, 0f, h / 2f,
            intArrayOf(background, Color.argb(120, 9, 11, 16), 0),
            null, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, h / 2f, w, h, paint)
        return out
    }

    @Suppress("unused")
    private fun legacyBackdropOverlays(context: Context, frame: FrameLayout, height: Int) {
        frame.addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Ui.background, Color.argb(225, 9, 11, 16), Color.argb(60, 9, 11, 16), 0)
            )
        }, FrameLayout.LayoutParams(-1, -1))
        frame.addView(View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Ui.background, Color.argb(120, 9, 11, 16), 0)
            )
        }, FrameLayout.LayoutParams(-1, height / 2, Gravity.BOTTOM))
    }

    fun formatRuntime(minutes: Int?): String? =
        minutes?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }

    fun formatRating(rating: Double): String? =
        rating.takeIf { it > 0.0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) }

    // Quality badges read from a stream's name and description, as Stremio users expect.
    fun streamBadges(text: String): List<Pair<String, Int>> {
        val t = text.uppercase()
        val badges = mutableListOf<Pair<String, Int>>()
        when {
            "2160P" in t || Regex("\\b4K\\b").containsMatchIn(t) || " UHD" in t -> badges += "4K" to gold
            "1080P" in t -> badges += "1080p" to textPrimary
            "720P" in t -> badges += "720p" to textSecondary
            "480P" in t || "SDTV" in t || "DVDRIP" in t -> badges += "SD" to textMuted
        }
        if ("DOLBY VISION" in t || Regex("\\b(DV|DOVI)\\b").containsMatchIn(t)) badges += "Dolby Vision" to accent
        if ("HDR" in t) badges += "HDR" to accent
        if ("ATMOS" in t) badges += "Atmos" to textSecondary
        if ("REMUX" in t) badges += "Remux" to textSecondary
        return badges
    }

    // The first size in a stream's text, e.g. "8.8 GB". Season packs list the file's size
    // first and the pack's total after it ("1.34 GiB / 149.84 GiB").
    fun streamSize(text: String): String? =
        Regex("(\\d+(?:\\.\\d+)?)\\s?(GB|GiB|MB|MiB)", RegexOption.IGNORE_CASE).find(text)?.value
}
