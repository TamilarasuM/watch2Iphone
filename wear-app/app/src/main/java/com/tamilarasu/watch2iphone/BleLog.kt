package com.tamilarasu.watch2iphone

import android.util.Log

/**
 * Logcat tags for the whole app. Filter everything with: adb logcat -s W2I.Perm W2I.Ble W2I.Svc W2I.Ancs
 */
object BleLog {
    const val TAG_PERM = "W2I.Perm"
    const val TAG_BLE = "W2I.Ble"
    const val TAG_SVC = "W2I.Svc"
    const val TAG_ANCS = "W2I.Ancs"

    fun perm(message: String) {
        Log.i(TAG_PERM, message)
    }

    fun ble(message: String) {
        Log.i(TAG_BLE, message)
    }

    fun svc(message: String) {
        Log.i(TAG_SVC, message)
    }

    fun ancs(message: String) {
        Log.i(TAG_ANCS, message)
    }

    /** Hex of at most [limit] leading bytes — never the whole payload, so notification text stays out of the log. */
    fun hex(bytes: ByteArray, limit: Int = 16): String {
        val head = bytes.take(limit).joinToString(" ") { "%02X".format(it) }
        return if (bytes.size > limit) "$head …(${bytes.size}B)" else head
    }
}
