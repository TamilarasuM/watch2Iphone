package com.tamilarasu.watch2iphone.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import com.tamilarasu.watch2iphone.BleLog
import com.tamilarasu.watch2iphone.ScanItem

/**
 * Unfiltered LE scan used to answer the open question "does the iPhone show up in a scan at all?". Every new device is
 * logged once with its advertised services and manufacturer ids so the result can be read from Logcat.
 */
@SuppressLint("MissingPermission")
class BleScanner(
    private val adapter: BluetoothAdapter,
    private val onResults: (List<ScanItem>) -> Unit,
    private val onStopped: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val items = LinkedHashMap<String, ScanItem>()
    private var scanning = false
    private var publishPending = false

    private val stopOnTimeout = Runnable { stop("scan finished (${SCAN_TIMEOUT_MS / 1000}s)") }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { add(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            handler.post { results.forEach(::add) }
        }

        override fun onScanFailed(errorCode: Int) {
            BleLog.ble("scan failed errorCode=$errorCode")
            handler.post {
                scanning = false
                handler.removeCallbacks(stopOnTimeout)
                onStopped("scan failed (error $errorCode)")
            }
        }
    }

    fun start(): Boolean {
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            BleLog.ble("no LE scanner available (Bluetooth off?)")
            return false
        }
        items.clear()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        scanning = true
        BleLog.ble("scan started (unfiltered, LOW_LATENCY, timeout ${SCAN_TIMEOUT_MS / 1000}s)")
        handler.postDelayed(stopOnTimeout, SCAN_TIMEOUT_MS)
        return true
    }

    fun stop(reason: String) {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(stopOnTimeout)
        adapter.bluetoothLeScanner?.stopScan(callback)
        BleLog.ble("scan stopped: $reason; ${items.size} device(s) seen")
        onStopped(reason)
    }

    private fun add(result: ScanResult) {
        if (!scanning) return
        val record = result.scanRecord
        val address = result.device.address
        val name = record?.deviceName ?: result.device.name
        val isApple = record?.getManufacturerSpecificData(APPLE_COMPANY_ID) != null
        val services = record?.serviceUuids?.joinToString { it.uuid.toString().take(8) }.orEmpty()
        val solicited = record?.serviceSolicitationUuids?.joinToString { it.uuid.toString().take(8) }.orEmpty()
        val manufacturers = record?.manufacturerSpecificData?.let { data ->
            (0 until data.size()).joinToString { "0x%04X".format(data.keyAt(it)) }
        }.orEmpty()

        val isNew = address !in items
        val detail = buildString {
            append(address.takeLast(8))
            append("  ").append(result.rssi).append(" dBm")
            if (isApple) append("  Apple")
        }
        items[address] = ScanItem(address, name, result.rssi, isApple, detail)
        if (isNew) {
            BleLog.ble(
                "scan: new $address name=${name ?: "-"} rssi=${result.rssi} apple=$isApple " +
                    "connectable=${result.isConnectable} services=[$services] solicited=[$solicited] mfg=[$manufacturers]",
            )
        }
        if (!publishPending) {
            publishPending = true
            handler.postDelayed({
                publishPending = false
                onResults(items.values.sortedByDescending { it.rssi })
            }, PUBLISH_INTERVAL_MS)
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MS = 30_000L
        const val PUBLISH_INTERVAL_MS = 500L

        /** Apple Inc. company identifier (Bluetooth SIG assigned number) — used only as a hint in the device list. */
        const val APPLE_COMPANY_ID = 0x004C
    }
}
