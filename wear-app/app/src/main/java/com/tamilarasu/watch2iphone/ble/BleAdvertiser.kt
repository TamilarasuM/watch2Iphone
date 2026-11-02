package com.tamilarasu.watch2iphone.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.os.ParcelUuid
import com.tamilarasu.watch2iphone.BleLog
import com.tamilarasu.watch2iphone.ancs.AncsUuids
import java.util.UUID

/**
 * Connectable advertising that carries the ANCS UUID in the *service solicitation* list
 * (AdvertiseData.Builder.addServiceSolicitationUuid, API 31+). The device name and any extra service UUIDs go in the
 * scan response because the 31-byte advertising packet is nearly full with a 128-bit solicitation UUID.
 */
@SuppressLint("MissingPermission")
class BleAdvertiser(
    private val adapter: BluetoothAdapter,
    private val extraServiceUuids: List<UUID> = emptyList(),
) {
    private var callback: AdvertiseCallback? = null

    fun start(onResult: (Boolean, String) -> Unit) {
        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            onResult(false, "BLE advertising is not available (Bluetooth off or unsupported)")
            return
        }
        stop()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceSolicitationUuid(ParcelUuid(AncsUuids.SERVICE))
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .apply { extraServiceUuids.forEach { addServiceUuid(ParcelUuid(it)) } }
            .build()

        val newCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                BleLog.ble("advertising started (solicited ANCS ${AncsUuids.SERVICE}), settings=$settingsInEffect")
                onResult(true, "advertising")
            }

            override fun onStartFailure(errorCode: Int) {
                val reason = when (errorCode) {
                    ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
                    ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
                    ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
                    ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
                    ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                    else -> "error $errorCode"
                }
                BleLog.ble("advertising failed: $reason")
                onResult(false, "advertising failed: $reason")
            }
        }
        callback = newCallback
        advertiser.startAdvertising(settings, data, scanResponse, newCallback)
        BleLog.ble("startAdvertising requested")
    }

    fun stop() {
        val active = callback ?: return
        callback = null
        adapter.bluetoothLeAdvertiser?.stopAdvertising(active)
        BleLog.ble("advertising stopped")
    }
}
