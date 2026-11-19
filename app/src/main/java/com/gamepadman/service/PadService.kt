package com.gamepadman.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gamepadman.R
import com.gamepadman.bt.BtGamepad
import com.gamepadman.core.PadHub
import com.gamepadman.layout.Store
import com.gamepadman.ui.GamepadView
import com.gamepadman.ui.MainActivity
import com.gamepadman.ui.PadPanel
import com.gamepadman.core.VirtualPad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Foreground service that keeps the overlay controls and/or the Bluetooth controller alive
 * while you play. Restarted automatically by the system (START_STICKY).
 */
class PadService : Service() {

    data class State(val overlay: Boolean = false, val controlsVisible: Boolean = false, val bluetooth: Boolean = false)

    companion object {
        const val ACTION_OVERLAY_START = "com.gamepadman.OVERLAY_START"
        const val ACTION_OVERLAY_STOP = "com.gamepadman.OVERLAY_STOP"
        const val ACTION_TOGGLE = "com.gamepadman.TOGGLE"
        const val ACTION_EDIT = "com.gamepadman.EDIT"
        const val ACTION_BT_START = "com.gamepadman.BT_START"
        const val ACTION_BT_STOP = "com.gamepadman.BT_STOP"
        const val ACTION_STOP_ALL = "com.gamepadman.STOP_ALL"

        private const val CHANNEL = "gamepadman"
        private const val NOTIF_ID = 7

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, PadService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Throwable) {
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var wm: WindowManager

    private var panel: PadPanel? = null
    private var panelAttached = false
    private var bubble: ImageView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlayOn = false
    private var btOn = false

    private val storeListener: () -> Unit = {
        main.post {
            val p = panel
            if (p != null && !p.isEditing) p.setProfile(Store.active())
            if (overlayOn) syncBubble()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        Store.addListener(storeListener)
        scope.launch {
            combine(VirtualPad.status, BtGamepad.status) { _, _ -> Unit }.collect { updateNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        // Must enter foreground immediately.
        val wantsBt = btOn || action == ACTION_BT_START
        val wantsOverlay = overlayOn || action == ACTION_OVERLAY_START || (action == null && Store.overlayWanted)
        if (!goForeground(wantsOverlay, wantsBt && action != ACTION_BT_STOP)) {
            stopSelf(); return START_NOT_STICKY
        }
        try {
            when (action) {
                ACTION_OVERLAY_START -> startOverlay()
                ACTION_OVERLAY_STOP -> stopOverlay()
                ACTION_TOGGLE -> toggleControls()
                ACTION_EDIT -> editOverlay()
                ACTION_BT_START -> { btOn = true; BtGamepad.start(this) }
                ACTION_BT_STOP -> { btOn = false; BtGamepad.stop() }
                ACTION_STOP_ALL -> { stopOverlay(); if (btOn) { btOn = false; BtGamepad.stop() } }
                null -> if (Store.overlayWanted) startOverlay() // restarted by the system
            }
        } catch (t: Throwable) {
            android.util.Log.e("GamepadMan", "action $action failed", t)
        }
        publishState()
        if (!overlayOn && !btOn) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(overlayOn, btOn)
        return START_STICKY
    }

    private fun goForeground(overlay: Boolean, bt: Boolean): Boolean {
        var type = 0
        if (Build.VERSION.SDK_INT >= 34 && overlay) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT >= 29 && bt && BtGamepad.hasPermission(this)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        if (Build.VERSION.SDK_INT >= 34 && type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            true
        } catch (t: Throwable) {
            android.util.Log.e("GamepadMan", "startForeground failed", t)
            false
        }
    }

    // ------------------------------------------------------------------ overlay

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        Store.overlayWanted = true
        overlayOn = true
        if (panel == null) {
            panel = PadPanel(this, PadHub.local).apply {
                setProfile(Store.active())
                onEditFinished = { publishState() }
            }
        }
        attachPanel()
        VirtualPad.start()
    }

    private fun stopOverlay() {
        Store.overlayWanted = false
        detachPanel()
        removeBubble()
        panel = null
        if (overlayOn) VirtualPad.stop()
        overlayOn = false
    }

    private fun toggleControls() {
        if (!overlayOn) { startOverlay(); return }
        if (panelAttached) detachPanel() else attachPanel()
        publishState()
        updateNotification()
    }

    private fun editOverlay() {
        if (!overlayOn) startOverlay()
        if (!panelAttached) attachPanel()
        panel?.startEdit()
    }

    private fun panelParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "GamepadMan controls"
        layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    private fun attachPanel() {
        val p = panel ?: return
        if (panelAttached) return
        try {
            wm.addView(p, panelParams())
            panelAttached = true
        } catch (t: Throwable) {
            android.util.Log.e("GamepadMan", "addView failed", t)
        }
        syncBubble(forceTop = true)
    }

    private fun detachPanel() {
        val p = panel ?: return
        if (!panelAttached) return
        if (p.isEditing) p.finishEdit(false)
        runCatching { wm.removeViewImmediate(p) } // view releases every input on detach
        panelAttached = false
        PadHub.local.neutral()
        syncBubble()
    }

    // ------------------------------------------------------------------ bubble

    private fun syncBubble(forceTop: Boolean = false) {
        if (!overlayOn || !Store.showBubble) { removeBubble(); return }
        if (bubble != null && forceTop) removeBubble()
        if (bubble == null) addBubble()
        bubble?.alpha = if (panelAttached) 0.9f else 0.55f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val dp = resources.displayMetrics.density
        val size = (44 * dp).toInt()
        val v = ImageView(this).apply {
            setImageResource(R.drawable.ic_gamepad)
            setColorFilter(GamepadView.ACCENT)
            val pad = (10 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 18, 20, 24))
                setStroke((1.5f * dp).toInt(), Color.argb(140, 163, 230, 53))
            }
            contentDescription = "GamepadMan: tap to show or hide controls, long press to edit"
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Store.bubbleX; y = Store.bubbleY
            title = "GamepadMan bubble"
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var dragging = false; var longPressed = false
        val longPress = Runnable { longPressed = true; v.performHapticFeedback(0); editOverlay() }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    dragging = false; longPressed = false
                    main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) { dragging = true; main.removeCallbacks(longPress) }
                    if (dragging) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) { Store.bubbleX = lp.x; Store.bubbleY = lp.y }
                    else if (!longPressed) toggleControls()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
        try {
            wm.addView(v, lp)
            bubble = v; bubbleParams = lp
        } catch (t: Throwable) {
            android.util.Log.e("GamepadMan", "bubble addView failed", t)
        }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeViewImmediate(it) } }
        bubble = null; bubbleParams = null
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Controller service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the on-screen controller running"
                setShowBadge(false)
            },
        )
    }

    private fun pi(action: String, req: Int): PendingIntent = PendingIntent.getService(
        this, req, Intent(this, PadService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun statusText(): String {
        val parts = mutableListOf<String>()
        if (overlayOn) {
            parts += when (val p = VirtualPad.status.value) {
                is VirtualPad.Status.Ready -> "Controller active" + if (panelAttached) "" else " (controls hidden)"
                is VirtualPad.Status.Starting -> p.message
                is VirtualPad.Status.Error -> p.message
                VirtualPad.Status.Off -> "Overlay on"
            }
        }
        if (btOn) {
            val s = BtGamepad.status.value
            parts += when (s.phase) {
                BtGamepad.Phase.CONNECTED -> "Bluetooth: connected to ${s.host}"
                BtGamepad.Phase.READY -> "Bluetooth: waiting for a host"
                BtGamepad.Phase.CONNECTING -> "Bluetooth: connecting to ${s.host ?: "host"}…"
                else -> "Bluetooth: ${s.message ?: s.phase.name.lowercase()}"
            }
        }
        return parts.joinToString(" • ").ifEmpty { "Starting…" }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_gamepad)
            .setContentTitle("GamepadMan")
            .setContentText(statusText())
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
        if (overlayOn) {
            b.addAction(R.drawable.ic_gamepad, if (panelAttached) "Hide" else "Show", pi(ACTION_TOGGLE, 1))
            b.addAction(R.drawable.ic_pencil, "Edit layout", pi(ACTION_EDIT, 2))
        }
        b.addAction(R.drawable.ic_x, "Stop", pi(ACTION_STOP_ALL, 3))
        return b.build()
    }

    private fun updateNotification() {
        if (!overlayOn && !btOn) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification()) }
    }

    private fun publishState() {
        _state.value = State(overlayOn, panelAttached, btOn)
    }

    override fun onDestroy() {
        detachPanel()
        removeBubble()
        panel = null
        if (overlayOn) VirtualPad.stop()
        if (btOn) BtGamepad.stop()
        overlayOn = false; btOn = false
        publishState()
        Store.removeListener(storeListener)
        scope.cancel()
        super.onDestroy()
    }
}
