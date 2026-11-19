package com.gamepadman.core

import kotlin.math.roundToInt

/**
 * XPAD: right stick on Rx/Ry, triggers on Z/Rz. With Microsoft VID/PID Android picks the stock
 * Vendor_045e_Product_028e/02ea.kl key layouts, exactly like a real wired Xbox 360/One pad.
 * STANDARD: right stick on Z/Rz, triggers on Brake/Accelerator. Works with Android's Generic.kl
 * and with PCs/TVs when used over Bluetooth.
 */
enum class DescriptorStyle { XPAD, STANDARD }

enum class Identity(
    val label: String,
    val deviceName: String,
    val vendor: Int,
    val product: Int,
    val version: Int,
    val style: DescriptorStyle,
) {
    XBOX_360("Xbox 360 Controller", "Microsoft X-Box 360 pad", 0x045E, 0x028E, 0x0114, DescriptorStyle.XPAD),
    XBOX_ONE("Xbox One Controller", "Microsoft X-Box One S pad", 0x045E, 0x02EA, 0x0408, DescriptorStyle.XPAD),
    GENERIC("Generic HID Gamepad", "GamepadMan Virtual Gamepad", 0x1209, 0x5042, 0x0100, DescriptorStyle.STANDARD);

    companion object {
        fun of(name: String?): Identity = entries.firstOrNull { it.name == name } ?: XBOX_360
    }
}

object HidReports {
    /** Report payload length (without report id). */
    const val LEN = 13

    const val BT_REPORT_ID = 1

    fun descriptor(style: DescriptorStyle, reportId: Int = 0): ByteArray {
        val d = ArrayList<Int>(128)
        fun add(vararg b: Int) { b.forEach { d.add(it) } }
        add(0x05, 0x01)             // Usage Page (Generic Desktop)
        add(0x09, 0x05)             // Usage (Game Pad)
        add(0xA1, 0x01)             // Collection (Application)
        if (reportId != 0) add(0x85, reportId) //   Report ID
        add(0x09, 0x01)             //   Usage (Pointer)
        add(0xA1, 0x00)             //   Collection (Physical)
        add(0x09, 0x30, 0x09, 0x31) //     X, Y  (left stick)
        if (style == DescriptorStyle.XPAD) add(0x09, 0x33, 0x09, 0x34) // Rx, Ry
        else add(0x09, 0x32, 0x09, 0x35)                                // Z, Rz
        add(0x16, 0x01, 0x80)       //     Logical Min (-32767)
        add(0x26, 0xFF, 0x7F)       //     Logical Max (32767)
        add(0x75, 0x10, 0x95, 0x04) //     Report Size 16, Count 4
        add(0x81, 0x02)             //     Input (Data,Var,Abs)
        add(0xC0)                   //   End Collection
        if (style == DescriptorStyle.XPAD) {
            add(0x05, 0x01, 0x09, 0x32, 0x09, 0x35) // Z (LT), Rz (RT)
        } else {
            add(0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4) // Simulation: Brake (LT), Accelerator (RT)
        }
        add(0x15, 0x00, 0x26, 0xFF, 0x00)    // Logical 0..255
        add(0x75, 0x08, 0x95, 0x02, 0x81, 0x02)
        add(0x05, 0x01, 0x09, 0x39)          // Hat switch
        add(0x15, 0x00, 0x25, 0x07)          // Logical 0..7
        add(0x35, 0x00, 0x46, 0x3B, 0x01)    // Physical 0..315
        add(0x65, 0x14)                      // Unit (degrees)
        add(0x75, 0x04, 0x95, 0x01, 0x81, 0x42) // Input (Data,Var,Abs,Null)
        add(0x65, 0x00, 0x45, 0x00)          // Unit none, Physical Max 0
        add(0x75, 0x04, 0x95, 0x01, 0x81, 0x03) // 4 bit padding
        add(0x05, 0x09, 0x19, 0x01, 0x29, 0x0F) // Buttons 1..15
        add(0x15, 0x00, 0x25, 0x01)
        add(0x75, 0x01, 0x95, 0x0F, 0x81, 0x02)
        add(0x75, 0x01, 0x95, 0x01, 0x81, 0x03) // 1 bit padding
        add(0xC0)                           // End Collection
        return ByteArray(d.size) { d[it].toByte() }
    }

    private fun axis(v: Float): Int = (v.coerceIn(-1f, 1f) * 32767f).roundToInt()
    private fun trig(v: Float): Int = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    fun hat(dpad: Int): Int {
        val up = dpad and Dpad.UP != 0
        val down = dpad and Dpad.DOWN != 0
        val left = dpad and Dpad.LEFT != 0
        val right = dpad and Dpad.RIGHT != 0
        return when {
            up && right -> 1
            down && right -> 3
            down && left -> 5
            up && left -> 7
            up -> 0
            right -> 2
            down -> 4
            left -> 6
            else -> 8 // null state = centered
        }
    }

    /** Encodes the state into [out] (at least [LEN] bytes, little endian). */
    fun encode(s: PadState, out: ByteArray) {
        fun put16(i: Int, v: Int) {
            out[i] = (v and 0xFF).toByte(); out[i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        put16(0, axis(s.lx))
        put16(2, axis(s.ly))
        put16(4, axis(s.rx))
        put16(6, axis(s.ry))
        out[8] = trig(s.lt).toByte()
        out[9] = trig(s.rt).toByte()
        out[10] = (hat(s.dpad) and 0x0F).toByte()
        put16(11, s.buttons and 0x7FFF)
    }
}
