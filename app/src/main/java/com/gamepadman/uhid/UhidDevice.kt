package com.gamepadman.uhid

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal /dev/uhid client written with plain read()/write() (no ioctl, no native code).
 * Must run in a process allowed to open /dev/uhid (shell via Shizuku/ADB, or root).
 */
class UhidDevice {
    companion object {
        private const val UHID_DESTROY = 1
        private const val UHID_START = 2
        private const val UHID_STOP = 3
        private const val UHID_OPEN = 4
        private const val UHID_CLOSE = 5
        private const val UHID_GET_REPORT = 9
        private const val UHID_GET_REPORT_REPLY = 10
        private const val UHID_CREATE2 = 11
        private const val UHID_INPUT2 = 12
        private const val UHID_SET_REPORT = 13
        private const val UHID_SET_REPORT_REPLY = 14

        private const val EVENT_SIZE = 4380 // >= sizeof(struct uhid_event)
        const val BUS_USB = 0x03
    }

    private val lock = Any()
    private var fd: FileDescriptor? = null
    @Volatile private var running = false
    @Volatile var started = false; private set
    @Volatile var opened = false; private set
    @Volatile var lastError: String = ""; private set
    private val inputBuf = ByteArray(6 + 64)

    val isCreated: Boolean get() = fd != null

    /** Returns 0 on success, otherwise errno. */
    fun create(name: String, vendor: Int, product: Int, version: Int, bus: Int, descriptor: ByteArray): Int = synchronized(lock) {
        destroyLocked()
        val f = try {
            Os.open("/dev/uhid", OsConstants.O_RDWR or OsConstants.O_CLOEXEC, 0)
        } catch (e: ErrnoException) {
            lastError = "open /dev/uhid: ${e.message}"
            return e.errno
        }
        val ev = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        ev.putInt(0, UHID_CREATE2)
        putCString(ev, 4, name, 128)
        putCString(ev, 132, "gamepadman/virtual0", 64)
        putCString(ev, 196, "gamepadman-0001", 64)
        ev.putShort(260, descriptor.size.toShort())
        ev.putShort(262, bus.toShort())
        ev.putInt(264, vendor)
        ev.putInt(268, product)
        ev.putInt(272, version)
        ev.putInt(276, 0)
        System.arraycopy(descriptor, 0, ev.array(), 280, descriptor.size)
        try {
            Os.write(f, ev.array(), 0, EVENT_SIZE)
        } catch (e: ErrnoException) {
            lastError = "UHID_CREATE2: ${e.message}"
            runCatching { Os.close(f) }
            return e.errno
        }
        fd = f
        running = true
        started = false
        opened = false
        lastError = ""
        Thread({ readLoop(f) }, "uhid-reader").apply { isDaemon = true; start() }
        0
    }

    /** Sends one input report (payload without report id). */
    fun input(report: ByteArray) {
        synchronized(lock) {
            val f = fd ?: return
            val n = report.size.coerceAtMost(inputBuf.size - 6)
            putIntLE(inputBuf, 0, UHID_INPUT2)
            inputBuf[4] = (n and 0xFF).toByte()
            inputBuf[5] = ((n shr 8) and 0xFF).toByte()
            System.arraycopy(report, 0, inputBuf, 6, n)
            try {
                Os.write(f, inputBuf, 0, 6 + n)
            } catch (e: ErrnoException) {
                lastError = "UHID_INPUT2: ${e.message}"
            }
        }
    }

    fun destroy() = synchronized(lock) { destroyLocked() }

    private fun destroyLocked() {
        val f = fd ?: return
        running = false
        try {
            val b = ByteArray(4)
            putIntLE(b, 0, UHID_DESTROY)
            Os.write(f, b, 0, 4)
        } catch (_: ErrnoException) {
        }
        runCatching { Os.close(f) }
        fd = null
        started = false
        opened = false
    }

    private fun readLoop(f: FileDescriptor) {
        val buf = ByteArray(EVENT_SIZE)
        val pfd = StructPollfd().apply { this.fd = f; events = OsConstants.POLLIN.toShort() }
        while (running && fd === f) {
            try {
                pfd.revents = 0
                if (Os.poll(arrayOf(pfd), 500) <= 0) continue
                if (fd !== f) break
                val n = Os.read(f, buf, 0, buf.size)
                if (n < 4) continue
                val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
                when (bb.getInt(0)) {
                    UHID_START -> started = true
                    UHID_STOP -> started = false
                    UHID_OPEN -> opened = true
                    UHID_CLOSE -> opened = false
                    UHID_GET_REPORT -> reply(f, UHID_GET_REPORT_REPLY, bb.getInt(4), OsConstants.EIO)
                    UHID_SET_REPORT -> reply(f, UHID_SET_REPORT_REPLY, bb.getInt(4), 0)
                }
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR || e.errno == OsConstants.EAGAIN) continue
                break
            } catch (_: Throwable) {
                break
            }
        }
    }

    private fun reply(f: FileDescriptor, type: Int, id: Int, err: Int) {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, type)
        b.putInt(4, id)
        b.putShort(8, err.toShort())
        b.putShort(10, 0)
        synchronized(lock) {
            if (fd === f) runCatching { Os.write(f, b.array(), 0, 12) }
        }
    }

    private fun putCString(bb: ByteBuffer, off: Int, s: String, max: Int) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val n = bytes.size.coerceAtMost(max - 1)
        System.arraycopy(bytes, 0, bb.array(), off, n)
    }

    private fun putIntLE(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v shr 8).toByte()
        b[off + 2] = (v shr 16).toByte(); b[off + 3] = (v shr 24).toByte()
    }
}
