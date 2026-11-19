package com.gamepadman.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.MotionEvent
import android.view.View
import com.gamepadman.core.Dpad
import com.gamepadman.core.PadState
import com.gamepadman.core.ReportChannel
import com.gamepadman.layout.CType
import com.gamepadman.layout.Ctl
import com.gamepadman.layout.PadProfile
import com.gamepadman.layout.Shape
import com.gamepadman.layout.StickMode
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Single hardware-accelerated view that draws every control and handles multi-touch.
 * It redraws only when something visible changes and publishes state only on change.
 */
@SuppressLint("ViewConstructor")
class GamepadView(context: Context) : View(context) {

    companion object {
        private const val MAX_PTR = 64
        private const val NONE = -1
        private const val IGNORED = -2

        val COLOR_A = Color.rgb(0x4C, 0xC2, 0x5E)
        val COLOR_B = Color.rgb(0xE5, 0x48, 0x4D)
        val COLOR_X = Color.rgb(0x3D, 0x8B, 0xF2)
        val COLOR_Y = Color.rgb(0xEB, 0xB4, 0x0B)
        val ACCENT = Color.rgb(0xA3, 0xE6, 0x35)
    }

    /** Where the state goes (null in pure edit mode). */
    var output: ReportChannel? = null

    var onSelectionChanged: ((Ctl?) -> Unit)? = null
    var onLayoutEdited: (() -> Unit)? = null
    var onEditGestureEnd: (() -> Unit)? = null

    var profile: PadProfile = PadProfile(PadProfile.DEFAULT_NAME)
        set(value) {
            releaseAll(); field = value; selected = -1; computeGeometry(); commit(); invalidate()
        }

    var editMode = false
        set(value) {
            if (field == value) return
            releaseAll(); commit()
            field = value; selected = -1
            onSelectionChanged?.invoke(null)
            invalidate()
        }

    private val density = resources.displayMetrics.density

    // geometry (index = position in profile.controls)
    private var count = 0
    private var cx = FloatArray(16)
    private var cy = FloatArray(16)
    private var hw = FloatArray(16)
    private var hh = FloatArray(16)

    // runtime
    private val ptrCtl = IntArray(MAX_PTR) { NONE }
    private var pressCount = IntArray(16)
    private var stickPtr = IntArray(16) { NONE }
    private var stickOx = FloatArray(16)
    private var stickOy = FloatArray(16)
    private var knobX = FloatArray(16)
    private var knobY = FloatArray(16)
    private var valX = FloatArray(16)
    private var valY = FloatArray(16)
    private var dpadBits = 0
    private var dpadPtr = NONE
    private val state = PadState()
    private var lastButtons = -1
    private var lastDpad = -1

    // edit
    private var selected = -1
    private var dragPtr = NONE
    private var dragDx = 0f
    private var dragDy = 0f
    private var pinchPtr = NONE
    private var pinchStartDist = 0f
    private var pinchStartSize = 0f

    // drawing
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 5f * density), 0f)
    }
    private val grid = Paint().apply { strokeWidth = 1f }
    private val rect = RectF()
    private val path = Path()
    private val path2 = Path()

    private val vibrator: Vibrator? = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    } catch (_: Throwable) {
        null
    }
    private val tick: VibrationEffect? = try {
        if (Build.VERSION.SDK_INT >= 29) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        else VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
    } catch (_: Throwable) {
        null
    }

    init {
        isHapticFeedbackEnabled = false
        isFocusable = false
    }

    // ------------------------------------------------------------------ geometry

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        releaseAll(); computeGeometry(); commit()
    }

    private fun computeGeometry() {
        val list = profile.controls
        count = list.size
        if (cx.size < count) {
            cx = FloatArray(count); cy = FloatArray(count); hw = FloatArray(count); hh = FloatArray(count)
            pressCount = IntArray(count); stickPtr = IntArray(count) { NONE }
            stickOx = FloatArray(count); stickOy = FloatArray(count)
            knobX = FloatArray(count); knobY = FloatArray(count); valX = FloatArray(count); valY = FloatArray(count)
        }
        for (i in 0 until count) geometryOf(i)
    }

    private fun geometryOf(i: Int) {
        val c = profile.controls[i]
        val base = min(width, height).toFloat().coerceAtLeast(1f)
        val r = c.size * profile.scale * base / 2f
        cx[i] = c.x * width
        cy[i] = c.y * height
        if (c.ctl.shape == Shape.PILL) {
            hw[i] = r * 1.25f; hh[i] = r * 0.72f
        } else {
            hw[i] = r; hh[i] = r
        }
        stickOx[i] = cx[i]; stickOy[i] = cy[i]
    }

    private fun normDist(i: Int, x: Float, y: Float): Float =
        hypot((x - cx[i]) / hw[i], (y - cy[i]) / hh[i])

    private fun hit(x: Float, y: Float, buttonsOnly: Boolean, includeHidden: Boolean): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (i in 0 until count) {
            val c = profile.controls[i]
            if (!c.visible && !includeHidden) continue
            val t = c.ctl.type
            if (buttonsOnly && t != CType.BUTTON && t != CType.TRIGGER) continue
            val slop = when (t) {
                CType.BUTTON, CType.TRIGGER -> 1.22f
                CType.DPAD -> 1.12f
                CType.STICK -> if (profile.stickMode == StickMode.FLOATING && !editMode) 1.7f else 1.08f
            }
            val d = normDist(i, x, y)
            if (d <= slop && d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // ------------------------------------------------------------------ touch

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (editMode) return onEditTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e) // lowest latency: no vsync batching
                pointerDown(e.getPointerId(0), e.getX(0), e.getY(0))
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) pointerMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_POINTER_UP -> pointerUp(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_UP -> pointerUp(e.getPointerId(0))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        commit()
        return true
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        if (id !in 0 until MAX_PTR) return
        val i = hit(x, y, buttonsOnly = false, includeHidden = false)
        if (i < 0) { ptrCtl[id] = IGNORED; return }
        when (profile.controls[i].ctl.type) {
            CType.STICK -> {
                if (stickPtr[i] != NONE) { ptrCtl[id] = IGNORED; return }
                stickPtr[i] = id
                if (profile.stickMode == StickMode.FLOATING) { stickOx[i] = x; stickOy[i] = y }
                else { stickOx[i] = cx[i]; stickOy[i] = cy[i] }
                updateStick(i, x, y)
                invalidate()
            }
            CType.DPAD -> {
                if (dpadPtr != NONE) { ptrCtl[id] = IGNORED; return }
                dpadPtr = id
                setDpad(dpadFrom(i, x, y))
            }
            CType.BUTTON, CType.TRIGGER -> {
                pressCount[i]++
                buzz(); invalidate()
            }
        }
        ptrCtl[id] = i
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        if (id !in 0 until MAX_PTR) return
        val i = ptrCtl[id]
        if (i < 0) return
        when (profile.controls[i].ctl.type) {
            CType.STICK -> { updateStick(i, x, y); invalidate() }
            CType.DPAD -> setDpad(dpadFrom(i, x, y))
            CType.BUTTON, CType.TRIGGER -> if (profile.slideButtons) {
                val n = hit(x, y, buttonsOnly = true, includeHidden = false)
                if (n >= 0 && n != i && normDist(n, x, y) < 1f) {
                    pressCount[i] = (pressCount[i] - 1).coerceAtLeast(0)
                    pressCount[n]++
                    ptrCtl[id] = n
                    buzz(); invalidate()
                }
            }
        }
    }

    private fun pointerUp(id: Int) {
        if (id !in 0 until MAX_PTR) return
        val i = ptrCtl[id]
        ptrCtl[id] = NONE
        if (i < 0) return
        when (profile.controls[i].ctl.type) {
            CType.STICK -> {
                stickPtr[i] = NONE
                knobX[i] = 0f; knobY[i] = 0f; valX[i] = 0f; valY[i] = 0f
                stickOx[i] = cx[i]; stickOy[i] = cy[i]
            }
            CType.DPAD -> { dpadPtr = NONE; setDpad(0) }
            CType.BUTTON, CType.TRIGGER -> pressCount[i] = (pressCount[i] - 1).coerceAtLeast(0)
        }
        invalidate()
    }

    fun releaseAll() {
        ptrCtl.fill(NONE)
        pressCount.fill(0)
        stickPtr.fill(NONE)
        knobX.fill(0f); knobY.fill(0f); valX.fill(0f); valY.fill(0f)
        for (i in 0 until count) { stickOx[i] = cx[i]; stickOy[i] = cy[i] }
        dpadBits = 0; dpadPtr = NONE
        dragPtr = NONE; pinchPtr = NONE
        invalidate()
    }

    private fun updateStick(i: Int, x: Float, y: Float) {
        val travel = hw[i] * 0.72f
        var dx = (x - stickOx[i]) / travel
        var dy = (y - stickOy[i]) / travel
        val m = hypot(dx, dy)
        if (m > 1f) {
            dx /= m; dy /= m
            if (profile.stickMode == StickMode.FLOATING) { // base follows the finger
                stickOx[i] = x - dx * travel; stickOy[i] = y - dy * travel
            }
        }
        knobX[i] = dx; knobY[i] = dy
        val mag = min(m, 1f)
        val dz = profile.deadzone.coerceIn(0f, 0.6f)
        if (mag <= dz || mag == 0f) {
            valX[i] = 0f; valY[i] = 0f
        } else {
            val k = ((mag - dz) / (1f - dz)) / mag
            valX[i] = dx * k; valY[i] = dy * k
        }
    }

    private fun dpadFrom(i: Int, x: Float, y: Float): Int {
        val dx = x - cx[i]
        val dy = y - cy[i]
        if (hypot(dx, dy) < hw[i] * 0.2f) return 0
        var a = Math.toDegrees(atan2(dy, dx).toDouble()) // 0 = right, 90 = down
        if (a < 0) a += 360.0
        return if (profile.dpadDiagonals) {
            when (((a + 22.5) / 45.0).toInt() % 8) {
                0 -> Dpad.RIGHT
                1 -> Dpad.DOWN or Dpad.RIGHT
                2 -> Dpad.DOWN
                3 -> Dpad.DOWN or Dpad.LEFT
                4 -> Dpad.LEFT
                5 -> Dpad.UP or Dpad.LEFT
                6 -> Dpad.UP
                else -> Dpad.UP or Dpad.RIGHT
            }
        } else {
            when (((a + 45.0) / 90.0).toInt() % 4) {
                0 -> Dpad.RIGHT
                1 -> Dpad.DOWN
                2 -> Dpad.LEFT
                else -> Dpad.UP
            }
        }
    }

    private fun setDpad(bits: Int) {
        if (bits == dpadBits) return
        if (bits and dpadBits.inv() != 0) buzz()
        dpadBits = bits
        invalidate()
    }

    private fun buzz() {
        if (!profile.haptics) return
        val v = vibrator ?: return
        val t = tick ?: return
        try { v.vibrate(t) } catch (_: Throwable) { }
    }

    /** Rebuilds the logical state and publishes it if anything changed. */
    private fun commit() {
        val s = state
        s.clear()
        if (!editMode) {
            for (i in 0 until count) {
                val c = profile.controls[i].ctl
                when (c) {
                    Ctl.LS -> { s.lx = valX[i]; s.ly = valY[i] }
                    Ctl.RS -> { s.rx = valX[i]; s.ry = valY[i] }
                    Ctl.LT -> if (pressCount[i] > 0) s.lt = 1f
                    Ctl.RT -> if (pressCount[i] > 0) s.rt = 1f
                    else -> if (c.bit >= 0 && pressCount[i] > 0) s.buttons = s.buttons or (1 shl c.bit)
                }
            }
            s.dpad = dpadBits
        }
        output?.publish(s)
        lastButtons = s.buttons; lastDpad = s.dpad
    }

    override fun onDetachedFromWindow() {
        releaseAll(); commit()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------ edit mode

    private fun onEditTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x = e.getX(0); val y = e.getY(0)
                val i = hit(x, y, buttonsOnly = false, includeHidden = true)
                select(i)
                if (i >= 0) {
                    dragPtr = e.getPointerId(0)
                    dragDx = cx[i] - x; dragDy = cy[i] - y
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (selected >= 0 && pinchPtr == NONE && dragPtr != NONE) {
                val i = e.actionIndex
                val a = e.findPointerIndex(dragPtr)
                if (a >= 0) {
                    pinchPtr = e.getPointerId(i)
                    pinchStartDist = hypot(e.getX(i) - e.getX(a), e.getY(i) - e.getY(a)).coerceAtLeast(1f)
                    pinchStartSize = profile.controls[selected].size
                }
            }
            MotionEvent.ACTION_MOVE -> if (selected >= 0) {
                val cfg = profile.controls[selected]
                val a = if (dragPtr != NONE) e.findPointerIndex(dragPtr) else -1
                val b = if (pinchPtr != NONE) e.findPointerIndex(pinchPtr) else -1
                if (a >= 0 && b >= 0) {
                    val d = hypot(e.getX(b) - e.getX(a), e.getY(b) - e.getY(a))
                    cfg.size = (pinchStartSize * d / pinchStartDist).coerceIn(0.06f, 0.6f)
                } else if (a >= 0 && width > 0 && height > 0) {
                    cfg.x = ((e.getX(a) + dragDx) / width).coerceIn(0f, 1f)
                    cfg.y = ((e.getY(a) + dragDy) / height).coerceIn(0f, 1f)
                }
                geometryOf(selected)
                onLayoutEdited?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == pinchPtr) pinchPtr = NONE
                if (id == dragPtr) { dragPtr = NONE; pinchPtr = NONE }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragPtr = NONE; pinchPtr = NONE
                onEditGestureEnd?.invoke()
            }
        }
        return true
    }

    private fun select(i: Int) {
        if (selected == i) return
        selected = i
        onSelectionChanged?.invoke(if (i >= 0) profile.controls[i].ctl else null)
        invalidate()
    }

    fun selectedCtl(): Ctl? = if (selected >= 0) profile.controls[selected].ctl else null

    /** Overlap of [r] with the controls (selected one weighted heavily), used to place the editor toolbar. */
    fun overlapScore(r: RectF): Float {
        var score = 0f
        val tmp = RectF()
        for (i in 0 until count) {
            tmp.set(cx[i] - hw[i], cy[i] - hh[i], cx[i] + hw[i], cy[i] + hh[i])
            if (tmp.intersect(r)) score += tmp.width() * tmp.height() * (if (i == selected) 12f else 1f)
        }
        return score
    }

    fun resizeSelected(factor: Float) {
        if (selected < 0) return
        val c = profile.controls[selected]
        c.size = (c.size * factor).coerceIn(0.06f, 0.6f)
        geometryOf(selected); onLayoutEdited?.invoke(); invalidate()
    }

    fun toggleSelectedVisible() {
        if (selected < 0) return
        val c = profile.controls[selected]
        c.visible = !c.visible
        onLayoutEdited?.invoke(); onSelectionChanged?.invoke(c.ctl); invalidate()
    }

    fun refreshProfile() {
        computeGeometry(); invalidate()
    }

    // ------------------------------------------------------------------ drawing

    private fun a(f: Float): Int = (f.coerceIn(0f, 1f) * 255).toInt()

    override fun onDraw(canvas: Canvas) {
        val op = if (editMode) maxOf(profile.opacity, 0.75f) else profile.opacity
        if (editMode) drawGrid(canvas)
        for (i in 0 until count) {
            val c = profile.controls[i]
            if (!c.visible && !editMode) continue
            val alpha = if (c.visible) op else op * 0.35f
            when (c.ctl.type) {
                CType.STICK -> drawStick(canvas, i, alpha)
                CType.DPAD -> drawDpad(canvas, i, alpha)
                CType.BUTTON, CType.TRIGGER -> drawButton(canvas, i, c.ctl, alpha)
            }
            if (editMode) {
                if (!c.visible) {
                    dashed.color = Color.WHITE; dashed.alpha = 150
                    rect.set(cx[i] - hw[i], cy[i] - hh[i], cx[i] + hw[i], cy[i] + hh[i])
                    canvas.drawRoundRect(rect, hh[i], hh[i], dashed)
                }
                if (i == selected) {
                    dashed.color = ACCENT; dashed.alpha = 255
                    val pad = 6f * density
                    rect.set(cx[i] - hw[i] - pad, cy[i] - hh[i] - pad, cx[i] + hw[i] + pad, cy[i] + hh[i] + pad)
                    canvas.drawRoundRect(rect, hh[i] + pad, hh[i] + pad, dashed)
                }
            }
        }
    }

    private fun drawGrid(canvas: Canvas) {
        grid.color = Color.WHITE; grid.alpha = 18
        var f = 0.1f
        while (f < 1f) {
            canvas.drawLine(width * f, 0f, width * f, height.toFloat(), grid)
            canvas.drawLine(0f, height * f, width.toFloat(), height * f, grid)
            f += 0.1f
        }
        grid.alpha = 40
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), grid)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, grid)
    }

    private fun baseFill(alpha: Float) { fill.color = Color.rgb(16, 18, 22); fill.alpha = a(alpha * 0.55f) }
    private fun baseStroke(alpha: Float) { stroke.color = Color.rgb(236, 239, 244); stroke.alpha = a(alpha * 0.8f) }

    private fun drawStick(canvas: Canvas, i: Int, alpha: Float) {
        val active = stickPtr[i] != NONE
        val ox = if (active) stickOx[i] else cx[i]
        val oy = if (active) stickOy[i] else cy[i]
        val r = hw[i]
        baseFill(alpha); canvas.drawCircle(ox, oy, r, fill)
        baseStroke(alpha); canvas.drawCircle(ox, oy, r, stroke)
        stroke.alpha = a(alpha * 0.25f); canvas.drawCircle(ox, oy, r * 0.72f, stroke)
        val travel = r * 0.72f
        val kx = ox + knobX[i] * travel
        val ky = oy + knobY[i] * travel
        val kr = r * 0.42f
        if (active) { fill.color = ACCENT; fill.alpha = a(alpha * 0.85f) } else { fill.color = Color.rgb(70, 76, 86); fill.alpha = a(alpha * 0.9f) }
        canvas.drawCircle(kx, ky, kr, fill)
        baseStroke(alpha); canvas.drawCircle(kx, ky, kr, stroke)
        text.color = if (active) Color.rgb(16, 18, 22) else Color.WHITE
        text.alpha = a(alpha * 0.9f); text.textSize = kr * 0.55f
        canvas.drawText(profile.controls[i].ctl.label, kx, ky - (text.descent() + text.ascent()) / 2f, text)
    }

    private fun drawDpad(canvas: Canvas, i: Int, alpha: Float) {
        val x = cx[i]; val y = cy[i]; val r = hw[i]
        val arm = r * 0.34f
        val corner = arm * 0.35f
        path.reset()
        path.addRoundRect(x - arm, y - r, x + arm, y + r, corner, corner, Path.Direction.CW)
        path2.reset()
        path2.addRoundRect(x - r, y - arm, x + r, y + arm, corner, corner, Path.Direction.CW)
        path.op(path2, Path.Op.UNION)
        baseFill(alpha); canvas.drawPath(path, fill)
        baseStroke(alpha); canvas.drawPath(path, stroke)
        fill.color = ACCENT; fill.alpha = a(alpha * 0.85f)
        if (dpadBits and Dpad.UP != 0) { rect.set(x - arm, y - r, x + arm, y - arm); canvas.drawRoundRect(rect, corner, corner, fill) }
        if (dpadBits and Dpad.DOWN != 0) { rect.set(x - arm, y + arm, x + arm, y + r); canvas.drawRoundRect(rect, corner, corner, fill) }
        if (dpadBits and Dpad.LEFT != 0) { rect.set(x - r, y - arm, x - arm, y + arm); canvas.drawRoundRect(rect, corner, corner, fill) }
        if (dpadBits and Dpad.RIGHT != 0) { rect.set(x + arm, y - arm, x + r, y + arm); canvas.drawRoundRect(rect, corner, corner, fill) }
        // direction chevrons
        fill.color = Color.WHITE; fill.alpha = a(alpha * 0.85f)
        val t = arm * 0.45f
        val d = r * 0.68f
        triangle(canvas, x, y - d - t * 0.5f, x - t, y - d + t * 0.5f, x + t, y - d + t * 0.5f)
        triangle(canvas, x, y + d + t * 0.5f, x - t, y + d - t * 0.5f, x + t, y + d - t * 0.5f)
        triangle(canvas, x - d - t * 0.5f, y, x - d + t * 0.5f, y - t, x - d + t * 0.5f, y + t)
        triangle(canvas, x + d + t * 0.5f, y, x + d - t * 0.5f, y - t, x + d - t * 0.5f, y + t)
    }

    private fun triangle(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset(); path.moveTo(x1, y1); path.lineTo(x2, y2); path.lineTo(x3, y3); path.close()
        canvas.drawPath(path, fill)
    }

    private fun drawButton(canvas: Canvas, i: Int, ctl: Ctl, alpha: Float) {
        val pressed = pressCount[i] > 0
        val x = cx[i]; val y = cy[i]
        val tint = when (ctl) {
            Ctl.A -> COLOR_A; Ctl.B -> COLOR_B; Ctl.X -> COLOR_X; Ctl.Y -> COLOR_Y
            else -> ACCENT
        }
        val isFace = ctl == Ctl.A || ctl == Ctl.B || ctl == Ctl.X || ctl == Ctl.Y
        if (pressed) { fill.color = tint; fill.alpha = a(alpha * 0.9f) } else baseFill(alpha)
        if (ctl.shape == Shape.PILL) {
            rect.set(x - hw[i], y - hh[i], x + hw[i], y + hh[i])
            canvas.drawRoundRect(rect, hh[i], hh[i], fill)
            baseStroke(alpha); canvas.drawRoundRect(rect, hh[i], hh[i], stroke)
        } else {
            canvas.drawCircle(x, y, hw[i], fill)
            if (isFace && !pressed) { stroke.color = tint; stroke.alpha = a(alpha) } else baseStroke(alpha)
            canvas.drawCircle(x, y, hw[i], stroke)
        }
        val fg = if (pressed) Color.rgb(16, 18, 22) else if (isFace) tint else Color.WHITE
        val r = hh[i]
        when (ctl) {
            Ctl.START -> glyphLines(canvas, x, y, r, fg, alpha)
            Ctl.BACK -> glyphWindows(canvas, x, y, r, fg, alpha)
            Ctl.GUIDE -> glyphGuide(canvas, x, y, r, fg, alpha)
            else -> {
                text.color = fg; text.alpha = a(alpha.coerceAtLeast(0.5f))
                text.textSize = if (ctl.label.length == 1) r * 0.95f else r * 0.72f
                canvas.drawText(ctl.label, x, y - (text.descent() + text.ascent()) / 2f, text)
            }
        }
    }

    private fun glyphLines(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        stroke.color = color; stroke.alpha = a(alpha.coerceAtLeast(0.5f))
        val w = r * 0.42f; val g = r * 0.26f
        for (k in -1..1) canvas.drawLine(x - w, y + k * g, x + w, y + k * g, stroke)
    }

    private fun glyphWindows(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        stroke.color = color; stroke.alpha = a(alpha.coerceAtLeast(0.5f))
        val s = r * 0.34f; val o = r * 0.14f
        rect.set(x - s - o, y - s - o, x + s - o, y + s - o); canvas.drawRoundRect(rect, s * 0.3f, s * 0.3f, stroke)
        rect.set(x - s + o, y - s + o, x + s + o, y + s + o); canvas.drawRoundRect(rect, s * 0.3f, s * 0.3f, stroke)
    }

    private fun glyphGuide(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Float) {
        stroke.color = color; stroke.alpha = a(alpha.coerceAtLeast(0.5f))
        canvas.drawCircle(x, y, r * 0.48f, stroke)
        val d = r * 0.26f
        canvas.drawLine(x - d, y - d, x + d, y + d, stroke)
        canvas.drawLine(x - d, y + d, x + d, y - d, stroke)
    }
}
