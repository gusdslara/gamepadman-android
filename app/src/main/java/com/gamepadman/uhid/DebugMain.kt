package com.gamepadman.uhid

import com.gamepadman.core.Btn
import com.gamepadman.core.Dpad
import com.gamepadman.core.HidReports
import com.gamepadman.core.Identity
import com.gamepadman.core.PadState

/**
 * Self test without Shizuku (run from `adb shell`):
 *   CLASSPATH=<path of base.apk> app_process /system/bin com.gamepadman.uhid.DebugMain [seconds] [XBOX_360|XBOX_ONE|GENERIC]
 * Creates the virtual controller and cycles through every input.
 */
object DebugMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val seconds = args.firstOrNull()?.toIntOrNull() ?: 20
        val id = Identity.of(args.getOrNull(1))
        val dev = UhidDevice()
        val rc = dev.create(id.deviceName, id.vendor, id.product, id.version, UhidDevice.BUS_USB, HidReports.descriptor(id.style))
        println("create rc=$rc err=${dev.lastError}")
        if (rc != 0) return
        val s = PadState()
        val out = ByteArray(HidReports.LEN)
        val buttons = intArrayOf(Btn.A, Btn.B, Btn.X, Btn.Y, Btn.LB, Btn.RB, Btn.BACK, Btn.START, Btn.GUIDE, Btn.L3, Btn.R3)
        val end = System.currentTimeMillis() + seconds * 1000L
        var step = 0
        Thread.sleep(800)
        while (System.currentTimeMillis() < end) {
            s.clear()
            val phase = step % 16
            when {
                phase <= 10 -> s.buttons = 1 shl buttons[phase]
                phase == 11 -> { s.lx = 1f; s.ly = -1f }
                phase == 12 -> { s.rx = -1f; s.ry = 1f }
                phase == 13 -> s.lt = 1f
                phase == 14 -> s.rt = 1f
                else -> s.dpad = Dpad.UP or Dpad.RIGHT
            }
            HidReports.encode(s, out); dev.input(out)
            Thread.sleep(250)
            s.clear(); HidReports.encode(s, out); dev.input(out)
            Thread.sleep(150)
            step++
        }
        println("started=${dev.started} opened=${dev.opened} err=${dev.lastError}")
        dev.destroy()
    }
}
