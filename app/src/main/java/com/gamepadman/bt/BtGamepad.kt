package com.gamepadman.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.gamepadman.core.DescriptorStyle
import com.gamepadman.core.HidReports
import com.gamepadman.core.PadHub
import com.gamepadman.layout.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executor

/**
 * Turns the phone into a Bluetooth HID gamepad (Android 9+ BluetoothHidDevice API).
 * The host (PC, TV, tablet, another phone) pairs with the phone and sees a standard gamepad.
 */
@SuppressLint("MissingPermission")
object BtGamepad {
    enum class Phase { OFF, UNSUPPORTED, NO_PERMISSION, BT_DISABLED, STARTING, READY, CONNECTING, CONNECTED, ERROR }

    data class Status(val phase: Phase, val host: String? = null, val message: String? = null)

    private val _status = MutableStateFlow(Status(Phase.OFF))
    val status: StateFlow<Status> = _status

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private var ctx: Context? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var wanted = false

    private val descriptor by lazy { HidReports.descriptor(DescriptorStyle.STANDARD, HidReports.BT_REPORT_ID) }

    fun hasPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun adapter(c: Context): BluetoothAdapter? =
        (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun isAdapterOn(c: Context): Boolean = runCatching { adapter(c)?.isEnabled == true }.getOrDefault(false)

    fun start(c: Context) {
        val app = c.applicationContext
        ctx = app
        wanted = true
        if (!hasPermission(app)) { _status.value = Status(Phase.NO_PERMISSION); return }
        val ad = adapter(app)
        if (ad == null) { _status.value = Status(Phase.UNSUPPORTED, message = "This device has no Bluetooth"); return }
        if (!ad.isEnabled) { _status.value = Status(Phase.BT_DISABLED); return }
        if (hid != null) { if (!registered) register() else publishReady(); return }
        _status.value = Status(Phase.STARTING)
        val ok = try {
            ad.getProfileProxy(app, listener, BluetoothProfile.HID_DEVICE)
        } catch (t: Throwable) {
            false
        }
        if (!ok) _status.value = Status(Phase.UNSUPPORTED, message = "Bluetooth HID device profile is not available on this phone")
    }

    fun stop() {
        wanted = false
        PadHub.bluetooth.setSink(null)
        val h = hid
        val c = ctx
        try {
            host?.let { h?.disconnect(it) }
            if (registered) h?.unregisterApp()
        } catch (_: Throwable) {
        }
        if (h != null && c != null) runCatching { adapter(c)?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        hid = null; host = null; registered = false
        _status.value = Status(Phase.OFF)
    }

    fun bondedDevices(c: Context): List<BluetoothDevice> = try {
        if (!hasPermission(c)) emptyList() else adapter(c)?.bondedDevices?.toList()?.sortedBy { it.name ?: it.address }.orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }

    fun connect(device: BluetoothDevice) {
        val h = hid ?: return
        _status.value = Status(Phase.CONNECTING, device.name ?: device.address)
        try {
            host?.takeIf { it != device }?.let { h.disconnect(it) }
            if (!h.connect(device)) _status.value = Status(Phase.ERROR, message = "Connection request was rejected")
        } catch (t: Throwable) {
            _status.value = Status(Phase.ERROR, message = t.message)
        }
    }

    fun disconnect() {
        val h = hid ?: return
        host?.let { runCatching { h.disconnect(it) } }
    }

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as? BluetoothHidDevice
            if (!wanted) { stop(); return }
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = null; registered = false; host = null
            PadHub.bluetooth.setSink(null)
            if (wanted) _status.value = Status(Phase.ERROR, message = "Bluetooth service stopped")
        }
    }

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            Store.btName, "Gamepad", "GamepadMan",
            (BluetoothHidDevice.SUBCLASS1_NONE.toInt() or BluetoothHidDevice.SUBCLASS2_GAMEPAD.toInt()).toByte(),
            descriptor,
        )
        val ok = try {
            h.registerApp(sdp, null, null, mainExecutor, callback)
        } catch (t: Throwable) {
            _status.value = Status(Phase.ERROR, message = t.message); return
        }
        if (!ok) _status.value = Status(Phase.ERROR, message = "Another app is already using the Bluetooth HID profile")
    }

    private fun publishReady() {
        val h = host
        _status.value = if (h != null) Status(Phase.CONNECTED, h.name ?: h.address) else Status(Phase.READY)
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BtGamepad.registered = registered
            if (!registered) {
                PadHub.bluetooth.setSink(null)
                if (wanted) _status.value = Status(Phase.ERROR, message = "Bluetooth HID registration was lost")
                return
            }
            publishReady()
            // Reconnect to the last host automatically.
            val c = ctx ?: return
            val target = pluggedDevice ?: Store.btLastHost?.let { addr ->
                bondedDevices(c).firstOrNull { it.address == addr }
            }
            if (target != null && host == null) connect(target)
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    Store.btLastHost = device.address
                    val h = hid
                    if (h != null) PadHub.bluetooth.setSink { r -> h.sendReport(device, HidReports.BT_REPORT_ID, r) }
                    publishReady()
                }
                BluetoothProfile.STATE_CONNECTING -> _status.value = Status(Phase.CONNECTING, device.name ?: device.address)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == device || host == null) {
                        host = null
                        PadHub.bluetooth.setSink(null)
                        if (wanted && registered) publishReady()
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                h.replyReport(device, type, HidReports.BT_REPORT_ID.toByte(), PadHub.bluetooth.currentReport())
            } else {
                h.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray?) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (host == device) { host = null; PadHub.bluetooth.setSink(null); publishReady() }
        }
    }
}
