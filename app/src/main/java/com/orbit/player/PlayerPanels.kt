package com.orbit.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// The player's side panel, after NuvioTV's (NuvioMedia/NuvioTV, GPL-3.0): slides in from the
// right over the playing video, with a title, optional tabs (seasons) and a list. Used for
// sources, episodes, subtitles and audio.
class SidePanel(private val context: Context) {

    data class Row(
        val title: String,
        val subtitle: String = "",
        val checked: Boolean = false,
        val trailing: String = "",
        val header: Boolean = false,
        val onClick: (() -> Unit)? = null
    )

    val view = FrameLayout(context).apply { visibility = View.GONE }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(48, 48, 40, 24)
        background = GradientDrawable().apply {
            setColor(Color.argb(246, 19, 23, 32))
            cornerRadii = floatArrayOf(36f, 36f, 0f, 0f, 0f, 0f, 36f, 36f)
        }
        elevation = 24f
    }
    private val title = Ui.text(context, Ui.Type.Title, "", Color.WHITE, maxLines = 1)
    private val subtitle = Ui.text(context, Ui.Type.Label, "", Ui.textSecondary, maxLines = 2)
    private val tabs = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
        setPadding(4, 8, 4, 8)
    }
    private val tabScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        addView(tabs)
    }
    private val status = Ui.text(context, Ui.Type.Label, "", Ui.textMuted)
    private val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        itemAnimator = null
        clipToPadding = false
        setPadding(0, 8, 0, 40)
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
    }
    private var rows: List<Row> = emptyList()
    // Back from a panel opened from another (an episode's sources) returns to that one.
    var onBack: (() -> Unit)? = null

    init {
        panel.addView(title)
        panel.addView(subtitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
        panel.addView(tabScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 18 })
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 14 })
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = 12 })
        view.addView(panel, FrameLayout.LayoutParams(900, -1, Gravity.END))
        list.adapter = Adapter()
    }

    val isShowing get() = view.visibility == View.VISIBLE

    fun show(name: String, about: String = "") {
        title.text = name
        subtitle.text = about
        subtitle.visibility = if (about.isBlank()) View.GONE else View.VISIBLE
        if (!isShowing) {
            view.visibility = View.VISIBLE
            panel.translationX = 160f
            panel.alpha = 0f
            panel.animate().translationX(0f).alpha(1f).setDuration(180).start()
        }
    }

    fun hide() {
        if (!isShowing) return
        onBack = null
        panel.animate().translationX(160f).alpha(0f).setDuration(150).withEndAction {
            view.visibility = View.GONE
        }.start()
    }

    private var selectedTabId = View.NO_ID

    // Tabs (seasons): the selected one filled; picking one runs onPick.
    fun setTabs(names: List<String>, selected: Int, onPick: (Int) -> Unit) {
        tabs.removeAllViews()
        selectedTabId = View.NO_ID
        tabScroll.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
        names.forEachIndexed { i, label ->
            val pill = Ui.pill(context, label) { onPick(i) }
            Ui.paintPill(pill, i == selected)
            pill.setOnFocusChangeListener { v, _ -> Ui.paintPill(v as TextView, i == selected) }
            pill.id = View.generateViewId()
            if (i == selected) selectedTabId = pill.id
            tabs.addView(pill, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = 14 })
        }
    }

    fun focusTab(index: Int) {
        tabs.getChildAt(index)?.let { tab ->
            tab.requestFocus()
            tabScroll.post { tabScroll.smoothScrollTo((tab.left - 40).coerceAtLeast(0), 0) }
        }
    }

    fun setStatus(text: String) {
        status.text = text
        status.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
    }

    // Replaces the list; focus goes to focusIndex (or stays on the row it was on).
    fun setRows(newRows: List<Row>, focusIndex: Int? = null) {
        val focusedBefore = list.findFocus()?.let { list.findContainingViewHolder(it)?.bindingAdapterPosition }
        rows = newRows
        list.adapter?.notifyDataSetChanged()
        // Nothing in the panel focused (it's just opened): the first row, so the remote works.
        val nothingFocused = view.findFocus() == null
        val target = focusIndex ?: focusedBefore ?: (if (nothingFocused) rows.indexOfFirst { !it.header } else null) ?: return
        if (target !in rows.indices) return
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(target, 160)
        // The row exists once the list has laid out, which can take a frame or two.
        list.post(object : Runnable {
            var tries = 0
            override fun run() {
                val row = list.findViewHolderForAdapterPosition(target)?.itemView
                if (row != null) row.requestFocus() else if (tries++ < 10) list.postDelayed(this, 50)
            }
        })
    }

    private inner class Holder(v: View) : RecyclerView.ViewHolder(v)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = rows.size
        override fun getItemViewType(position: Int) = if (rows[position].header) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            if (viewType == 1) {
                return Holder(Ui.text(context, Ui.Type.Overline, "", Ui.textMuted).apply {
                    setPadding(8, 30, 8, 12)
                    layoutParams = RecyclerView.LayoutParams(-1, -2)
                })
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(24, 20, 24, 20)
                if (android.os.Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply { bottomMargin = 8 }
            }
            val check = Ui.text(context, Ui.Type.Headline, "", Ui.accent).apply { tag = "check" }
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            column.addView(Ui.text(context, Ui.Type.BodyStrong, "", Ui.textPrimary, maxLines = 2).apply { tag = "title" })
            column.addView(Ui.text(context, Ui.Type.Caption, "", Ui.textSecondary, maxLines = 2).apply {
                tag = "subtitle"
                setPadding(0, 6, 0, 0)
            })
            row.addView(check, LinearLayout.LayoutParams(48, -2))
            row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.text(context, Ui.Type.Label, "", Ui.textSecondary).apply {
                tag = "trailing"
                setPadding(16, 0, 0, 0)
            })
            val paint = { focused: Boolean ->
                row.background = if (focused) Ui.rounded(Color.WHITE, 18f) else null
                (row.findViewWithTag<TextView>("title")).setTextColor(if (focused) Ui.background else Ui.textPrimary)
                (row.findViewWithTag<TextView>("subtitle")).setTextColor(if (focused) Color.rgb(70, 76, 92) else Ui.textSecondary)
                (row.findViewWithTag<TextView>("trailing")).setTextColor(if (focused) Color.rgb(70, 76, 92) else Ui.textSecondary)
            }
            row.setOnFocusChangeListener { _, focused -> paint(focused) }
            paint(false)
            return Holder(row)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            if (row.header) {
                (holder.itemView as TextView).text = row.title.uppercase()
                return
            }
            val v = holder.itemView
            v.findViewWithTag<TextView>("check").text = if (row.checked) "✓" else ""
            v.findViewWithTag<TextView>("title").text = row.title
            v.findViewWithTag<TextView>("subtitle").apply {
                text = row.subtitle
                visibility = if (row.subtitle.isBlank()) View.GONE else View.VISIBLE
            }
            v.findViewWithTag<TextView>("trailing").text = row.trailing
            v.setOnClickListener { row.onClick?.invoke() }
            v.nextFocusUpId = if (position == rows.indexOfFirst { !it.header }) selectedTabId else View.NO_ID
        }
    }
}
