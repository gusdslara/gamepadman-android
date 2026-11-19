package com.gamepadman.helper

import com.gamepadman.core.HidReports
import com.gamepadman.core.Identity
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** App-side connection to [HelperMain]. Thread-safe; writes are tiny and non-blocking in practice. */
class HelperClient private constructor(private val socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64))
    @Volatile var closed = false; private set

    companion object {
        /** Returns a connected, authenticated client or null if no helper answers. */
        fun connect(port: Int, token: String, timeoutMs: Int = 800): HelperClient? {
            if (port <= 0 || token.isEmpty()) return null
            val s = Socket()
            return try {
                s.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), timeoutMs)
                s.tcpNoDelay = true
                s.soTimeout = timeoutMs
                val c = HelperClient(s)
                c.out.write(token.toByteArray(Charsets.US_ASCII)); c.out.flush()
                val proto = c.input.readInt()
                if (proto != HelperMain.PROTOCOL) { c.close(); return null }
                s.soTimeout = 4000
                c
            } catch (_: Throwable) {
                runCatching { s.close() }
                null
            }
        }
    }

    @Synchronized
    @Throws(IOException::class)
    fun open(id: Identity): Int {
        out.write(HelperMain.OP_OPEN)
        out.writeInt(id.style.ordinal); out.writeInt(id.vendor); out.writeInt(id.product); out.writeInt(id.version)
        out.writeUTF(id.deviceName)
        out.flush()
        return input.readInt()
    }

    @Synchronized
    @Throws(IOException::class)
    fun send(report: ByteArray) {
        out.write(HelperMain.OP_INPUT)
        out.write(report, 0, HidReports.LEN)
        out.flush()
    }

    @Synchronized
    fun ping(): Boolean = try {
        out.write(HelperMain.OP_PING); out.flush(); input.readInt() == 0
    } catch (_: Throwable) {
        false
    }

    @Synchronized
    fun diag(): String = try {
        out.write(HelperMain.OP_DIAG); out.flush(); input.readUTF()
    } catch (t: Throwable) {
        t.toString()
    }

    /** Asks the helper process to quit (used before starting a newer one). */
    @Synchronized
    fun exitHelper() {
        runCatching { out.write(HelperMain.OP_EXIT); out.flush(); input.readInt() }
        close()
    }

    fun close() {
        closed = true
        runCatching { socket.close() }
    }
}
