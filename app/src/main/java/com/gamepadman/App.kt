package com.gamepadman

import android.app.Application
import com.gamepadman.layout.Store
import com.gamepadman.uhid.ShizukuBridge
import rikka.sui.Sui

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        runCatching { Sui.init(packageName) } // root users with Sui don't need the Shizuku app
        ShizukuBridge.init(this)
        com.gamepadman.core.VirtualPad.init(this)
    }
}
