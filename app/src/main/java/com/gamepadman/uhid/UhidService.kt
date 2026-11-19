package com.gamepadman.uhid

import android.os.Process
import com.gamepadman.IVirtualPad
import com.gamepadman.core.DescriptorStyle
import com.gamepadman.core.HidReports
import kotlin.system.exitProcess

/** Shizuku user service. Lives in a separate privileged process (uid shell or root). */
class UhidService : IVirtualPad.Stub() {
    private val device = UhidDevice()
    @Volatile private var name = ""

    override fun open(style: Int, vendor: Int, product: Int, version: Int, name: String?): Int {
        val st = DescriptorStyle.entries.getOrElse(style) { DescriptorStyle.XPAD }
        this.name = name ?: "Virtual Gamepad"
        return device.create(this.name, vendor, product, version, UhidDevice.BUS_USB, HidReports.descriptor(st))
    }

    override fun send(report: ByteArray?) {
        if (report != null) device.input(report)
    }

    override fun close() = device.destroy()

    override fun diag(): String =
        "uid=${Process.myUid()} pid=${Process.myPid()} created=${device.isCreated} started=${device.started} " +
            "opened=${device.opened} name=$name err=${device.lastError}"

    override fun destroy() {
        device.destroy()
        exitProcess(0)
    }
}
