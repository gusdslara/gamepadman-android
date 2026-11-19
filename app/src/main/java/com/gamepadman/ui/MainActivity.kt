package com.gamepadman.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gamepadman.R
import com.gamepadman.core.Identity
import com.gamepadman.layout.PadProfile
import com.gamepadman.layout.StickMode
import com.gamepadman.layout.Store
import com.gamepadman.service.PadService
import com.gamepadman.uhid.ShizukuBridge
import com.gamepadman.adb.Activator
import com.gamepadman.core.VirtualPad
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PadTheme { Surface(Modifier.fillMaxSize()) { MainScreen(resumeTick.intValue) } } }
    }

    override fun onResume() {
        super.onResume()
        ShizukuBridge.refresh()
        resumeTick.intValue++
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun MainScreen(tick: Int) {
    val ctx = LocalContext.current
    val access by ShizukuBridge.access.collectAsState()
    val pad by VirtualPad.status.collectAsState()
    val helperAlive by Activator.helperAlive.collectAsState()
    val svc by PadService.state.collectAsState()

    // re-evaluated on every resume
    val canOverlay = remember(tick) { Settings.canDrawOverlays(ctx) }
    var notifTick by remember { mutableIntStateOf(0) }
    val notifOk = remember(tick, notifTick) {
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOk = remember(tick) {
        (ctx.getSystemService(PowerManager::class.java)).isIgnoringBatteryOptimizations(ctx.packageName)
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifTick++ }
    androidx.compose.runtime.LaunchedEffect(tick) {
        if (!VirtualPad.usingHelper) Activator.setHelperAlive(
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Activator.probeHelper() },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().systemBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Header() }

        item {
            Section("On-screen controller", R.drawable.ic_gamepad, "Touch controls that this phone sees as a real Xbox controller") {
                val (statusText, tone) = when {
                    !svc.overlay -> "Stopped" to Tone.IDLE
                    pad is VirtualPad.Status.Ready -> "Active — ${(pad as VirtualPad.Status.Ready).name}" to Tone.OK
                    pad is VirtualPad.Status.Error -> (pad as VirtualPad.Status.Error).message to Tone.ERROR
                    pad is VirtualPad.Status.Starting -> (pad as VirtualPad.Status.Starting).message to Tone.WARN
                    else -> "Connecting…" to Tone.WARN
                }
                StatusPill(statusText, tone)

                val activated = remember(tick, helperAlive, access) { VirtualPad.isActivated(ctx) }
                CheckRow(
                    activated, "Activation",
                    when {
                        helperAlive -> "Helper running. The controller is created when you start the overlay."
                        activated -> "Done. GamepadMan starts its helper automatically when needed."
                        else -> "One-time setup inside the app (Wireless debugging). No PC or root needed."
                    },
                ) {
                    FilledTonalButton({ ctx.startActivity(Intent(ctx, ActivationActivity::class.java)) }) {
                        Text(if (activated) "Manage" else "Activate")
                    }
                }
                CheckRow(canOverlay, "Display over other apps", "Needed to draw the controls on top of games.") {
                    if (!canOverlay) FilledTonalButton({
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    }) { Text("Allow") }
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    CheckRow(notifOk, "Notifications", "Shows the Hide / Edit / Stop controls while playing.", optional = true) {
                        if (!notifOk) FilledTonalButton({ notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow") }
                    }
                }
                CheckRow(batteryOk, "Unrestricted battery", "Prevents the system from closing the controller.", optional = true) {
                    if (!batteryOk) FilledTonalButton({
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                        }
                    }) { Text("Allow") }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                IdentityPicker(svc.overlay)

                val ready = activated && canOverlay
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (svc.overlay) {
                        Button(
                            { PadService.send(ctx, PadService.ACTION_OVERLAY_STOP) },
                            Modifier.weight(1f).height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                        ) { Icon(painterResource(R.drawable.ic_square), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Stop overlay") }
                    } else {
                        Button(
                            { PadService.send(ctx, PadService.ACTION_OVERLAY_START) },
                            Modifier.weight(1f).height(52.dp), enabled = ready,
                        ) { Icon(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Start overlay") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton({ ctx.startActivity(Intent(ctx, EditorActivity::class.java)) }, Modifier.weight(1f).height(48.dp)) {
                        Icon(painterResource(R.drawable.ic_pencil), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Edit layout")
                    }
                    OutlinedButton({ ctx.startActivity(Intent(ctx, TesterActivity::class.java)) }, Modifier.weight(1f).height(48.dp)) {
                        Icon(painterResource(R.drawable.ic_activity), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Test input")
                    }
                }
                if (svc.overlay) {
                    Text(
                        "Open your game now. Tap the floating button to hide/show the controls; long-press it to edit the layout over the game.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Section("Bluetooth controller", R.drawable.ic_bluetooth, "Use this phone as a wireless gamepad for a PC, TV or tablet") {
                Text(
                    "Pair this phone from the other device's Bluetooth settings. No Shizuku needed.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton({ ctx.startActivity(Intent(ctx, BluetoothActivity::class.java)) }, Modifier.fillMaxWidth().height(48.dp)) {
                    Text(if (svc.bluetooth) "Bluetooth mode is on — open" else "Set up Bluetooth mode")
                }
            }
        }

        item { ProfileSection(tick) }

        item {
            Section("How it works", R.drawable.ic_circle_alert) {
                val steps = listOf(
                    "Activate once: GamepadMan pairs with the phone's own Wireless debugging (Developer options).",
                    "It then starts a tiny helper that creates a virtual Xbox 360 controller — the same thing a USB controller does.",
                    "After a reboot just tap Start overlay: GamepadMan turns Wireless debugging on and restarts the helper by itself (needs Wi-Fi).",
                    "Rooted? Use root in Activation. Already use Shizuku? It works too.",
                )
                steps.forEachIndexed { i, s ->
                    Row { Text("${i + 1}.", fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp)); Text(s, style = MaterialTheme.typography.bodyMedium) }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun Header() {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp)) {
        Text("GamepadMan", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Play gamepad-only games with touch controls, or turn your phone into a Bluetooth controller.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdentityPicker(overlayOn: Boolean) {
    var id by remember { mutableStateOf(Store.identity) }
    Text("Controller type", style = MaterialTheme.typography.labelLarge)
    val options = listOf(Identity.XBOX_360 to "Xbox 360", Identity.XBOX_ONE to "Xbox One", Identity.GENERIC to "Generic")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = id == value,
                onClick = {
                    id = value; Store.identity = value
                    if (overlayOn) VirtualPad.restartDevice()
                },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
    Text(
        "Xbox 360 is the most compatible. Change it only if a game does not detect the controller.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ProfileSection(tick: Int) {
    var version by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { version++ }
        Store.addListener(l)
        onDispose { Store.removeListener(l) }
    }
    val profile = remember(version, tick) { Store.active() }
    val names = remember(version, tick) { Store.profileNames() }
    var dialog by remember { mutableStateOf<String?>(null) } // "new" | "rename" | "delete"

    fun update(block: PadProfile.() -> Unit) {
        val p = Store.active(); p.block(); Store.saveProfile(p)
    }

    Section("Profile & settings", R.drawable.ic_pencil, "Layouts and settings are saved per profile") {
        var menu by remember { mutableStateOf(false) }
        Box {
            OutlinedButton({ menu = true }, Modifier.fillMaxWidth().height(48.dp)) {
                Text(profile.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(painterResource(R.drawable.ic_chevron_down), null, Modifier.size(18.dp))
            }
            DropdownMenu(menu, { menu = false }) {
                names.forEach { n ->
                    DropdownMenuItem(text = { Text(n) }, onClick = { Store.setActive(n); menu = false })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton({ dialog = "new" }, Modifier.weight(1f)) { Text("New") }
            TextButton({ Store.createProfile("${profile.name} copy", profile) }, Modifier.weight(1f)) { Text("Duplicate") }
            TextButton({ dialog = "rename" }, Modifier.weight(1f)) { Text("Rename") }
            TextButton({ dialog = "delete" }, Modifier.weight(1f), enabled = names.size > 1) { Text("Delete") }
        }

        LabeledSlider("Opacity", profile.opacity, 0.15f..1f, "${(profile.opacity * 100).roundToInt()}%") { update { opacity = it } }
        LabeledSlider("Size", profile.scale, 0.6f..1.5f, "${(profile.scale * 100).roundToInt()}%") { update { scale = it } }
        LabeledSlider("Stick dead zone", profile.deadzone, 0f..0.4f, "${(profile.deadzone * 100).roundToInt()}%") { update { deadzone = it } }

        SwitchRow("Floating sticks", "Stick center follows where your thumb lands", profile.stickMode == StickMode.FLOATING) {
            update { stickMode = if (it) StickMode.FLOATING else StickMode.FIXED }
        }
        SwitchRow("Slide between buttons", "Drag your thumb from A to B without lifting", profile.slideButtons) { update { slideButtons = it } }
        SwitchRow("D-pad diagonals", null, profile.dpadDiagonals) { update { dpadDiagonals = it } }
        SwitchRow("Vibrate on press", null, profile.haptics) { update { haptics = it } }
        var bubble by remember { mutableStateOf(Store.showBubble) }
        SwitchRow("Floating button", "Quick hide / show / edit while playing", bubble) { bubble = it; Store.showBubble = it }
        TextButton({ update { resetLayout() } }) { Text("Restore Xbox 360 default layout") }
    }

    when (dialog) {
        "new", "rename" -> NameDialog(
            title = if (dialog == "new") "New profile" else "Rename profile",
            initial = if (dialog == "new") "" else profile.name,
            onDismiss = { dialog = null },
        ) { name ->
            if (dialog == "new") Store.createProfile(name) else Store.renameProfile(profile.name, name)
            dialog = null
        }
        "delete" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete “${profile.name}”?") },
            text = { Text("Its layout and settings will be removed. This cannot be undone.") },
            confirmButton = { TextButton({ Store.deleteProfile(profile.name); dialog = null }) { Text("Delete") } },
            dismissButton = { TextButton({ dialog = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onCommit: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                if (v == value) display else "${(v * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(value = v, onValueChange = { v = it }, valueRange = range, onValueChangeFinished = { onCommit(v) })
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton({ onConfirm(text) }, enabled = text.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
