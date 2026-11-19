package com.gamepadman.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gamepadman.R
import com.gamepadman.adb.Activator
import com.gamepadman.adb.PairingService
import com.gamepadman.core.VirtualPad
import com.gamepadman.layout.Store
import com.gamepadman.uhid.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One-time setup that lets GamepadMan start its own privileged helper (no PC, no root, no Shizuku). */
class ActivationActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PadTheme { Surface(Modifier.fillMaxSize()) { ActivationScreen(resumeTick.intValue) } } }
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.refresh()
        resumeTick.intValue++
    }
}

private fun openDevSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // highlights the Wireless debugging entry on Pixel/AOSP builds
                .putExtra(":settings:fragment_args_key", "toggle_adb_wireless"),
        )
    }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS)) } }
}

@Composable
private fun ActivationScreen(tick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val busy by Activator.busy.collectAsState()
    val alive by Activator.helperAlive.collectAsState()
    val shizuku by ShizukuBridge.access.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError
    var working by remember { mutableStateOf(false) }

    val dev = remember(tick, refresh) { Activator.devOptionsEnabled(ctx) }
    val wd = remember(tick, refresh) { Activator.wirelessDebuggingOn(ctx) }
    val wifi = remember(tick, refresh) { Activator.onWifi(ctx) }
    val secure = remember(tick, refresh) { Activator.hasSecureSettings(ctx) }
    val paired = remember(tick, refresh) { Store.adbPaired }
    val root = remember { Activator.rootAvailable() }
    val modern = Activator.supportsWirelessDebugging()

    LaunchedEffect(tick, refresh) {
        if (!VirtualPad.usingHelper) Activator.setHelperAlive(withContext(Dispatchers.IO) { Activator.probeHelper() })
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        PairingService.start(ctx); openDevSettings(ctx)
    }

    fun startHelper(root: Boolean) {
        if (working) return
        working = true; message = null
        if (root) Store.helperMode = Store.MODE_ROOT else Store.helperMode = Store.MODE_ADB
        scope.launch {
            val r = withContext(Dispatchers.IO) { Activator.launchHelper(ctx) }
            message = if (r.isSuccess) "Helper running. You can start the overlay now." to false
            else (r.exceptionOrNull()?.message ?: "Failed") to true
            working = false; refresh++
            if (r.isSuccess && com.gamepadman.service.PadService.state.value.overlay) VirtualPad.start()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text("Activation", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    "One-time setup, all inside GamepadMan — no PC, no root, no extra apps. Android only lets debugging tools " +
                        "create controllers, so GamepadMan uses the phone's own Wireless debugging to start a tiny helper.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Section("Status", R.drawable.ic_gamepad) {
                when {
                    alive -> StatusPill("Helper running (${if (Store.helperMode == Store.MODE_ROOT) "root" else "built-in"})", Tone.OK)
                    paired && secure -> StatusPill("Activated — starts automatically when needed (Wi-Fi)", Tone.OK)
                    paired -> StatusPill("Paired — tap Start helper", Tone.WARN)
                    else -> StatusPill("Not activated", Tone.IDLE)
                }
                if (busy != null || working) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(busy ?: "Working…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                message?.let { (text, err) ->
                    Text(text, color = if (err) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (modern) {
            item {
                Section("Steps", R.drawable.ic_circle_check, "Do these once. After a reboot GamepadMan handles it.") {
                    CheckRow(dev, "1. Developer options", "Settings → About phone → Software information → tap Build number 7 times.") {
                        if (!dev) FilledTonalButton({ runCatching { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)) } }) { Text("Open") }
                    }
                    CheckRow(
                        wd == true, "2. Wireless debugging",
                        if (!wifi) "Connect to Wi-Fi first, then turn on Developer options → Wireless debugging."
                        else "Developer options → Wireless debugging → On (allow on this network).",
                    ) {
                        if (wd != true) {
                            if (secure && wifi) FilledTonalButton({
                                runCatching { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1) }; refresh++
                            }) { Text("Turn on") }
                            else FilledTonalButton({ openDevSettings(ctx) }, enabled = dev) { Text("Open") }
                        }
                    }
                    CheckRow(paired, "3. Pair GamepadMan", "Tap Pair, choose “Pair device with pairing code”, then type the code in GamepadMan's notification.") {
                        FilledTonalButton({
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else { PairingService.start(ctx); openDevSettings(ctx) }
                        }, enabled = dev) { Text(if (paired) "Re-pair" else "Pair") }
                    }
                    var code by remember { mutableStateOf("") }
                    Text("Using split screen? You can also type the code here:", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            code, { code = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f),
                            singleLine = true, label = { Text("Pairing code") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        )
                        OutlinedButton({
                            working = true; message = null
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { Activator.pair(ctx, code) }
                                working = false; refresh++
                                if (r.isSuccess) { message = "Paired!" to false; startHelper(false) }
                                else message = (r.exceptionOrNull()?.message ?: "Failed") to true
                            }
                        }, enabled = code.length == 6 && !working) { Text("Pair") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CheckRow(alive, "4. Start helper", "Runs until the phone restarts. Starting the overlay does this automatically.") {
                        Button({ startHelper(false) }, enabled = paired && !working) { Text(if (alive) "Restart" else "Start") }
                    }
                }
            }
        } else {
            item {
                Section("Android 10 or older", R.drawable.ic_circle_alert) {
                    Text(
                        "Wireless debugging needs Android 11. Connect the phone to a computer once, run “adb tcpip 5555”, " +
                            "then tap Start and accept the USB debugging prompt.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button({ Store.adbPaired = true; startHelper(false) }, Modifier.fillMaxWidth().height(48.dp), enabled = !working) {
                        Text("Start helper")
                    }
                }
            }
        }
        item {
            Section("Other ways", R.drawable.ic_link) {
                if (root) {
                    CheckRow(alive && Store.helperMode == Store.MODE_ROOT, "Root", "Start the helper with root (Magisk/KernelSU).") {
                        FilledTonalButton({ startHelper(true) }, enabled = !working) { Text("Use root") }
                    }
                }
                CheckRow(
                    shizuku == ShizukuBridge.Access.READY, "Shizuku",
                    when (shizuku) {
                        ShizukuBridge.Access.READY -> "Detected and allowed — used if the built-in helper is off."
                        ShizukuBridge.Access.NO_PERMISSION -> "Running — allow GamepadMan to use it."
                        else -> "Optional. Not needed with the built-in activation."
                    },
                    optional = true,
                ) {
                    if (shizuku == ShizukuBridge.Access.NO_PERMISSION) FilledTonalButton({ ShizukuBridge.requestPermission() }) { Text("Allow") }
                }
            }
        }
        item {
            Section("Options", R.drawable.ic_pencil) {
                var autoOff by remember { mutableStateOf(Store.autoDisableWirelessDebugging) }
                SwitchRow(
                    "Turn off Wireless debugging after start",
                    "More private. GamepadMan turns it back on by itself when needed.",
                    autoOff,
                ) { autoOff = it; Store.autoDisableWirelessDebugging = it }
                if (alive) TextButton({ scope.launch { withContext(Dispatchers.IO) { Activator.stopHelper() }; refresh++ } }) { Text("Stop helper") }
                Text(
                    "Keep Developer options on. If you use “Revoke debugging authorizations”, pair again.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
