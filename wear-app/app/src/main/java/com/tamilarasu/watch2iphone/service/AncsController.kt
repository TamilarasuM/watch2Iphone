package com.tamilarasu.watch2iphone.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tamilarasu.watch2iphone.AncsStatus
import com.tamilarasu.watch2iphone.BleLog
import com.tamilarasu.watch2iphone.ConnectMode
import com.tamilarasu.watch2iphone.LinkPhase
import com.tamilarasu.watch2iphone.Prefs
import com.tamilarasu.watch2iphone.UiState
import com.tamilarasu.watch2iphone.ancs.AncsClient
import com.tamilarasu.watch2iphone.ancs.AncsNotification
import com.tamilarasu.watch2iphone.ble.BleAdvertiser
import com.tamilarasu.watch2iphone.ble.BleScanner
import com.tamilarasu.watch2iphone.ble.GattServerHost
import com.tamilarasu.watch2iphone.notify.NotificationPoster

/**
 * Owns the whole Phase 1 pipeline: discovery (scan or advertise), the ANCS client, notification posting and the
 * reconnect policy. Runs on the main thread inside [AncsService].
 */
@SuppressLint("MissingPermission")
class AncsController(
    private val context: Context,
    private val onUiState: (UiState) -> Unit,
) : AncsClient.Callbacks, GattServerHost.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = Prefs(context)
    private val poster = NotificationPoster(context)
    private val client = AncsClient(context, this)

    private var scanner: BleScanner? = null
    private var advertiser: BleAdvertiser? = null
    private var serverHost: GattServerHost? = null

    private var mode: ConnectMode? = null
    private var stopped = true
    private var reconnectDelayMs = INITIAL_RECONNECT_MS
    private val reconnectRunnable = Runnable { reconnectNow() }

    private var ui = UiState(savedDevice = prefs.savedName ?: prefs.savedAddress)

    init {
        publish(LinkPhase.IDLE, "not connected")
    }

    // ---- commands from the service ----------------------------------------------------------------------------

    fun startScan() {
        val a = readyAdapter() ?: return
        BleLog.svc("startScan")
        stopDiscovery()
        stopped = false
        mode = ConnectMode.SCAN
        AncsStatus.scanResults.value = emptyList()
        val newScanner = BleScanner(
            adapter = a,
            onResults = { AncsStatus.scanResults.value = it },
            onStopped = { reason ->
                if (ui.phase == LinkPhase.SCANNING) publish(LinkPhase.IDLE, reason)
            },
        )
        scanner = newScanner
        if (newScanner.start()) {
            publish(LinkPhase.SCANNING, "looking for your iPhone")
        } else {
            publish(LinkPhase.FAILED, "could not start scanning")
        }
    }

    fun connectTo(address: String, autoConnect: Boolean = false) {
        val a = readyAdapter() ?: return
        BleLog.svc("connectTo $address autoConnect=$autoConnect")
        stopDiscovery()
        stopped = false
        mode = ConnectMode.SCAN
        val device = remoteDevice(a, address) ?: return
        val name = AncsStatus.scanResults.value.firstOrNull { it.address == address }?.name
        prefs.savedAddress = address
        prefs.savedName = name ?: prefs.savedName
        prefs.mode = ConnectMode.SCAN
        ui = ui.copy(deviceName = name ?: prefs.savedName ?: address, savedDevice = prefs.savedName ?: address)
        client.connect(device, autoConnect)
    }

    fun startAdvertising() {
        val a = readyAdapter() ?: return
        BleLog.svc("startAdvertising")
        stopDiscovery()
        stopped = false
        mode = ConnectMode.ADVERTISE
        prefs.mode = ConnectMode.ADVERTISE
        if (serverHost == null) serverHost = GattServerHost(context, this)
        if (serverHost?.open() != true) {
            publish(LinkPhase.FAILED, "could not open the GATT server")
            return
        }
        advertise(a)
    }

    fun resumeSaved() {
        BleLog.svc("resumeSaved mode=${prefs.mode} address=${prefs.savedAddress}")
        when (prefs.mode) {
            ConnectMode.SCAN -> prefs.savedAddress?.let { connectTo(it, autoConnect = true) }
            ConnectMode.ADVERTISE -> startAdvertising()
            null -> Unit
        }
    }

    fun stop() {
        BleLog.svc("stop")
        stopped = true
        handler.removeCallbacks(reconnectRunnable)
        stopDiscovery()
        advertiser?.stop()
        advertiser = null
        serverHost?.close()
        serverHost = null
        client.disconnect()
        publish(LinkPhase.IDLE, "stopped")
    }

    fun forgetSaved() {
        prefs.forget()
        ui = ui.copy(savedDevice = null, deviceName = null)
        publish(ui.phase, ui.detail)
    }

    fun release() {
        stop()
        client.release()
    }

    fun performAction(sessionId: Int, uid: Long, actionId: Int) = client.performAction(sessionId, uid, actionId)

    // ---- AncsClient.Callbacks ---------------------------------------------------------------------------------

    override fun onPhase(phase: LinkPhase, detail: String) {
        if (phase == LinkPhase.READY) {
            reconnectDelayMs = INITIAL_RECONNECT_MS
            handler.removeCallbacks(reconnectRunnable)
        }
        publish(phase, detail)
    }

    override fun onNotification(notification: AncsNotification) = poster.post(notification)

    override fun onNotificationRemoved(uid: Long) = poster.cancel(uid)

    override fun onDisconnected(reason: AncsClient.DisconnectReason) {
        BleLog.svc("client disconnected: $reason (stopped=$stopped, mode=$mode)")
        when (reason) {
            AncsClient.DisconnectReason.USER -> Unit
            AncsClient.DisconnectReason.FATAL -> stopped = true // do not loop on a configuration problem
            AncsClient.DisconnectReason.LINK_LOST -> if (!stopped) scheduleReconnect()
        }
    }

    // ---- GattServerHost.Listener (advertise mode) -------------------------------------------------------------

    override fun onDeviceConnected(device: BluetoothDevice) {
        BleLog.svc("iPhone connected to the watch's GATT server: ${device.address}")
        if (stopped || mode != ConnectMode.ADVERTISE) return
        if (client.phase != LinkPhase.IDLE && client.phase != LinkPhase.FAILED && client.phase != LinkPhase.RECONNECTING) {
            BleLog.svc("client already busy (${client.phase}); not starting another connection")
            return
        }
        prefs.savedAddress = device.address
        prefs.savedName = device.name ?: prefs.savedName
        ui = ui.copy(deviceName = device.name ?: device.address, savedDevice = device.name ?: device.address)
        handler.removeCallbacks(reconnectRunnable)
        client.connect(device, autoConnect = false)
    }

    override fun onDeviceDisconnected(device: BluetoothDevice) {
        BleLog.svc("iPhone disconnected from the watch's GATT server: ${device.address}")
    }

    // ---- internals --------------------------------------------------------------------------------------------

    private fun advertise(a: BluetoothAdapter) {
        advertiser?.stop()
        advertiser = BleAdvertiser(a).also {
            it.start { ok, message ->
                handler.post {
                    if (ok) {
                        publish(LinkPhase.ADVERTISING, "waiting for the iPhone — look for this watch in iPhone Bluetooth settings")
                    } else {
                        publish(LinkPhase.FAILED, message)
                    }
                }
            }
        }
    }

    private fun scheduleReconnect() {
        val delay = reconnectDelayMs
        reconnectDelayMs = (delay * 2).coerceAtMost(MAX_RECONNECT_MS)
        BleLog.svc("reconnect in ${delay}ms (next backoff ${reconnectDelayMs}ms)")
        publish(LinkPhase.RECONNECTING, "reconnecting in ${delay / 1000}s")
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delay)
    }

    private fun reconnectNow() {
        if (stopped) return
        val a = adapter ?: return
        when (mode) {
            ConnectMode.SCAN -> {
                val address = prefs.savedAddress ?: return
                val device = remoteDevice(a, address) ?: return
                client.connect(device, autoConnect = true)
            }
            ConnectMode.ADVERTISE -> advertise(a)
            null -> Unit
        }
    }

    private fun stopDiscovery() {
        scanner?.stop("stopped")
        scanner = null
    }

    private fun readyAdapter(): BluetoothAdapter? {
        val a = adapter
        if (a == null) {
            publish(LinkPhase.FAILED, "this watch has no Bluetooth adapter")
            return null
        }
        if (!a.isEnabled) {
            publish(LinkPhase.FAILED, "Bluetooth is turned off")
            return null
        }
        return a
    }

    private fun remoteDevice(a: BluetoothAdapter, address: String): BluetoothDevice? =
        try {
            a.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            publish(LinkPhase.FAILED, "invalid device address")
            null
        }

    private fun publish(phase: LinkPhase, detail: String) {
        ui = ui.copy(phase = phase, detail = detail)
        AncsStatus.ui.value = ui
        onUiState(ui)
    }

    private companion object {
        const val INITIAL_RECONNECT_MS = 1_000L
        const val MAX_RECONNECT_MS = 60_000L
    }
}
