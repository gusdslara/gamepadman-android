package com.gamepadman.layout

import android.content.Context
import android.content.SharedPreferences
import com.gamepadman.core.Identity
import org.json.JSONArray
import java.util.concurrent.CopyOnWriteArrayList

/** Persistent profiles and app settings (single process, main thread). */
object Store {
    private lateinit var prefs: SharedPreferences
    private val profiles = mutableListOf<PadProfile>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("gamepadman", Context.MODE_PRIVATE)
        load()
    }

    private fun load() {
        profiles.clear()
        runCatching {
            val arr = JSONArray(prefs.getString("profiles", "[]"))
            for (i in 0 until arr.length()) profiles.add(PadProfile.fromJson(arr.getJSONObject(i)))
        }
        if (profiles.isEmpty()) profiles.add(PadProfile(PadProfile.DEFAULT_NAME))
    }

    private fun persist() {
        val arr = JSONArray().apply { profiles.forEach { put(it.toJson()) } }
        prefs.edit().putString("profiles", arr.toString()).apply()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private fun notifyChanged() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) listeners.forEach { runCatching { it() } }
        else main.post { listeners.forEach { runCatching { it() } } }
    }

    // ---- profiles ----
    fun profileNames(): List<String> = profiles.map { it.name }

    /** Returns a detached copy of the active profile (safe to edit; call [saveProfile] to commit). */
    fun active(): PadProfile {
        val name = prefs.getString("active", null)
        return (profiles.firstOrNull { it.name == name } ?: profiles.first()).copy()
    }

    fun activeName(): String = active().name

    fun setActive(name: String) {
        if (profiles.any { it.name == name }) {
            prefs.edit().putString("active", name).apply(); notifyChanged()
        }
    }

    fun saveProfile(p: PadProfile) {
        val i = profiles.indexOfFirst { it.name == p.name }
        if (i >= 0) profiles[i] = p.copy() else profiles.add(p.copy())
        persist(); notifyChanged()
    }

    fun uniqueName(base: String): String {
        if (profiles.none { it.name == base }) return base
        var n = 2
        while (profiles.any { it.name == "$base $n" }) n++
        return "$base $n"
    }

    fun createProfile(name: String, from: PadProfile? = null): String {
        val unique = uniqueName(name.trim().ifEmpty { "Profile" })
        val p = from?.copy(unique) ?: PadProfile(unique)
        profiles.add(p); persist()
        setActive(unique)
        return unique
    }

    fun renameProfile(old: String, new: String): Boolean {
        val n = new.trim()
        if (n.isEmpty() || profiles.any { it.name == n }) return false
        val p = profiles.firstOrNull { it.name == old } ?: return false
        p.name = n
        if (prefs.getString("active", null) == old) prefs.edit().putString("active", n).apply()
        persist(); notifyChanged()
        return true
    }

    fun deleteProfile(name: String) {
        if (profiles.size <= 1) return
        profiles.removeAll { it.name == name }
        if (prefs.getString("active", null) == name) prefs.edit().putString("active", profiles.first().name).apply()
        persist(); notifyChanged()
    }

    // ---- settings ----
    var identity: Identity
        get() = Identity.of(prefs.getString("identity", null))
        set(v) { prefs.edit().putString("identity", v.name).apply(); notifyChanged() }

    var btName: String
        get() = prefs.getString("btName", "Xbox Wireless Controller") ?: "Xbox Wireless Controller"
        set(v) { prefs.edit().putString("btName", v).apply() }

    var btLastHost: String?
        get() = prefs.getString("btHost", null)
        set(v) { prefs.edit().putString("btHost", v).apply() }

    var showBubble: Boolean
        get() = prefs.getBoolean("bubble", true)
        set(v) { prefs.edit().putBoolean("bubble", v).apply(); notifyChanged() }

    var overlayWanted: Boolean
        get() = prefs.getBoolean("overlayWanted", false)
        set(v) { prefs.edit().putBoolean("overlayWanted", v).apply() }

    // ---- built-in activation ----
    const val MODE_ADB = "adb"
    const val MODE_ROOT = "root"

    var helperMode: String
        get() = prefs.getString("helperMode", MODE_ADB) ?: MODE_ADB
        set(v) { prefs.edit().putString("helperMode", v).apply() }

    var helperPort: Int
        get() = prefs.getInt("helperPort", 0)
        set(v) { prefs.edit().putInt("helperPort", v).commit() }

    var helperToken: String
        get() = prefs.getString("helperToken", "") ?: ""
        set(v) { prefs.edit().putString("helperToken", v).commit() }

    var adbPaired: Boolean
        get() = prefs.getBoolean("adbPaired", false)
        set(v) { prefs.edit().putBoolean("adbPaired", v).apply(); notifyChanged() }

    var autoDisableWirelessDebugging: Boolean
        get() = prefs.getBoolean("autoOffWd", false)
        set(v) { prefs.edit().putBoolean("autoOffWd", v).apply() }

    var bubbleX: Int
        get() = prefs.getInt("bubbleX", 24)
        set(v) { prefs.edit().putInt("bubbleX", v).apply() }

    var bubbleY: Int
        get() = prefs.getInt("bubbleY", 200)
        set(v) { prefs.edit().putInt("bubbleY", v).apply() }
}
