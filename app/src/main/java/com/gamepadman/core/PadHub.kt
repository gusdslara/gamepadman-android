package com.gamepadman.core

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * Delivers encoded reports to an output (virtual uhid device or Bluetooth host) on a dedicated
 * thread, so the UI/touch thread never blocks on IPC. Only state changes are sent; consecutive
 * stick-only updates are coalesced, but button/trigger/d-pad transitions are never dropped.
 */
class ReportChannel(private val handler: Handler) {
    fun interface Sink { fun send(report: ByteArray) }

    @Volatile private var sink: Sink? = null
    private val lock = Any()
    private val queue = ArrayDeque<ByteArray>()
    private var scheduled = false
    private val last = ByteArray(HidReports.LEN)
    private val scratch = ByteArray(HidReports.LEN)

    private val drain = Runnable {
        while (true) {
            val r = synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) scheduled = false
                next
            } ?: break
            val s = sink ?: continue
            try { s.send(r) } catch (_: Throwable) { }
        }
    }

    fun setSink(s: Sink?) {
        sink = s
        if (s != null) synchronized(lock) { enqueue(last.copyOf()) } // sync current state to new output
    }

    val hasSink get() = sink != null

    /** Call from any thread whenever the controller state may have changed. */
    fun publish(state: PadState) = synchronized(lock) {
        HidReports.encode(state, scratch)
        if (scratch.contentEquals(last)) return@synchronized
        scratch.copyInto(last)
        enqueue(scratch.copyOf())
    }

    fun neutral() = publish(PadState())

    private fun enqueue(r: ByteArray) {
        val tail = queue.lastOrNull()
        if (tail != null && onlyAxesDiffer(tail, r)) {
            queue[queue.size - 1] = r
        } else {
            if (queue.size >= 128) queue.removeFirst()
            queue.addLast(r)
        }
        if (!scheduled) {
            scheduled = true
            handler.post(drain)
        }
    }

    private fun onlyAxesDiffer(a: ByteArray, b: ByteArray): Boolean {
        for (i in 8 until HidReports.LEN) if (a[i] != b[i]) return false
        return true
    }

    fun currentReport(): ByteArray = synchronized(lock) { last.copyOf() }
}

object PadHub {
    private val thread = HandlerThread("pad-output", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    /** Virtual controller on this phone (overlay mode). */
    val local = ReportChannel(handler)

    /** Bluetooth HID host (phone-as-controller mode). */
    val bluetooth = ReportChannel(handler)
}
