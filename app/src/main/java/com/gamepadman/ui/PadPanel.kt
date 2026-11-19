package com.gamepadman.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.gamepadman.core.ReportChannel
import com.gamepadman.layout.Ctl
import com.gamepadman.layout.PadProfile
import com.gamepadman.layout.Store

/**
 * Gamepad surface + in-place layout editor toolbar. Used by the overlay window, the editor
 * screen and the Bluetooth controller screen.
 */
@SuppressLint("ViewConstructor")
class PadPanel(context: Context, output: ReportChannel?) : FrameLayout(context) {
    val pad = GamepadView(context).apply { this.output = output }

    /** Called when the user leaves edit mode (true = saved). */
    var onEditFinished: ((Boolean) -> Unit)? = null

    private val dp = resources.displayMetrics.density
    private var original: PadProfile? = null
    private var editing: PadProfile? = null
    private var dirty = false

    private val title = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = (44 * dp).toInt()
    }
    private val btnSmaller = chip("−") { pad.resizeSelected(0.9f) }
    private val btnBigger = chip("+") { pad.resizeSelected(1.1f) }
    private val btnVisible = chip("Hide") { pad.toggleSelectedVisible() }
    private val btnReset = chip("Reset") {
        editing?.let { it.resetLayout(); pad.profile = it; dirty = true; updateToolbar(null) }
    }
    private val btnCancel = chip("Cancel") { finishEdit(false) }
    private val btnSave = chip("Save", primary = true) { finishEdit(true) }

    private val toolbar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = (6 * dp).toInt()
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = 16 * dp; setColor(Color.argb(235, 22, 24, 29))
            setStroke((1 * dp).toInt(), Color.argb(60, 255, 255, 255))
        }
        addView(title)
        listOf(btnSmaller, btnBigger, btnVisible, btnReset, btnCancel, btnSave).forEach { addView(it) }
        visibility = View.GONE
        elevation = 8 * dp
    }

    init {
        addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(toolbar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        pad.onSelectionChanged = { updateToolbar(it) }
        pad.onLayoutEdited = { dirty = true }
        pad.onEditGestureEnd = { placeToolbar() }
    }

    /** Moves the toolbar to the spot that covers the fewest controls (never over the selected one). */
    private fun placeToolbar() {
        if (editing == null || width == 0 || toolbar.width == 0) return
        val tw = toolbar.width.toFloat()
        val th = toolbar.height.toFloat()
        val left = (width - tw) / 2f
        val margin = 8 * dp
        val r = android.graphics.RectF()
        var bestY = 0.5f
        var bestScore = Float.MAX_VALUE
        for (f in floatArrayOf(0.5f, 0.32f, 0.68f, 0.2f, 0.8f, 0f, 1f)) {
            val top = (margin + f * (height - th - 2 * margin))
            r.set(left, top, left + tw, top + th)
            val s = pad.overlapScore(r)
            if (s < bestScore - 1f) { bestScore = s; bestY = top }
        }
        val target = bestY - (height - th) / 2f
        toolbar.animate().translationY(target).setDuration(140).start()
    }

    val isEditing get() = editing != null

    fun setProfile(p: PadProfile) {
        if (editing != null) return
        pad.profile = p
    }

    fun startEdit() {
        if (editing != null) return
        original = pad.profile
        val e = pad.profile.copy()
        editing = e
        dirty = false
        pad.profile = e
        pad.editMode = true
        toolbar.visibility = View.VISIBLE
        updateToolbar(null)
    }

    fun finishEdit(save: Boolean) {
        val e = editing ?: return
        editing = null
        pad.editMode = false
        toolbar.visibility = View.GONE
        if (save) {
            Store.saveProfile(e)
            pad.profile = e
        } else {
            original?.let { pad.profile = it }
        }
        original = null
        onEditFinished?.invoke(save)
    }

    private fun updateToolbar(sel: Ctl?) {
        val e = editing ?: return
        val has = sel != null
        title.text = if (sel != null) sel.label else "Tap a control"
        btnSmaller.isEnabled = has; btnBigger.isEnabled = has; btnVisible.isEnabled = has
        listOf(btnSmaller, btnBigger, btnVisible).forEach { it.alpha = if (has) 1f else 0.4f }
        btnVisible.text = if (sel != null && !e.cfg(sel).visible) "Show" else "Hide"
        toolbar.post { placeToolbar() }
    }

    private fun chip(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = (44 * dp).toInt()
        minHeight = (44 * dp).toInt()
        setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
        setTextColor(if (primary) Color.rgb(16, 18, 22) else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor(if (primary) GamepadView.ACCENT else Color.argb(255, 44, 49, 58))
        }
        isClickable = true
        isFocusable = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (44 * dp).toInt()).apply {
            marginStart = (6 * dp).toInt()
        }
    }
}
