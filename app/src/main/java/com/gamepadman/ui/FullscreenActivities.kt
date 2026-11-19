package com.gamepadman.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.gamepadman.bt.BtGamepad
import com.gamepadman.core.PadHub
import com.gamepadman.layout.Store
import kotlinx.coroutines.launch

private fun ComponentActivity.immersive() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.attributes = window.attributes.apply {
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    WindowInsetsControllerCompat(window, window.decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** Full-screen layout editor (landscape). */
class EditorActivity : ComponentActivity() {
    private lateinit var panel: PadPanel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        panel = PadPanel(this, null).apply {
            setBackgroundColor(Color.rgb(10, 12, 15))
            setProfile(Store.active())
            onEditFinished = { finish() }
        }
        setContentView(panel)
        panel.startEdit()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = panel.finishEdit(false)
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }
}

/** Full-screen controller that sends input to the connected Bluetooth host. */
class ControllerActivity : ComponentActivity() {
    private lateinit var panel: PadPanel
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        panel = PadPanel(this, PadHub.bluetooth).apply {
            setBackgroundColor(Color.rgb(10, 12, 15))
            setProfile(Store.active())
        }
        fun chip(text: String, onClick: (() -> Unit)?) = TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE); textSize = 12f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minHeight = (36 * dp).toInt()
            setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
            background = GradientDrawable().apply { cornerRadius = 18 * dp; setColor(Color.argb(200, 30, 34, 40)) }
            if (onClick != null) setOnClickListener { onClick() }
        }
        status = chip("", null)
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(status)
            addView(chip("Edit") { panel.startEdit() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * dp).toInt() })
            addView(chip("Exit") { finish() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * dp).toInt() })
        }
        val root = FrameLayout(this)
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = (64 * dp).toInt()
        })
        panel.onEditFinished = { bar.visibility = android.view.View.VISIBLE }
        setContentView(root)

        lifecycleScope.launch {
            BtGamepad.status.collect { s ->
                status.text = when (s.phase) {
                    BtGamepad.Phase.CONNECTED -> "Connected: ${s.host}"
                    BtGamepad.Phase.READY -> "Waiting for host…"
                    BtGamepad.Phase.CONNECTING -> "Connecting…"
                    BtGamepad.Phase.OFF -> "Bluetooth mode off"
                    else -> s.message ?: s.phase.name
                }
                status.setTextColor(if (s.phase == BtGamepad.Phase.CONNECTED) GamepadView.ACCENT else Color.WHITE)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    override fun onPause() {
        super.onPause()
        panel.pad.releaseAll()
        PadHub.bluetooth.neutral()
    }
}
