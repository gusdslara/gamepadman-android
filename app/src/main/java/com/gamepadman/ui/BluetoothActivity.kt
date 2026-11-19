package com.gamepadman.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gamepadman.R
import com.gamepadman.bt.BtGamepad
import com.gamepadman.layout.Store
import com.gamepadman.service.PadService

class BluetoothActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PadTheme { Surface(Modifier.fillMaxSize()) { BluetoothScreen(resumeTick.intValue) } } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun BluetoothScreen(tick: Int) {
    val ctx = LocalContext.current
    val status by BtGamepad.status.collectAsState()
    val svc by PadService.state.collectAsState()
    var permTick by remember { mutableIntStateOf(0) }
    val hasPerm = remember(tick, permTick) { BtGamepad.hasPermission(ctx) }
    val btOn = remember(tick, permTick, status) { BtGamepad.isAdapterOn(ctx) }
    val devices: List<BluetoothDevice> = remember(tick, permTick, status) { BtGamepad.bondedDevices(ctx) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permTick++ }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permTick++
        if (svc.bluetooth) PadService.send(ctx, PadService.ACTION_BT_START)
    }
    val discoverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permTick++ }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text("Bluetooth controller", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Your phone becomes a wireless gamepad for another device.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Section("Status", R.drawable.ic_bluetooth) {
                val (text, tone) = when (status.phase) {
                    BtGamepad.Phase.OFF -> "Off" to Tone.IDLE
                    BtGamepad.Phase.CONNECTED -> "Connected to ${status.host}" to Tone.OK
                    BtGamepad.Phase.READY -> "Ready — waiting for a host to connect" to Tone.WARN
                    BtGamepad.Phase.STARTING, BtGamepad.Phase.CONNECTING -> "Connecting…" to Tone.WARN
                    BtGamepad.Phase.NO_PERMISSION -> "Bluetooth permission needed" to Tone.ERROR
                    BtGamepad.Phase.BT_DISABLED -> "Bluetooth is off" to Tone.ERROR
                    BtGamepad.Phase.UNSUPPORTED, BtGamepad.Phase.ERROR -> (status.message ?: "Error") to Tone.ERROR
                }
                StatusPill(text, tone)

                CheckRow(hasPerm, "Nearby devices permission", "Lets GamepadMan talk to the paired host.") {
                    if (!hasPerm && Build.VERSION.SDK_INT >= 31) FilledTonalButton({
                        permLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
                    }) { Text("Allow") }
                }
                CheckRow(btOn, "Bluetooth on", "Turn on Bluetooth on this phone.") {
                    if (!btOn && hasPerm) FilledTonalButton({
                        runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    }) { Text("Turn on") }
                }

                var name by remember { mutableStateOf(Store.btName) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40); Store.btName = name },
                    label = { Text("Controller name (applied when started)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (svc.bluetooth) {
                    Button(
                        { PadService.send(ctx, PadService.ACTION_BT_STOP) }, Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    ) { Text("Stop Bluetooth mode") }
                } else {
                    Button(
                        { PadService.send(ctx, PadService.ACTION_BT_START) }, Modifier.fillMaxWidth().height(52.dp),
                        enabled = hasPerm && btOn,
                    ) { Icon(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Start Bluetooth mode") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        {
                            runCatching {
                                discoverLauncher.launch(
                                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300),
                                )
                            }
                        },
                        Modifier.weight(1f).height(48.dp), enabled = svc.bluetooth && hasPerm,
                    ) { Text("Make visible") }
                    Button(
                        { ctx.startActivity(Intent(ctx, ControllerActivity::class.java)) },
                        Modifier.weight(1f).height(48.dp), enabled = svc.bluetooth,
                    ) { Icon(painterResource(R.drawable.ic_gamepad), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Open controller") }
                }
            }
        }
        if (svc.bluetooth && hasPerm) {
            item {
                Section("Paired devices", R.drawable.ic_link, "Connect to a host you already paired with") {
                    if (devices.isEmpty()) {
                        Text("No paired devices yet. Tap “Make visible”, then add this phone from the other device's Bluetooth settings.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    devices.forEachIndexed { i, d ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val isHost = status.phase == BtGamepad.Phase.CONNECTED && status.host == (d.name ?: d.address)
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name ?: "Unknown device", style = MaterialTheme.typography.bodyLarge)
                                Text(d.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (isHost) OutlinedButton({ BtGamepad.disconnect() }) { Text("Disconnect") }
                            else FilledTonalButton({ BtGamepad.connect(d) }, enabled = status.phase == BtGamepad.Phase.READY) { Text("Connect") }
                        }
                    }
                }
            }
        }
        item {
            Section("Tips", R.drawable.ic_circle_alert) {
                listOf(
                    "Windows / Linux / Mac: Settings → Bluetooth → Add device → pick this phone. It appears as a standard gamepad (use Steam Input or a game that supports DirectInput controllers).",
                    "Android TV / tablets / phones: pair from Bluetooth settings; games see a normal gamepad.",
                    "Xbox and PlayStation consoles only accept their own licensed controllers, so they are not supported.",
                    "If a host does not see the phone, tap “Make visible” and pair again. Some phone makers disable the Bluetooth HID profile.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
