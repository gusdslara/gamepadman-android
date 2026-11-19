package com.gamepadman.helper

import com.gamepadman.core.DescriptorStyle
import com.gamepadman.core.HidReports
import com.gamepadman.uhid.UhidDevice
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Privileged helper started by GamepadMan itself (through its built-in ADB client or root) as:
 *   app_process -Djava.class.path=<apk> /system/bin --nice-name=gpm_helper com.gamepadman.helper.HelperMain <port> <token>
 * Listens on 127.0.0.1 only and requires the secret token. The virtual controller only exists while
 * the app is connected, so a crashed/closed app never leaves a stuck controller behind.
 */
object HelperMain {
    const val PROTOCOL = 1
    const val OP_OPEN = 1
    const val OP_INPUT = 2
    const val OP_CLOSE = 3
    const val OP_PING = 4
    const val OP_EXIT = 5
    const val OP_DIAG = 6

    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toIntOrNull() ?: exitProcess(2)
        val token = args.getOrNull(1)?.toByteArray(Charsets.US_ASCII) ?: exitProcess(2)
        val server = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 4)
            }
        } catch (t: Throwable) {
            System.err.println("bind failed: $t"); exitProcess(3)
        }
        val dev = UhidDevice()
        while (true) {
            val s = try { server.accept() } catch (_: Throwable) { continue }
            try {
                serve(s, dev, token)
            } catch (_: Throwable) {
            } finally {
                dev.destroy()
                runCatching { s.close() }
            }
        }
    }

    private fun serve(s: Socket, dev: UhidDevice, token: ByteArray) {
        s.tcpNoDelay = true
        s.soTimeout = 3000
        val input = DataInputStream(BufferedInputStream(s.getInputStream()))
        val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        val got = ByteArray(token.size)
        input.readFully(got)
        if (!MessageDigest.isEqual(got, token)) return
        out.writeInt(PROTOCOL); out.flush()
        s.soTimeout = 0
        val report = ByteArray(HidReports.LEN)
        while (true) {
            val op = input.read()
            if (op < 0) throw EOFException()
            when (op) {
                OP_INPUT -> { input.readFully(report); dev.input(report) }
                OP_OPEN -> {
                    val style = input.readInt()
                    val vendor = input.readInt()
                    val product = input.readInt()
                    val version = input.readInt()
                    val name = input.readUTF()
                    val st = DescriptorStyle.entries.getOrElse(style) { DescriptorStyle.XPAD }
                    val rc = dev.create(name, vendor, product, version, UhidDevice.BUS_USB, HidReports.descriptor(st))
                    out.writeInt(rc); out.flush()
                }
                OP_CLOSE -> dev.destroy()
                OP_PING -> { out.writeInt(0); out.flush() }
                OP_DIAG -> {
                    out.writeUTF("uid=${android.os.Process.myUid()} created=${dev.isCreated} opened=${dev.opened} err=${dev.lastError}")
                    out.flush()
                }
                OP_EXIT -> { dev.destroy(); out.writeInt(0); out.flush(); exitProcess(0) }
                else -> return
            }
        }
    }
}
