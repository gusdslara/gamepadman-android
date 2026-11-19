package com.gamepadman.core

/** Bit index of each button inside the HID report (HID button N -> bit N-1 -> Linux BTN_GAMEPAD+N-1). */
object Btn {
    const val A = 0       // HID button 1  -> BTN_A
    const val B = 1       // HID button 2  -> BTN_B
    const val X = 3       // HID button 4  -> BTN_X
    const val Y = 4       // HID button 5  -> BTN_Y
    const val LB = 6      // HID button 7  -> BTN_TL
    const val RB = 7      // HID button 8  -> BTN_TR
    const val BACK = 10   // HID button 11 -> BTN_SELECT
    const val START = 11  // HID button 12 -> BTN_START
    const val GUIDE = 12  // HID button 13 -> BTN_MODE
    const val L3 = 13     // HID button 14 -> BTN_THUMBL
    const val R3 = 14     // HID button 15 -> BTN_THUMBR
}

object Dpad {
    const val UP = 1
    const val DOWN = 2
    const val LEFT = 4
    const val RIGHT = 8
}

/** Complete logical state of the emulated controller. Sticks are -1..1 (Y down = +1), triggers 0..1. */
class PadState {
    var buttons = 0
    var dpad = 0
    var lx = 0f
    var ly = 0f
    var rx = 0f
    var ry = 0f
    var lt = 0f
    var rt = 0f

    fun clear() {
        buttons = 0; dpad = 0
        lx = 0f; ly = 0f; rx = 0f; ry = 0f; lt = 0f; rt = 0f
    }
}
