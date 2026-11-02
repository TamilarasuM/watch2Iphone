package com.tamilarasu.watch2iphone

import android.content.Context

enum class ConnectMode {
    /** The watch scans for the iPhone and connects to it as a GATT client. */
    SCAN,

    /** The watch advertises the ANCS solicitation UUID and the iPhone connects to it. */
    ADVERTISE,
}

class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("watch2iphone", Context.MODE_PRIVATE)

    var savedAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        set(value) = prefs.edit().putString(KEY_ADDRESS, value).apply()

    var savedName: String?
        get() = prefs.getString(KEY_NAME, null)
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    var mode: ConnectMode?
        get() = prefs.getString(KEY_MODE, null)?.let { runCatching { ConnectMode.valueOf(it) }.getOrNull() }
        set(value) = prefs.edit().putString(KEY_MODE, value?.name).apply()

    fun forget() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_NAME = "name"
        const val KEY_MODE = "mode"
    }
}
