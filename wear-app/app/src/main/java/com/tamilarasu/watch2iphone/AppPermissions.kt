package com.tamilarasu.watch2iphone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Runtime permissions the app needs. minSdk is 33, so all of these exist on every supported device. */
object AppPermissions {
    val required: List<String> = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context): List<String> = required.filterNot { isGranted(context, it) }

    fun label(permission: String): String = permission.substringAfterLast('.')
}
