package com.gamepadman.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.gamepadman.helper.HelperClient
import com.gamepadman.layout.Store
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.ServerSocket
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** ADB client talking to this phone's own adbd (Wireless debugging / tcpip 5555). */
private class LocalAdb(private val keys: AdbKeys) : AbsAdbConnectionManager() {
    init {
        api = Build.VERSION.SDK_INT
        setTimeout(12, TimeUnit.SECONDS)
        hostAddress = "127.0.0.1"
    }

    override fun getPrivateKey(): PrivateKey = keys.privateKey
    override fun getCertificate(): Certificate = keys.certificate
    override fun getDeviceName(): String = "GamepadMan"
}

/**
 * Built-in replacement for Shizuku: pairs with Wireless debugging, connects to the local ADB daemon,
 * and launches [com.gamepadman.helper.HelperMain] as the shell user. Everything here blocks: call
 * from a background thread.
 */
object Activator {
    private const val TAG = "GamepadMan.Activator"

    class NeedsPairing(msg: String) : Exception(msg)

    /** Progress text for the UI (null when idle). */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private val _helperAlive = MutableStateFlow(false)
    val helperAlive: StateFlow<Boolean> = _helperAlive

    fun setHelperAlive(v: Boolean) { _helperAlive.value = v }

    // ------------------------------------------------------------------ system state

    fun devOptionsEnabled(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** null when the platform does not let us read it. */
    fun wirelessDebuggingOn(ctx: Context): Boolean? =
        runCatching { Settings.Global.getInt(ctx.contentResolver, "adb_wifi_enabled") == 1 }.getOrNull()

    fun hasSecureSettings(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun onWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    fun supportsWirelessDebugging() = Build.VERSION.SDK_INT >= 30

    fun rootAvailable(): Boolean =
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su", "/data/adb/ksu/bin/su")
            .any { runCatching { File(it).exists() }.getOrDefault(false) }

    /** True when the helper can be (re)started without the user leaving the app. */
    fun canAutoLaunch(ctx: Context): Boolean = when (Store.helperMode) {
        Store.MODE_ROOT -> true
        else -> Store.adbPaired && (hasSecureSettings(ctx) || wirelessDebuggingOn(ctx) == true || !supportsWirelessDebugging())
    }

    // ------------------------------------------------------------------ pairing

    /** Address of the last pairing service found. Some phones (e.g. Samsung) don't listen on 127.0.0.1. */
    @Volatile private var pairingHost: String? = null
    @Volatile private var connectHost: String? = null

    private fun discoverPort(ctx: Context, type: String, timeoutMs: Long): Int {
        val latch = CountDownLatch(1)
        var found = -1
        val mdns = AdbMdns(ctx, type) { host, port ->
            if (port > 0) {
                if (type == AdbMdns.SERVICE_TYPE_TLS_PAIRING) pairingHost = host?.hostAddress
                if (type == AdbMdns.SERVICE_TYPE_TLS_CONNECT) connectHost = host?.hostAddress
                found = port; latch.countDown()
            }
        }
        mdns.start()
        try { latch.await(timeoutMs, TimeUnit.MILLISECONDS) } finally { runCatching { mdns.stop() } }
        return found
    }

    private fun pairOnce(ctx: Context, host: String, port: Int, code: String) {
        val adb = LocalAdb(AdbKeys.get(ctx))
        try { adb.pair(host, port, code) } finally { runCatching { adb.disconnect() } }
    }

    /** Pairs with the "Pair device with pairing code" dialog that must be open in Wireless debugging. */
    fun pair(ctx: Context, code: String, knownPort: Int = -1): Result<Unit> {
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) return Result.failure(IllegalArgumentException("The pairing code has 6 digits"))
        _busy.value = "Looking for the pairing dialog…"
        return try {
            val port = if (knownPort > 0) knownPort else discoverPort(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, 15_000)
            if (port <= 0) {
                Result.failure(IllegalStateException("Pairing dialog not found. Keep “Pair device with pairing code” open and try again."))
            } else {
                _busy.value = "Pairing…"
                val host = pairingHost
                if (host != null && host != "127.0.0.1") {
                    try {
                        pairOnce(ctx, host, port, clean)
                    } catch (e: java.net.ConnectException) {
                        pairOnce(ctx, "127.0.0.1", port, clean)
                    }
                } else {
                    pairOnce(ctx, "127.0.0.1", port, clean)
                }
                Store.adbPaired = true
                Result.success(Unit)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pair failed", t)
            Result.failure(Exception("Pairing failed: ${t.message ?: t.javaClass.simpleName}. Check the code and try again.", t))
        } finally {
            _busy.value = null
        }
    }

    fun discoverPairingPort(ctx: Context, timeoutMs: Long) = discoverPort(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs)

    // ------------------------------------------------------------------ helper launch

    private fun newToken(): String {
        val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
        return b.joinToString("") { "%02x".format(it) }
    }

    private fun freePort(): Int = runCatching { ServerSocket(0).use { it.localPort } }.getOrDefault(47_321)

    private fun launchCommand(ctx: Context, port: Int, token: String): String {
        val apk = ctx.applicationInfo.sourceDir
        // app_process replaces argv with the nice name, so the helper's cmdline starts with "gpm_helper";
        // anchoring the pattern keeps pkill from matching (and killing) the shell running this line.
        // Detach completely: subshell + setsid + nohup survive the ADB session closing (tested on Android 16).
        return "pkill -f '^gpm_helper'; " +
            "(setsid nohup app_process -Djava.class.path=$apk /system/bin --nice-name=gpm_helper " +
            "com.gamepadman.helper.HelperMain $port $token </dev/null >/data/local/tmp/gpm_helper.log 2>&1 &); sleep 1"
    }

    /** Is a helper answering on the saved port? */
    fun probeHelper(): Boolean {
        val c = HelperClient.connect(Store.helperPort, Store.helperToken, 600) ?: return false
        val ok = c.ping()
        c.close()
        return ok
    }

    private fun waitForHelper(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (probeHelper()) return true
            Thread.sleep(250)
        }
        return false
    }

    /** Starts the helper with the built-in ADB client (or root, if that mode was chosen). */
    fun launchHelper(ctx: Context): Result<Unit> {
        if (Store.helperMode == Store.MODE_ROOT) return launchWithRoot(ctx)
        val port = freePort()
        val token = newToken()
        return try {
            val adb = LocalAdb(AdbKeys.get(ctx))
            // Note: never close() the manager, it destroys the private key; disconnect() instead.
            try {
                val it = adb
                connectAdb(ctx, it)
                _busy.value = "Starting the controller helper…"
                Store.helperPort = port; Store.helperToken = token
                val out = runShell(it, launchCommand(ctx, port, token))
                Log.i(TAG, "launch output: [${out.trim()}]")
                if (!hasSecureSettings(ctx)) {
                    // Lets GamepadMan turn Wireless debugging on by itself after a reboot.
                    runShell(it, "pm grant ${ctx.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
                }
            } finally {
                runCatching { adb.disconnect() }
            }
            Store.helperMode = Store.MODE_ADB
            if (!waitForHelper(5000)) throw IllegalStateException("The helper did not start")
            _helperAlive.value = true
            if (Store.autoDisableWirelessDebugging && hasSecureSettings(ctx) && supportsWirelessDebugging()) {
                runCatching { Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 0) }
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "launch failed", t)
            Result.failure(if (t is NeedsPairing) t else Exception(t.message ?: t.javaClass.simpleName, t))
        } finally {
            _busy.value = null
        }
    }

    private fun connectAdb(ctx: Context, adb: LocalAdb) {
        if (!supportsWirelessDebugging()) {
            _busy.value = "Connecting to ADB (port 5555)…"
            if (!adb.connect("127.0.0.1", 5555)) throw IllegalStateException("Could not connect to ADB on port 5555")
            return
        }
        if (wirelessDebuggingOn(ctx) != true) {
            if (!hasSecureSettings(ctx)) throw IllegalStateException("Turn on Wireless debugging first")
            if (!onWifi(ctx)) throw IllegalStateException("Connect to a Wi-Fi network first (Wireless debugging needs Wi-Fi)")
            _busy.value = "Turning on Wireless debugging…"
            Settings.Global.putInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1)
            Settings.Global.putInt(ctx.contentResolver, "adb_wifi_enabled", 1)
            Thread.sleep(1500)
        }
        _busy.value = "Connecting to Wireless debugging…"
        try {
            val port = discoverPort(ctx, AdbMdns.SERVICE_TYPE_TLS_CONNECT, 15_000)
            if (port <= 0) throw IllegalStateException("Wireless debugging not found on this network")
            val host = connectHost ?: "127.0.0.1"
            val ok = try {
                adb.connect(host, port)
            } catch (e: java.net.ConnectException) {
                if (host == "127.0.0.1") throw e
                adb.connect("127.0.0.1", port)
            }
            if (!ok && !adb.isConnected) throw IllegalStateException("Could not connect to Wireless debugging")
        } catch (e: AdbPairingRequiredException) {
            Store.adbPaired = false
            throw NeedsPairing("This phone needs to be paired again")
        }
        Store.adbPaired = true
    }

    private fun runShell(adb: LocalAdb, cmd: String): String {
        // "exec:" has no PTY, so background processes are not hung up when the session ends.
        val stream = adb.openStream("exec:$cmd")
        val out = java.io.ByteArrayOutputStream()
        try {
            val input = stream.openInputStream()
            val buf = ByteArray(4096)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (e: java.io.IOException) {
            // libadb reports the end of a finished shell command as "Stream closed".
            if (e.message?.contains("closed", ignoreCase = true) != true) throw e
        } finally {
            runCatching { stream.close() }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun launchWithRoot(ctx: Context): Result<Unit> {
        _busy.value = "Starting helper with root…"
        return try {
            val port = freePort()
            val token = newToken()
            Store.helperPort = port; Store.helperToken = token
            val p = ProcessBuilder("su", "-c", launchCommand(ctx, port, token)).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor(15, TimeUnit.SECONDS)
            if (!waitForHelper(5000)) throw IllegalStateException("Root access was denied or the helper did not start")
            Store.helperMode = Store.MODE_ROOT
            _helperAlive.value = true
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(Exception(t.message ?: "Root start failed", t))
        } finally {
            _busy.value = null
        }
    }

    /** Stops the helper process entirely. */
    fun stopHelper() {
        HelperClient.connect(Store.helperPort, Store.helperToken, 600)?.exitHelper()
        _helperAlive.value = false
    }
}
