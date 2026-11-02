package com.tamilarasu.watch2iphone.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tamilarasu.watch2iphone.BleLog

/**
 * Opens the watch's own GATT server. In advertise mode the iPhone connects to the watch; the server callback is how
 * the app learns about that connection (and, later, it hosts the custom health-sync service).
 */
@SuppressLint("MissingPermission")
class GattServerHost(
    private val context: Context,
    private val listener: Listener,
    private val delegate: BluetoothGattServerCallback? = null,
) {
    interface Listener {
        fun onDeviceConnected(device: BluetoothDevice)
        fun onDeviceDisconnected(device: BluetoothDevice)
    }

    private val handler = Handler(Looper.getMainLooper())
    var server: BluetoothGattServer? = null
        private set

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            BleLog.ble("server: ${device.address} status=$status newState=$newState")
            handler.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED ->
                        if (status == BluetoothGatt.GATT_SUCCESS) listener.onDeviceConnected(device)
                    BluetoothProfile.STATE_DISCONNECTED -> listener.onDeviceDisconnected(device)
                }
            }
            delegate?.onConnectionStateChange(device, status, newState)
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            BleLog.ble("server: service ${service.uuid} added status=$status")
            delegate?.onServiceAdded(status, service)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
        ) {
            delegate?.onCharacteristicReadRequest(device, requestId, offset, characteristic)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: android.bluetooth.BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            delegate?.onCharacteristicWriteRequest(
                device, requestId, characteristic, preparedWrite, responseNeeded, offset, value,
            )
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
        ) {
            delegate?.onDescriptorReadRequest(device, requestId, offset, descriptor)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            delegate?.onDescriptorWriteRequest(
                device, requestId, descriptor, preparedWrite, responseNeeded, offset, value,
            )
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            delegate?.onNotificationSent(device, status)
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            BleLog.ble("server: ${device.address} mtu=$mtu")
            delegate?.onMtuChanged(device, mtu)
        }
    }

    fun open(): Boolean {
        if (server != null) return true
        val manager = context.getSystemService(BluetoothManager::class.java)
        server = manager?.openGattServer(context, callback)
        BleLog.ble("openGattServer -> ${if (server != null) "ok" else "null"}")
        return server != null
    }

    fun addService(service: BluetoothGattService): Boolean {
        val added = server?.addService(service) ?: false
        BleLog.ble("addService ${service.uuid} requested=$added")
        return added
    }

    fun close() {
        server?.let {
            BleLog.ble("closing GATT server")
            it.close()
        }
        server = null
    }
}
