package com.orbit.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer

// The player's on-screen controls, after NuvioTV's player (NuvioMedia/NuvioTV, GPL-3.0):
// title, episode and quality badges over a dark fade; a progress bar that scrubs with
// Left/Right; a row of round buttons; the time and when the video ends. With the controls
// hidden, OK pauses and Left/Right scrub (faster the longer they're held), showing just
// the bar. They hide after 3 s of playing untouched.
class PlayerControls(private val context: Context, private val host: Host) {

    interface Host {
        val player: ExoPlayer?
        fun playPause()
        fun nextEpisode()
        fun subtitles()
        fun audio()
        fun sources()
        fun episodes()
        fun speed()
        fun aspectRatio()
        fun streamInfo()
        fun openElsewhere()
        val hasNextEpisode: Boolean
        val hasEpisodes: Boolean
        // Gives focus back to the video when the controls hide.
        fun focusVideo()
        fun onShown() {}
    }

    private val main = Handler(Looper.getMainLooper())
    val view = FrameLayout(context).apply {
        visibility = View.GONE
        clipChildren = false
    }
    private val topFade = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(180, 0, 0, 0), Color.TRANSPARENT))
    }
    private val bottomFade = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Color.argb(215, 0, 0, 0), Color.argb(120, 0, 0, 0), Color.TRANSPARENT))
    }
    private val clock = Ui.text(context, Ui.Type.Headline, "", Color.argb(240, 255, 255, 255))
    private val info = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val title = Ui.text(context, Ui.Type.Title, "", Color.WHITE, maxLines = 1)
    private val episode = Ui.text(context, Ui.Type.Headline, "", Color.argb(230, 255, 255, 255), maxLines = 1)
    private val badges = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val year = Ui.text(context, Ui.Type.Body, "", Color.argb(175, 255, 255, 255))
    val bar = SeekBar(context)
    private val buttons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val more = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
    }
    private val time = Ui.text(context, Ui.Type.Label, "", Color.argb(235, 255, 255, 255)).apply { gravity = Gravity.END }
    private val ends = Ui.text(context, Ui.Type.Caption, "", Color.argb(170, 255, 255, 255)).apply { gravity = Gravity.END }
    private val buttonRow: LinearLayout
    private lateinit var playButton: ImageView
    private lateinit var nextButton: ImageView
    private lateinit var episodesButton: ImageView
    private lateinit var moreButton: ImageView

    // Showing everything, or just the bar and time while scrubbing with the controls hidden.
    var isShowing = false
        private set
    private var scrubOnly = false

    // A scrub not yet applied: Left/Right move this, and it's sought when they're let go.
    private var pendingSeek: Long? = null

    init {
        view.addView(topFade, FrameLayout.LayoutParams(-1, 260, Gravity.TOP))
        view.addView(bottomFade, FrameLayout.LayoutParams(-1, 560, Gravity.BOTTOM))
        view.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = 48; rightMargin = Ui.gutter
        })

        info.addView(title)
        info.addView(episode, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10 })
        info.addView(badges, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 14 })
        info.addView(year, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 10 })

        fun button(icon: Int, label: String, onClick: () -> Unit) = ImageView(context).apply {
            setImageResource(icon)
            contentDescription = label
            isFocusable = true
            isClickable = true
            setPadding(22, 22, 22, 22)
            if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            val paint = { focused: Boolean ->
                setColorFilter(if (focused) Color.BLACK else Color.WHITE)
                background = if (focused) Ui.rounded(Color.WHITE, 100f) else null
            }
            paint(false)
            setOnFocusChangeListener { _, focused ->
                paint(focused)
                if (focused) keepShowing()
            }
            setOnClickListener { onClick() }
        }
        val size = 92
        fun add(row: LinearLayout, v: View) = row.addView(v, LinearLayout.LayoutParams(size, size).apply { rightMargin = 12 })
        playButton = button(R.drawable.ic_player_pause, "Pause") { host.playPause(); refresh() }
        nextButton = button(R.drawable.ic_player_next, "Next episode") { host.nextEpisode() }
        episodesButton = button(R.drawable.ic_player_episodes, "Episodes") { host.episodes() }
        moreButton = button(R.drawable.ic_player_more, "More") { toggleMore() }
        add(buttons, playButton)
        add(buttons, nextButton)
        add(buttons, button(R.drawable.ic_player_subtitles, "Subtitles") { host.subtitles() })
        add(buttons, button(R.drawable.ic_player_audio_filled, "Audio") { host.audio() })
        add(buttons, button(R.drawable.ic_player_source, "Sources") { host.sources() })
        add(buttons, episodesButton)
        add(more, button(R.drawable.ic_player_speed, "Playback speed") { host.speed() })
        add(more, button(R.drawable.ic_player_aspect_ratio, "Aspect ratio") { host.aspectRatio() })
        add(more, button(R.drawable.ic_player_info, "Stream info") { host.streamInfo() })
        add(more, button(R.drawable.ic_player_external, "Open in another app") { host.openElsewhere() })
        buttons.addView(more)
        add(buttons, moreButton)

        val timeColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(time)
            addView(ends, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 6 })
        }
        buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(buttons, LinearLayout.LayoutParams(0, -2, 1f))
            addView(timeColumn)
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.gutter, 0, Ui.gutter, 44)
            addView(info)
            addView(bar, LinearLayout.LayoutParams(-1, 44).apply { topMargin = 26 })
            addView(buttonRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })
        }
        view.addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        bar.setOnFocusChangeListener { _, focused ->
            bar.focused = focused
            bar.invalidate()
            if (focused) keepShowing()
        }
        bar.setOnKeyListener { _, _, event -> barKey(event) }
        // Up from the buttons goes to the bar; Down from the buttons, or Up from the bar, hides.
        buttonRow.isFocusable = false
    }

    fun setInfo(name: String, episodeLine: String, badgeList: List<Pair<String, Int>>, yearText: String) {
        title.text = name
        episode.text = episodeLine
        episode.visibility = if (episodeLine.isBlank()) View.GONE else View.VISIBLE
        badges.removeAllViews()
        badgeList.forEach { (label, tone) ->
            badges.addView(Ui.chip(context, label, tone), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 12 })
        }
        badges.visibility = if (badgeList.isEmpty()) View.GONE else View.VISIBLE
        year.text = yearText
        year.visibility = if (yearText.isBlank()) View.GONE else View.VISIBLE
    }

    // ---- Showing and hiding ----

    private val hideLater = Runnable {
        val p = host.player
        if (p != null && p.playWhenReady) hide()
    }

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (view.visibility == View.VISIBLE) main.postDelayed(this, 500)
        }
    }

    fun show(focus: View? = playButton) {
        scrubOnly = false
        info.visibility = View.VISIBLE
        buttons.visibility = View.VISIBLE
        topFade.visibility = View.VISIBLE
        clock.visibility = View.VISIBLE
        nextButton.visibility = if (host.hasNextEpisode) View.VISIBLE else View.GONE
        episodesButton.visibility = if (host.hasEpisodes) View.VISIBLE else View.GONE
        if (!isShowing) {
            more.visibility = View.GONE
            moreButton.setImageResource(R.drawable.ic_player_more)
        }
        isShowing = true
        appear()
        (focus ?: playButton).requestFocus()
        keepShowing()
        host.onShown()
    }

    // Just the bar and the time, for scrubbing with the controls hidden.
    private fun showScrubber() {
        if (isShowing) return
        scrubOnly = true
        info.visibility = View.GONE
        buttons.visibility = View.INVISIBLE
        topFade.visibility = View.GONE
        clock.visibility = View.GONE
        appear()
    }

    private fun appear() {
        main.removeCallbacks(ticker)
        refresh()
        if (view.visibility != View.VISIBLE) {
            view.alpha = 0f
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(160).start()
        }
        main.post(ticker)
    }

    fun hide() {
        main.removeCallbacks(hideLater)
        if (view.visibility != View.VISIBLE) return
        isShowing = false
        scrubOnly = false
        view.animate().alpha(0f).setDuration(200).withEndAction {
            if (!isShowing && !scrubOnly) view.visibility = View.GONE
        }.start()
        // Focus back on the video, so keys come to the player again.
        host.focusVideo()
    }

    // Restarts the 3 s countdown to hiding (Nuvio's timing).
    fun keepShowing() {
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, 3000)
    }

    fun release() {
        main.removeCallbacks(hideLater)
        main.removeCallbacks(ticker)
        main.removeCallbacks(commitSeek)
        pendingSeek = null
        view.visibility = View.GONE
        isShowing = false
        scrubOnly = false
    }

    private fun toggleMore() {
        val open = more.visibility != View.VISIBLE
        more.visibility = if (open) View.VISIBLE else View.GONE
        moreButton.setImageResource(if (open) R.drawable.ic_player_close else R.drawable.ic_player_more)
        keepShowing()
    }

    // ---- Progress, time ----

    fun refresh() {
        val p = host.player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: 0L
        val position = pendingSeek ?: p.currentPosition
        bar.duration = duration
        bar.position = position
        bar.buffered = p.bufferedPosition
        bar.previewing = pendingSeek != null
        bar.invalidate()
        time.text = if (duration > 0) "${WatchProgress.format(position)} / ${WatchProgress.format(duration)}"
        else WatchProgress.format(position)
        val fmt = android.text.format.DateFormat.getTimeFormat(context)
        val left = ((duration - position) / p.playbackParameters.speed).toLong()
        ends.text = if (duration > 0 && left > 0) "Ends at ${fmt.format(java.util.Date(System.currentTimeMillis() + left))}" else ""
        clock.text = fmt.format(java.util.Date())
        val playing = p.playWhenReady
        playButton.setImageResource(if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        playButton.contentDescription = if (playing) "Pause" else "Play"
    }

    // ---- Keys ----

    // Keys with the controls hidden (MainActivity sends them here first). True when used.
    fun handleHiddenKey(event: KeyEvent): Boolean {
        if (isShowing) return false
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                scrubBy(event)
                showScrubber()
            } else if (event.action == KeyEvent.ACTION_UP) {
                main.removeCallbacks(commitSeek)
                main.postDelayed(commitSeek, 450)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                show()
                true
            }
            else -> false
        }
    }

    // Left/Right on the focused bar scrub too; OK there pauses.
    private fun barKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            keepShowing()
            if (event.action == KeyEvent.ACTION_DOWN) scrubBy(event)
            else if (event.action == KeyEvent.ACTION_UP) {
                main.removeCallbacks(commitSeek)
                main.postDelayed(commitSeek, 450)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        return when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                hide()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                playButton.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                host.playPause()
                refresh()
                true
            }
            else -> false
        }
    }

    // Keys while the controls are showing, before the focused button sees them: Up from
    // the buttons goes to the bar, Down from them hides.
    fun handleShownKey(event: KeyEvent): Boolean {
        if (!isShowing || event.action != KeyEvent.ACTION_DOWN) return false
        keepShowing()
        val focused = view.findFocus()
        val onButtons = focused != null && focused !== bar
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (onButtons) {
                bar.requestFocus()
                true
            } else false
            KeyEvent.KEYCODE_DPAD_DOWN -> if (onButtons) {
                hide()
                true
            } else false
            else -> false
        }
    }

    // NuvioTV's scrub steps: 10 s a press, 20 s, 30 s then 60 s as the key is held longer.
    private fun scrubBy(event: KeyEvent) {
        val p = host.player ?: return
        main.removeCallbacks(commitSeek)
        val step = when {
            event.repeatCount >= 15 -> 60_000L
            event.repeatCount >= 8 -> 30_000L
            event.repeatCount >= 3 -> 20_000L
            else -> 10_000L
        }
        val forward = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val from = pendingSeek ?: p.currentPosition
        pendingSeek = (from + if (forward) step else -step).coerceIn(0L, (duration - 1000).coerceAtLeast(0L))
        refresh()
    }

    private val commitSeek = Runnable {
        val target = pendingSeek ?: return@Runnable
        val p = host.player
        pendingSeek = null
        if (p != null) {
            // Towards the direction moved, landing on a keyframe: quick on big files.
            val back = target < p.currentPosition
            p.setSeekParameters(if (back) androidx.media3.exoplayer.SeekParameters.PREVIOUS_SYNC
                else androidx.media3.exoplayer.SeekParameters.NEXT_SYNC)
            p.seekTo(target)
            p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        }
        refresh()
        if (scrubOnly) main.postDelayed({ if (scrubOnly && pendingSeek == null) hide() }, 1200)
        else keepShowing()
    }

    // ---- The bar ----

    // Played (accent), buffered (light) and the rest of the video; a knob while focused.
    class SeekBar(context: Context) : View(context) {
        var duration = 0L
        var position = 0L
        var buffered = 0L
        var focused = false
        var previewing = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init {
            isFocusable = true
            if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
        }

        override fun onDraw(canvas: Canvas) {
            val h = if (focused || previewing) 12f else 7f
            val cy = height / 2f
            val w = width.toFloat()
            fun fraction(ms: Long) = if (duration > 0) (ms.toFloat() / duration).coerceIn(0f, 1f) else 0f
            rect.set(0f, cy - h / 2, w, cy + h / 2)
            paint.color = Color.argb(70, 255, 255, 255)
            canvas.drawRoundRect(rect, h, h, paint)
            rect.right = w * fraction(buffered)
            paint.color = Color.argb(120, 255, 255, 255)
            canvas.drawRoundRect(rect, h, h, paint)
            val x = w * fraction(position)
            rect.right = x
            paint.color = Ui.accent
            canvas.drawRoundRect(rect, h, h, paint)
            if (focused || previewing) {
                paint.color = Color.WHITE
                canvas.drawCircle(x.coerceIn(14f, w - 14f), cy, 14f, paint)
            }
        }
    }
}

// The "You're watching" page, shown after a few seconds paused (as in NuvioTV): the logo or
// title, which episode, its description and the time. Any key closes it.
class PauseOverlay(private val context: Context) {
    val view = FrameLayout(context).apply {
        visibility = View.GONE
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(235, 0, 0, 0), Color.argb(170, 0, 0, 0), Color.argb(60, 0, 0, 0)))
    }
    private val clock = Ui.text(context, Ui.Type.Headline, "", Color.argb(240, 255, 255, 255))
    private val label = Ui.text(context, Ui.Type.Overline, "YOU'RE WATCHING", Ui.accent)
    val logo = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
        visibility = View.GONE
    }
    private val title = Ui.text(context, Ui.Type.Display, "", Color.WHITE, maxLines = 2)
    private val meta = Ui.text(context, Ui.Type.Headline, "", Color.argb(200, 255, 255, 255))
    private val episodeTitle = Ui.text(context, Ui.Type.Title, "", Color.WHITE, maxLines = 2)
    private val description = Ui.text(context, Ui.Type.Body, "", Color.argb(215, 255, 255, 255), maxLines = 5)
    private val hint = Ui.text(context, Ui.Type.Caption, "Press any button to go back to the video", Color.argb(150, 255, 255, 255))

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.gutter + 24, 0, 0, 0)
            addView(label)
            addView(logo, LinearLayout.LayoutParams(-2, 190).apply { topMargin = 28 })
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 20 })
            addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 18 })
            addView(episodeTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 26 })
            addView(description, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })
            addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = 40 })
        }
        view.addView(column, FrameLayout.LayoutParams(1100, -2, Gravity.CENTER_VERTICAL or Gravity.START))
        view.addView(clock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = 48; rightMargin = Ui.gutter
        })
    }

    val isShowing get() = view.visibility == View.VISIBLE

    fun show(name: String, metaLine: String, episodeName: String, about: String) {
        title.text = name
        title.visibility = if (logo.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        meta.text = metaLine
        meta.visibility = if (metaLine.isBlank()) View.GONE else View.VISIBLE
        episodeTitle.text = episodeName
        episodeTitle.visibility = if (episodeName.isBlank()) View.GONE else View.VISIBLE
        description.text = about
        description.visibility = if (about.isBlank()) View.GONE else View.VISIBLE
        clock.text = android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date())
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate().alpha(1f).setDuration(500).start()
    }

    fun hide() {
        if (!isShowing) return
        view.animate().alpha(0f).setDuration(200).withEndAction { view.visibility = View.GONE }.start()
    }
}
