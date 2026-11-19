package com.gamepadman.layout

import com.gamepadman.core.Btn
import org.json.JSONArray
import org.json.JSONObject

enum class CType { STICK, DPAD, BUTTON, TRIGGER }

enum class Shape { CIRCLE, PILL }

/** Every on-screen control of the Xbox 360 default layout. */
enum class Ctl(val type: CType, val label: String, val bit: Int, val shape: Shape) {
    LS(CType.STICK, "LS", -1, Shape.CIRCLE),
    RS(CType.STICK, "RS", -1, Shape.CIRCLE),
    DPAD(CType.DPAD, "D-Pad", -1, Shape.CIRCLE),
    A(CType.BUTTON, "A", Btn.A, Shape.CIRCLE),
    B(CType.BUTTON, "B", Btn.B, Shape.CIRCLE),
    X(CType.BUTTON, "X", Btn.X, Shape.CIRCLE),
    Y(CType.BUTTON, "Y", Btn.Y, Shape.CIRCLE),
    LB(CType.BUTTON, "LB", Btn.LB, Shape.PILL),
    RB(CType.BUTTON, "RB", Btn.RB, Shape.PILL),
    LT(CType.TRIGGER, "LT", -1, Shape.PILL),
    RT(CType.TRIGGER, "RT", -1, Shape.PILL),
    BACK(CType.BUTTON, "Back", Btn.BACK, Shape.CIRCLE),
    GUIDE(CType.BUTTON, "Guide", Btn.GUIDE, Shape.CIRCLE),
    START(CType.BUTTON, "Start", Btn.START, Shape.CIRCLE),
    L3(CType.BUTTON, "L3", Btn.L3, Shape.CIRCLE),
    R3(CType.BUTTON, "R3", Btn.R3, Shape.CIRCLE),
}

/** Position (0..1 of view width/height) and size (fraction of the shorter screen side). */
class ControlCfg(val ctl: Ctl, var x: Float, var y: Float, var size: Float, var visible: Boolean = true) {
    fun copy() = ControlCfg(ctl, x, y, size, visible)
    fun toJson(): JSONObject = JSONObject().put("c", ctl.name).put("x", x.toDouble()).put("y", y.toDouble())
        .put("s", size.toDouble()).put("v", visible)
}

enum class StickMode { FIXED, FLOATING }

class PadProfile(
    var name: String,
    var opacity: Float = 0.6f,
    var scale: Float = 1f,
    var haptics: Boolean = true,
    var stickMode: StickMode = StickMode.FIXED,
    var deadzone: Float = 0.10f,
    var slideButtons: Boolean = true,
    var dpadDiagonals: Boolean = true,
    var triggerAnalog: Boolean = false,
    val controls: MutableList<ControlCfg> = defaultControls(),
) {
    fun cfg(c: Ctl): ControlCfg = controls.first { it.ctl == c }

    fun copy(newName: String = name) = PadProfile(
        newName, opacity, scale, haptics, stickMode, deadzone, slideButtons, dpadDiagonals, triggerAnalog,
        controls.map { it.copy() }.toMutableList(),
    )

    fun resetLayout() {
        controls.clear(); controls.addAll(defaultControls())
    }

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("opacity", opacity.toDouble()).put("scale", scale.toDouble())
        .put("haptics", haptics).put("stickMode", stickMode.name).put("deadzone", deadzone.toDouble())
        .put("slide", slideButtons).put("diag", dpadDiagonals).put("trigAnalog", triggerAnalog)
        .put("controls", JSONArray().apply { controls.forEach { put(it.toJson()) } })

    companion object {
        const val DEFAULT_NAME = "Xbox 360 (default)"

        /** Xbox 360 style: left stick high / D-pad low on the left, ABXY high / right stick low on the right. */
        fun defaultControls(): MutableList<ControlCfg> = mutableListOf(
            ControlCfg(Ctl.LT, 0.075f, 0.11f, 0.12f),
            ControlCfg(Ctl.LB, 0.185f, 0.11f, 0.12f),
            ControlCfg(Ctl.RB, 0.815f, 0.11f, 0.12f),
            ControlCfg(Ctl.RT, 0.925f, 0.11f, 0.12f),
            ControlCfg(Ctl.LS, 0.135f, 0.50f, 0.36f),
            ControlCfg(Ctl.DPAD, 0.31f, 0.78f, 0.30f),
            ControlCfg(Ctl.RS, 0.69f, 0.78f, 0.30f),
            ControlCfg(Ctl.Y, 0.875f, 0.36f, 0.15f),
            ControlCfg(Ctl.X, 0.805f, 0.52f, 0.15f),
            ControlCfg(Ctl.B, 0.945f, 0.52f, 0.15f),
            ControlCfg(Ctl.A, 0.875f, 0.68f, 0.15f),
            ControlCfg(Ctl.BACK, 0.42f, 0.11f, 0.10f),
            ControlCfg(Ctl.GUIDE, 0.50f, 0.11f, 0.12f),
            ControlCfg(Ctl.START, 0.58f, 0.11f, 0.10f),
            ControlCfg(Ctl.L3, 0.285f, 0.36f, 0.10f),
            ControlCfg(Ctl.R3, 0.565f, 0.62f, 0.10f),
        )

        fun fromJson(o: JSONObject): PadProfile {
            val p = PadProfile(
                name = o.optString("name", "Profile"),
                opacity = o.optDouble("opacity", 0.6).toFloat(),
                scale = o.optDouble("scale", 1.0).toFloat(),
                haptics = o.optBoolean("haptics", true),
                stickMode = runCatching { StickMode.valueOf(o.optString("stickMode")) }.getOrDefault(StickMode.FIXED),
                deadzone = o.optDouble("deadzone", 0.10).toFloat(),
                slideButtons = o.optBoolean("slide", true),
                dpadDiagonals = o.optBoolean("diag", true),
                triggerAnalog = o.optBoolean("trigAnalog", false),
            )
            val arr = o.optJSONArray("controls")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val c = arr.optJSONObject(i) ?: continue
                    val ctl = runCatching { Ctl.valueOf(c.getString("c")) }.getOrNull() ?: continue
                    val cfg = p.controls.firstOrNull { it.ctl == ctl } ?: continue
                    cfg.x = c.optDouble("x", cfg.x.toDouble()).toFloat().coerceIn(0f, 1f)
                    cfg.y = c.optDouble("y", cfg.y.toDouble()).toFloat().coerceIn(0f, 1f)
                    cfg.size = c.optDouble("s", cfg.size.toDouble()).toFloat().coerceIn(0.05f, 0.7f)
                    cfg.visible = c.optBoolean("v", true)
                }
            }
            return p
        }
    }
}
