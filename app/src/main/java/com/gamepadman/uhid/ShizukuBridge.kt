package com.gamepadman.uhid

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.gamepadman.BuildConfig
import com.gamepadman.IVirtualPad
import com.gamepadman.core.PadHub
import com.gamepadman.layout.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku

/**
 * Owns the connection to the privileged [UhidService] and the virtual controller lifetime.
 * Reconnects automatically when Shizuku restarts. Main thread only.
 */
object ShizukuBridge {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4210

    enum class Access { NOT_INSTALLED, NOT_RUNNING, OUTDATED, NO_PERMISSION, READY }

    sealed class Pad {
        data object Off : Pad()
        data object Connecting : Pad()
        data class Ready(val name: String) : Pad()
        data class Error(val message: String) : Pad()
    }

    private val _pad = MutableStateFlow<Pad>(Pad.Off)
    val pad: StateFlow<Pad> = _pad

    private val _access = MutableStateFlow(Access.NOT_RUNNING)
    val access: StateFlow<Access> = _access

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var wanted = false
    private var service: IVirtualPad? = null
    private var bound = false

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, UhidService::class.java.name))
            .daemon(false)
            .processNameSuffix("vpad")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                _pad.value = Pad.Error("Shizuku service did not start"); return
            }
            val s = IVirtualPad.Stub.asInterface(binder)
            service = s
            runCatching { binder.linkToDeath({ main.post { onServiceLost() } }, 0) }
            if (wanted) createDevice() else runCatching { s.close() }
        }

        override fun onServiceDisconnected(name: ComponentName?) = onServiceLost()
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        Shizuku.addBinderReceivedListenerSticky {
            refresh()
            if (wanted) bind()
        }
        Shizuku.addBinderDeadListener {
            refresh()
            service = null; bound = false
            PadHub.local.setSink(null)
            if (wanted) _pad.value = Pad.Error("Shizuku stopped. Restart Shizuku to reconnect.")
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            refresh()
            if (result == PackageManager.PERMISSION_GRANTED && wanted) bind()
        }
        refresh()
    }

    fun refresh(): Access {
        val a = try {
            when {
                !isInstalled() && !Shizuku.pingBinder() -> Access.NOT_INSTALLED
                !Shizuku.pingBinder() -> Access.NOT_RUNNING
                Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> Access.OUTDATED
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Access.NO_PERMISSION
                else -> Access.READY
            }
        } catch (_: Throwable) {
            Access.NOT_RUNNING
        }
        _access.value = a
        return a
    }

    private fun isInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true
    } catch (_: Throwable) {
        false
    }

    fun requestPermission() {
        try {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED &&
                !Shizuku.shouldShowRequestPermissionRationale()
            ) Shizuku.requestPermission(REQUEST_CODE)
        } catch (_: Throwable) {
        }
    }

    /** Creates (or keeps) the virtual controller. */
    fun start() {
        wanted = true
        if (service != null) createDevice() else bind()
    }

    /** Re-creates the device, e.g. after the identity changed. */
    fun restartDevice() {
        if (wanted && service != null) createDevice()
    }

    fun stop() {
        wanted = false
        PadHub.local.neutral()
        PadHub.local.setSink(null)
        val s = service
        if (s != null) runCatching { s.close() }
        if (bound) runCatching { Shizuku.unbindUserService(args, conn, true) }
        bound = false
        service = null
        _pad.value = Pad.Off
    }

    fun diag(): String = runCatching { service?.diag() ?: "service not connected" }.getOrElse { it.toString() }

    private fun bind() {
        when (refresh()) {
            Access.READY -> Unit
            Access.NO_PERMISSION -> { _pad.value = Pad.Error("Shizuku permission not granted"); return }
            Access.NOT_INSTALLED -> { _pad.value = Pad.Error("Shizuku is not installed"); return }
            Access.OUTDATED -> { _pad.value = Pad.Error("Shizuku is too old, please update"); return }
            Access.NOT_RUNNING -> { _pad.value = Pad.Error("Shizuku is not running"); return }
        }
        _pad.value = Pad.Connecting
        try {
            Shizuku.bindUserService(args, conn)
            bound = true
        } catch (t: Throwable) {
            _pad.value = Pad.Error("Could not bind Shizuku service: ${t.message}")
        }
    }

    private fun createDevice() {
        val s = service ?: return
        val id = Store.identity
        _pad.value = Pad.Connecting
        val rc = try {
            s.open(id.style.ordinal, id.vendor, id.product, id.version, id.deviceName)
        } catch (t: Throwable) {
            _pad.value = Pad.Error("Service error: ${t.message}"); return
        }
        if (rc == 0) {
            PadHub.local.setSink { r -> s.send(r) }
            _pad.value = Pad.Ready(id.deviceName)
        } else {
            PadHub.local.setSink(null)
            _pad.value = Pad.Error("Could not create virtual controller (errno $rc). ${diag()}")
        }
    }

    private fun onServiceLost() {
        if (service == null && !bound) return
        service = null
        bound = false
        PadHub.local.setSink(null)
        if (wanted) {
            _pad.value = Pad.Connecting
            main.postDelayed({ if (wanted && service == null) bind() }, 1000)
        }
    }
}
