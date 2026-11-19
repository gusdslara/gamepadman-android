package com.gamepadman.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.gamepadman.adb.Activator
import com.gamepadman.helper.HelperClient
import com.gamepadman.layout.Store
import com.gamepadman.uhid.ShizukuBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Owns the virtual controller on this phone. Prefers the built-in helper (started by GamepadMan
 * through Wireless debugging or root) and falls back to Shizuku. Reconnects automatically.
 */
object VirtualPad {
    sealed class Status {
        data object Off : Status()
        data class Starting(val message: String) : Status()
        data class Ready(val name: String, val via: String) : Status()
        data class Error(val message: String, val needsActivation: Boolean = false) : Status()
    }

    private enum class Backend { NONE, HELPER, SHIZUKU }

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status

    private val exec = Executors.newSingleThreadExecutor { Thread(it, "vpad-ctl") }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var app: Context

    @Volatile private var wanted = false
    @Volatile private var backend = Backend.NONE
    @Volatile private var client: HelperClient? = null
    private var retries = 0

    fun init(ctx: Context) {
        app = ctx.applicationContext
        scope.launch {
            ShizukuBridge.pad.collect { p ->
                if (backend != Backend.SHIZUKU || !wanted) return@collect
                _status.value = when (p) {
                    is ShizukuBridge.Pad.Ready -> Status.Ready(p.name, "Shizuku")
                    is ShizukuBridge.Pad.Error -> Status.Error(p.message)
                    ShizukuBridge.Pad.Connecting -> Status.Starting("Connecting through Shizuku…")
                    ShizukuBridge.Pad.Off -> Status.Off
                }
            }
        }
        scope.launch {
            Activator.busy.collect { msg ->
                if (msg != null && wanted && backend == Backend.NONE) _status.value = Status.Starting(msg)
            }
        }
    }

    /** Whether the controller can be created without sending the user to the activation screen. */
    fun isActivated(ctx: Context): Boolean =
        Activator.helperAlive.value || Activator.canAutoLaunch(ctx) ||
            ShizukuBridge.access.value == ShizukuBridge.Access.READY

    fun start() {
        wanted = true
        retries = 0
        exec.execute { connect(allowLaunch = true) }
    }

    fun stop() {
        wanted = false
        exec.execute {
            PadHub.local.neutral()
            PadHub.local.setSink(null)
            client?.let { runCatching { it.close() } } // helper removes the controller on disconnect
            client = null
            if (backend == Backend.SHIZUKU) main.post { ShizukuBridge.stop() }
            backend = Backend.NONE
            _status.value = Status.Off
        }
    }

    /** Re-creates the controller (identity changed). */
    fun restartDevice() {
        if (!wanted) return
        exec.execute {
            val c = client
            if (backend == Backend.HELPER && c != null) openDevice(c)
            else if (backend == Backend.SHIZUKU) main.post { ShizukuBridge.restartDevice() }
        }
    }

    /** True while this app holds the helper connection (the helper serves one client). */
    val usingHelper: Boolean get() = client != null

    fun diag(): String = client?.diag() ?: if (backend == Backend.SHIZUKU) ShizukuBridge.diag() else "not connected"

    private fun connect(allowLaunch: Boolean) {
        if (!wanted) return
        var lastError: String? = null
        var needsActivation = false
        var c = HelperClient.connect(Store.helperPort, Store.helperToken)
        if (c == null && allowLaunch && Activator.canAutoLaunch(app)) {
            _status.value = Status.Starting("Starting the controller helper…")
            val r = Activator.launchHelper(app)
            if (r.isSuccess) c = HelperClient.connect(Store.helperPort, Store.helperToken, 1500)
            else {
                lastError = r.exceptionOrNull()?.message
                needsActivation = r.exceptionOrNull() is Activator.NeedsPairing
            }
        }
        if (!wanted) { c?.close(); return }
        if (c != null) {
            Activator.setHelperAlive(true)
            client = c
            backend = Backend.HELPER
            openDevice(c)
            return
        }
        Activator.setHelperAlive(false)
        if (ShizukuBridge.refresh() == ShizukuBridge.Access.READY) {
            backend = Backend.SHIZUKU
            main.post { ShizukuBridge.start() }
            return
        }
        backend = Backend.NONE
        _status.value = Status.Error(
            lastError ?: "GamepadMan is not activated yet. Open Activation to set it up (one time).",
            needsActivation = needsActivation || lastError == null,
        )
    }

    private fun openDevice(c: HelperClient) {
        val id = Store.identity
        _status.value = Status.Starting("Creating ${id.label}…")
        val rc = try {
            c.open(id)
        } catch (e: IOException) {
            onHelperLost(c); return
        }
        if (rc == 0) {
            retries = 0
            PadHub.local.setSink { r ->
                try { c.send(r) } catch (e: IOException) { onHelperLost(c) }
            }
            _status.value = Status.Ready(id.deviceName, if (Store.helperMode == Store.MODE_ROOT) "root helper" else "built-in helper")
        } else {
            _status.value = Status.Error("Could not create the controller (error $rc). ${c.diag()}")
        }
    }

    /** Called from any thread when the helper connection breaks. */
    private fun onHelperLost(c: HelperClient) {
        synchronized(this) {
            if (client !== c) return
            client = null
        }
        PadHub.local.setSink(null)
        c.close()
        backend = Backend.NONE
        if (!wanted) return
        _status.value = Status.Starting("Reconnecting…")
        val delay = (500L * (1 shl retries.coerceAtMost(4))).coerceAtMost(8000)
        retries++
        main.postDelayed({ exec.execute { connect(allowLaunch = true) } }, delay)
    }
}
