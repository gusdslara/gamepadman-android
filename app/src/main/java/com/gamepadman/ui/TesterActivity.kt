package com.gamepadman.ui

import android.hardware.input.InputManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamepadman.R

/** Shows every connected gamepad and its live input, to verify the virtual controller works. */
class TesterActivity : ComponentActivity(), InputManager.InputDeviceListener {
    private val devices = mutableStateListOf<String>()
    private val pressed = mutableStateMapOf<Int, Boolean>()
    private val axes = mutableStateMapOf<Int, Float>()
    private val lastEvent = mutableStateOf("Press something on the controller…")
    private lateinit var im: InputManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        im = getSystemService(InputManager::class.java)
        refreshDevices()
        setContent { PadTheme { Surface(Modifier.fillMaxSize()) { TesterScreen() } } }
    }

    override fun onResume() { super.onResume(); im.registerInputDeviceListener(this, null); refreshDevices() }
    override fun onPause() { im.unregisterInputDeviceListener(this); super.onPause() }
    override fun onInputDeviceAdded(deviceId: Int) = refreshDevices()
    override fun onInputDeviceRemoved(deviceId: Int) = refreshDevices()
    override fun onInputDeviceChanged(deviceId: Int) = refreshDevices()

    private fun isPad(d: InputDevice) =
        d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    private fun refreshDevices() {
        devices.clear()
        InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { isPad(it) }.forEach {
            devices += "%s  [%04x:%04x]".format(it.name, it.vendorId, it.productId)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val src = event.source
        val fromPad = src and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            src and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK || KeyEvent.isGamepadButton(event.keyCode)
        if (!fromPad) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) pressed[event.keyCode] = true
        else if (event.action == KeyEvent.ACTION_UP) pressed.remove(event.keyCode)
        lastEvent.value = "${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} — ${event.device?.name}"
        return true
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (ev.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || ev.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(ev)
        }
        for (a in AXES) axes[a] = ev.getAxisValue(a)
        return true
    }

    companion object {
        val AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun TesterScreen() {
        val cs = MaterialTheme.colorScheme
        LazyColumn(
            Modifier.fillMaxSize().systemBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text("Test input", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Start the overlay, then use the on-screen controls here. Anything a game would receive appears below.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
            }
            item {
                Section("Connected controllers", R.drawable.ic_gamepad) {
                    if (devices.isEmpty()) Text("No controller detected.", color = cs.onSurfaceVariant)
                    devices.forEach { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                Section("Live input", R.drawable.ic_activity) {
                    Text(lastEvent.value, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                        StickView("Left", axes[MotionEvent.AXIS_X] ?: 0f, axes[MotionEvent.AXIS_Y] ?: 0f)
                        // XPAD-style pads report the right stick on Z/RZ, generic ones on RX/RY.
                        val rx = (axes[MotionEvent.AXIS_Z] ?: 0f).takeIf { it != 0f } ?: (axes[MotionEvent.AXIS_RX] ?: 0f)
                        val ry = (axes[MotionEvent.AXIS_RZ] ?: 0f).takeIf { it != 0f } ?: (axes[MotionEvent.AXIS_RY] ?: 0f)
                        StickView("Right", rx, ry)
                    }
                    val lt = maxOf(axes[MotionEvent.AXIS_LTRIGGER] ?: 0f, axes[MotionEvent.AXIS_BRAKE] ?: 0f)
                    val rt = maxOf(axes[MotionEvent.AXIS_RTRIGGER] ?: 0f, axes[MotionEvent.AXIS_GAS] ?: 0f)
                    Bar("LT", lt); Bar("RT", rt)
                    Text(
                        "D-pad: x=%.0f y=%.0f".format(axes[MotionEvent.AXIS_HAT_X] ?: 0f, axes[MotionEvent.AXIS_HAT_Y] ?: 0f),
                        fontFamily = FontFamily.Monospace,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (pressed.isEmpty()) Text("No buttons pressed", color = cs.onSurfaceVariant)
                        pressed.keys.sorted().forEach { k ->
                            Box(Modifier.background(cs.primaryContainer, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                                Text(KeyEvent.keyCodeToString(k).removePrefix("KEYCODE_"), color = cs.onPrimaryContainer)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun StickView(label: String, x: Float, y: Float) {
        val cs = MaterialTheme.colorScheme
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(96.dp)) {
                val r = size.minDimension / 2f
                drawCircle(cs.surfaceVariant, r)
                drawCircle(cs.outline, r, style = Stroke(2f))
                val m = kotlin.math.hypot(x, y).coerceAtLeast(1f) // real pads report a square range
                drawCircle(cs.primary, r * 0.18f, center + Offset(x / m * r * 0.82f, y / m * r * 0.82f))
            }
            Spacer(Modifier.height(4.dp))
            Text("$label  %.2f, %.2f".format(x, y), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
        }
    }

    @Composable
    private fun Bar(label: String, v: Float) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.width(32.dp), fontWeight = FontWeight.SemiBold)
            LinearProgressIndicator(progress = { v.coerceIn(0f, 1f) }, modifier = Modifier.weight(1f).height(8.dp))
            Text("%.2f".format(v), Modifier.padding(start = 8.dp), fontFamily = FontFamily.Monospace)
        }
    }
}
