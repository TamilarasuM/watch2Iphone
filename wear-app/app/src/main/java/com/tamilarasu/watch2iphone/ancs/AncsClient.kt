package com.tamilarasu.watch2iphone.ancs

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.tamilarasu.watch2iphone.BleLog
import com.tamilarasu.watch2iphone.LinkPhase

/**
 * GATT client for the iPhone's Apple Notification Center Service.
 *
 * Every GATT callback is re-posted to the main looper, so all state below is touched from one thread. Android
 * allows only one GATT operation at a time, so operations go through [ops] and the next one starts when the
 * callback for the current one arrives (or its watchdog fires).
 */
@SuppressLint("MissingPermission")
class AncsClient(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    enum class DisconnectReason { USER, LINK_LOST, FATAL }

    interface Callbacks {
        fun onPhase(phase: LinkPhase, detail: String)
        fun onNotification(notification: AncsNotification)
        fun onNotificationRemoved(uid: Long)
        fun onDisconnected(reason: DisconnectReason)
    }

    private class GattOp(
        val name: String,
        val fatal: Boolean,
        val onWrite: ((Int) -> Unit)? = null,
        val onStartFailed: (() -> Unit)? = null,
        val start: (BluetoothGatt) -> Boolean,
    )

    private class Request(
        val label: String,
        val payload: ByteArray,
        val expected: ExpectedResponse?,
        val onResponse: ((DataSourceMessage?) -> Unit)?,
    )

    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var userDisconnect = false
    private var fatalReason: String? = null
    private var bondReceiverRegistered = false

    var sessionId = 0
        private set
    var phase = LinkPhase.IDLE
        private set

    private var notificationSource: BluetoothGattCharacteristic? = null
    private var controlPoint: BluetoothGattCharacteristic? = null
    private var dataSource: BluetoothGattCharacteristic? = null

    private val ops = ArrayDeque<GattOp>()
    private var currentOp: GattOp? = null

    private val requests = ArrayDeque<Request>()
    private var inFlight: Request? = null
    private var reassembler: DataSourceReassembler? = null

    private val liveUids = HashSet<Long>()
    private val appNames = HashMap<String, String>()
    private var negotiatedMtu = DEFAULT_MTU

    private val opTimeout = Runnable {
        val op = currentOp ?: return@Runnable
        BleLog.ble("GATT op timed out: ${op.name}")
        if (op.fatal) fail("timed out during ${op.name}") else opDone()
    }

    private val requestTimeout = Runnable {
        BleLog.ancs("request timed out: ${inFlight?.label}")
        finishRequest(null)
    }

    // ---- public API -------------------------------------------------------------------------------------------

    fun connect(target: BluetoothDevice, autoConnect: Boolean) {
        handler.post {
            teardownGatt()
            device = target
            userDisconnect = false
            fatalReason = null
            registerBondReceiver()
            setPhase(LinkPhase.CONNECTING, "connecting to ${describe(target)}")
            BleLog.ble(
                "connectGatt address=${target.address} autoConnect=$autoConnect transport=LE " +
                    "bondState=${bondName(target.bondState)}",
            )
            // The replacement connectGatt(BluetoothGattConnectionSettings, ...) only exists from API 37; the watch runs API 36.
            @Suppress("DEPRECATION")
            gatt = target.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) fail("connectGatt returned null")
        }
    }

    fun disconnect() {
        handler.post {
            userDisconnect = true
            val g = gatt
            if (g == null) {
                setPhase(LinkPhase.IDLE, "disconnected")
                return@post
            }
            BleLog.ble("disconnect requested by user")
            g.disconnect()
            handler.postDelayed({ if (gatt === g) handleDisconnected(g, -1) }, DISCONNECT_FALLBACK_MS)
        }
    }

    fun release() {
        handler.post {
            teardownGatt()
            unregisterBondReceiver()
        }
    }

    /** Ask the iPhone to perform the positive/negative action of a notification (spec: Perform Notification Action). */
    fun performAction(session: Int, uid: Long, actionId: Int) {
        handler.post {
            if (session != sessionId || phase != LinkPhase.READY) {
                BleLog.ancs("ignoring action $actionId for uid=$uid (session $session, current $sessionId, phase $phase)")
                return@post
            }
            BleLog.ancs("performing action $actionId on uid=$uid")
            requests.addLast(
                Request(
                    label = "action uid=$uid action=$actionId",
                    payload = AncsCommands.performNotificationAction(uid, actionId),
                    expected = null,
                    onResponse = null,
                ),
            )
            pumpRequests()
        }
    }

    // ---- GATT callbacks ---------------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post { onConnectionStateChanged(g, status, newState) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post { onServicesDiscoveredOnMain(g, status) }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                BleLog.ble("onMtuChanged mtu=$mtu status=${gattStatusName(status)}")
                if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu
                if (currentOp?.name == OP_MTU) opDone()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post { onDescriptorWriteOnMain(g, descriptor, status) }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g !== gatt) return@post
                BleLog.ble("onCharacteristicWrite ${characteristic.uuid} status=${gattStatusName(status)}")
                currentOp?.onWrite?.invoke(status)
                opDone()
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            handler.post {
                if (g !== gatt) return@post
                when (characteristic.uuid) {
                    AncsUuids.NOTIFICATION_SOURCE -> onNotificationSource(copy)
                    AncsUuids.DATA_SOURCE -> onDataSource(copy)
                    else -> BleLog.ble("notification from unexpected characteristic ${characteristic.uuid}")
                }
            }
        }

        override fun onServiceChanged(g: BluetoothGatt) {
            handler.post { BleLog.ble("onServiceChanged — the iPhone's GATT database changed") }
        }
    }

    private fun onConnectionStateChanged(g: BluetoothGatt, status: Int, newState: Int) {
        if (g !== gatt) {
            BleLog.ble("ignoring connection callback from a stale GATT object")
            return
        }
        BleLog.ble("onConnectionStateChange status=${connectionStatusName(status)} newState=${stateName(newState)}")
        when (newState) {
            BluetoothProfile.STATE_CONNECTED ->
                if (status == BluetoothGatt.GATT_SUCCESS) beginSession() else fail("connected with error status $status")
            BluetoothProfile.STATE_DISCONNECTED -> handleDisconnected(g, status)
            else -> Unit
        }
    }

    // ---- session setup ----------------------------------------------------------------------------------------

    private fun beginSession() {
        val target = device ?: return
        sessionId++
        requests.clear()
        inFlight = null
        reassembler = null
        liveUids.clear()
        appNames.clear()
        BleLog.ancs("session $sessionId begins")

        when (target.bondState) {
            BluetoothDevice.BOND_BONDED -> discoverServices()
            BluetoothDevice.BOND_BONDING -> setPhase(LinkPhase.BONDING, "pairing in progress — confirm on the iPhone")
            else -> {
                setPhase(LinkPhase.BONDING, "pairing — confirm on the iPhone and the watch")
                val started = target.createBond()
                BleLog.ble("createBond started=$started")
                if (!started) fail("could not start pairing")
            }
        }
    }

    private fun discoverServices() {
        val g = gatt ?: return
        setPhase(LinkPhase.DISCOVERING, "discovering services")
        val started = g.discoverServices()
        BleLog.ble("discoverServices started=$started")
        if (!started) fail("could not start service discovery")
    }

    private fun onServicesDiscoveredOnMain(g: BluetoothGatt, status: Int) {
        if (g !== gatt) return
        BleLog.ble("onServicesDiscovered status=${gattStatusName(status)}")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            fail("service discovery failed (${gattStatusName(status)})")
            return
        }
        g.services.forEach { BleLog.ble("  service ${it.uuid}") }
        val service = g.getService(AncsUuids.SERVICE)
        if (service == null) {
            fail(
                "ANCS service not found. The iPhone only exposes it to a paired device that is allowed to " +
                    "receive notifications — pair again and check the device's settings in iPhone Bluetooth.",
            )
            return
        }
        notificationSource = service.getCharacteristic(AncsUuids.NOTIFICATION_SOURCE)
        controlPoint = service.getCharacteristic(AncsUuids.CONTROL_POINT)
        dataSource = service.getCharacteristic(AncsUuids.DATA_SOURCE)
        val ns = notificationSource
        val cp = controlPoint
        val ds = dataSource
        if (ns == null || cp == null || ds == null) {
            fail("ANCS service is missing characteristics (NS=${ns != null} CP=${cp != null} DS=${ds != null})")
            return
        }
        BleLog.ancs("ANCS found; properties NS=${ns.properties} CP=${cp.properties} DS=${ds.properties}")

        setPhase(LinkPhase.SUBSCRIBING, "subscribing to ANCS")
        enqueue(GattOp(OP_MTU, fatal = false) { it.requestMtu(REQUESTED_MTU) })
        // Spec: notifications on the Notification Source start as soon as we subscribe, so be ready first. Data Source first.
        enqueue(subscribeOp("Data Source", ds))
        enqueue(subscribeOp("Notification Source", ns))
        enqueue(
            GattOp(OP_READY, fatal = true) {
                handler.post {
                    becomeReady()
                    opDone()
                }
                true
            },
        )
    }

    private fun subscribeOp(label: String, characteristic: BluetoothGattCharacteristic) =
        GattOp("$OP_SUBSCRIBE_PREFIX$label", fatal = true) { g ->
            if (!g.setCharacteristicNotification(characteristic, true)) {
                BleLog.ble("setCharacteristicNotification failed for $label")
                return@GattOp false
            }
            val cccd = characteristic.getDescriptor(AncsUuids.CCCD)
            if (cccd == null) {
                BleLog.ble("no CCCD on $label")
                return@GattOp false
            }
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
        }

    private fun onDescriptorWriteOnMain(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (g !== gatt) return
        BleLog.ble("onDescriptorWrite ${descriptor.characteristic.uuid} status=${gattStatusName(status)}")
        val op = currentOp
        if (status == BluetoothGatt.GATT_SUCCESS) {
            if (op?.name?.startsWith(OP_SUBSCRIBE_PREFIX) == true) opDone()
            return
        }
        handler.removeCallbacks(opTimeout)
        when (status) {
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION ->
                fail(
                    "iPhone requires an encrypted link (${gattStatusName(status)}). Remove the pairing on both " +
                        "devices (iPhone: Settings > Bluetooth > (i) > Forget; watch: Bluetooth settings) and pair again.",
                )
            BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION ->
                fail(
                    "iPhone refused access (${gattStatusName(status)}). The ANCS spec says every characteristic " +
                        "requires authorization — check the watch's entry in iPhone Settings > Bluetooth.",
                )
            else -> fail("subscribing failed (${gattStatusName(status)})")
        }
    }

    private fun becomeReady() {
        BleLog.ancs("session $sessionId ready, mtu=$negotiatedMtu")
        setPhase(LinkPhase.READY, "receiving notifications from ${describe(device)}")
        pumpRequests()
    }

    // ---- bonding ----------------------------------------------------------------------------------------------

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val changed = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
            handler.post { onBondChanged(changed, state, previous) }
        }
    }

    private fun registerBondReceiver() {
        if (bondReceiverRegistered) return
        ContextCompat.registerReceiver(
            context,
            bondReceiver,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        runCatching { context.unregisterReceiver(bondReceiver) }
        bondReceiverRegistered = false
    }

    private fun onBondChanged(changed: BluetoothDevice, state: Int, previous: Int) {
        BleLog.ble("bond ${changed.address}: ${bondName(previous)} -> ${bondName(state)}")
        if (changed.address != device?.address || gatt == null) return
        when (state) {
            BluetoothDevice.BOND_BONDING -> setPhase(LinkPhase.BONDING, "pairing in progress — confirm on the iPhone")
            BluetoothDevice.BOND_BONDED ->
                if (phase == LinkPhase.BONDING) handler.postDelayed({ discoverServices() }, BOND_SETTLE_MS)
            BluetoothDevice.BOND_NONE ->
                if (previous == BluetoothDevice.BOND_BONDING) fail("pairing was rejected or failed")
        }
    }

    // ---- Notification Source / Data Source --------------------------------------------------------------------

    private fun onNotificationSource(value: ByteArray) {
        if (value.size != NotificationEvent.SIZE) {
            BleLog.ancs("NS packet is ${value.size} bytes, expected ${NotificationEvent.SIZE}: ${BleLog.hex(value)}")
        }
        val event = NotificationEvent.parse(value)
        if (event == null) {
            BleLog.ancs("NS packet too short to parse: ${BleLog.hex(value)}")
            return
        }
        BleLog.ancs(
            "NS event=${event.eventId} flags=0x%02X category=${event.categoryId} count=${event.categoryCount} uid=${event.uid}"
                .format(event.eventFlags),
        )
        when (event.eventId) {
            EventId.REMOVED -> {
                liveUids.remove(event.uid)
                callbacks.onNotificationRemoved(event.uid)
            }
            EventId.ADDED, EventId.MODIFIED -> {
                if (event.isPreExisting && !POST_PRE_EXISTING) {
                    BleLog.ancs("skipping pre-existing notification uid=${event.uid}")
                    return
                }
                liveUids.add(event.uid)
                requestAttributes(event)
            }
            else -> BleLog.ancs("ignoring reserved EventID ${event.eventId}")
        }
    }

    private fun requestAttributes(event: NotificationEvent) {
        requests.addLast(
            Request(
                label = "attributes uid=${event.uid}",
                payload = AncsCommands.getNotificationAttributes(event.uid),
                expected = AncsCommands.EXPECTED_NOTIFICATION_ATTRIBUTES,
                onResponse = { message ->
                    val attributes = (message as? DataSourceMessage.NotificationAttributes)?.attributes
                    if (attributes == null) {
                        BleLog.ancs("no attributes for uid=${event.uid}; notification dropped")
                    } else {
                        resolveAppName(event, attributes)
                    }
                },
            ),
        )
        pumpRequests()
    }

    private fun resolveAppName(event: NotificationEvent, attributes: Map<Int, String>) {
        val appId = attributes[NotificationAttributeId.APP_IDENTIFIER].orEmpty()
        val cached = appNames[appId]
        if (appId.isEmpty() || cached != null) {
            deliver(event, attributes, cached ?: fallbackName(appId))
            return
        }
        requests.addLast(
            Request(
                label = "app name $appId",
                payload = AncsCommands.getAppAttributes(appId),
                expected = AncsCommands.EXPECTED_APP_ATTRIBUTES,
                onResponse = { message ->
                    val name = (message as? DataSourceMessage.AppAttributes)
                        ?.attributes?.get(AppAttributeId.DISPLAY_NAME)?.takeIf { it.isNotBlank() }
                    if (name != null) appNames[appId] = name
                    deliver(event, attributes, name ?: fallbackName(appId))
                },
            ),
        )
        pumpRequests()
    }

    private fun deliver(event: NotificationEvent, attributes: Map<Int, String>, appName: String) {
        if (event.uid !in liveUids) {
            BleLog.ancs("uid=${event.uid} was removed while its attributes were loading; not posting")
            return
        }
        callbacks.onNotification(
            AncsNotification(
                sessionId = sessionId,
                uid = event.uid,
                flags = event.eventFlags,
                categoryId = event.categoryId,
                appId = attributes[NotificationAttributeId.APP_IDENTIFIER].orEmpty(),
                appName = appName,
                title = attributes[NotificationAttributeId.TITLE].orEmpty(),
                subtitle = attributes[NotificationAttributeId.SUBTITLE].orEmpty(),
                message = attributes[NotificationAttributeId.MESSAGE].orEmpty(),
                positiveLabel = attributes[NotificationAttributeId.POSITIVE_ACTION_LABEL].orEmpty(),
                negativeLabel = attributes[NotificationAttributeId.NEGATIVE_ACTION_LABEL].orEmpty(),
            ),
        )
    }

    private fun fallbackName(appId: String) = appId.substringAfterLast('.').ifEmpty { "iPhone" }

    private fun onDataSource(value: ByteArray) {
        val active = reassembler
        if (active == null || inFlight == null) {
            BleLog.ancs("unexpected Data Source fragment (${value.size}B), no request in flight: ${BleLog.hex(value, 8)}")
            return
        }
        BleLog.ancs("DS fragment ${value.size}B for '${inFlight?.label}'")
        when (val outcome = active.feed(value)) {
            ParseOutcome.Incomplete -> Unit
            is ParseOutcome.Malformed -> {
                BleLog.ancs("malformed Data Source response: ${outcome.reason}")
                finishRequest(null)
            }
            is ParseOutcome.Complete -> finishRequest(outcome.message)
        }
    }

    // ---- request pipeline (Control Point writes) --------------------------------------------------------------

    private fun pumpRequests() {
        if (phase != LinkPhase.READY) return
        val cp = controlPoint ?: return
        while (inFlight == null) {
            val request = requests.removeFirstOrNull() ?: return
            val awaitsResponse = request.expected != null
            if (awaitsResponse) {
                inFlight = request
                reassembler = DataSourceReassembler(request.expected)
                handler.postDelayed(requestTimeout, REQUEST_TIMEOUT_MS)
            }
            enqueue(
                GattOp(
                    name = "write Control Point (${request.label})",
                    fatal = false,
                    onWrite = { status ->
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            BleLog.ancs(
                                "Control Point write failed for ${request.label}: ${gattStatusName(status)}",
                            )
                            if (request === inFlight) finishRequest(null)
                        }
                    },
                    onStartFailed = { if (request === inFlight) finishRequest(null) },
                ) { g ->
                    g.writeCharacteristic(cp, request.payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothStatusCodes.SUCCESS
                },
            )
            if (awaitsResponse) return
        }
    }

    private fun finishRequest(message: DataSourceMessage?) {
        handler.removeCallbacks(requestTimeout)
        val request = inFlight ?: return
        inFlight = null
        reassembler = null
        request.onResponse?.invoke(message)
        pumpRequests()
    }

    // ---- GATT operation queue ---------------------------------------------------------------------------------

    private fun enqueue(op: GattOp) {
        ops.addLast(op)
        runNextOp()
    }

    private fun runNextOp() {
        if (currentOp != null) return
        val g = gatt ?: return
        val op = ops.removeFirstOrNull() ?: return
        currentOp = op
        BleLog.ble("op start: ${op.name}")
        handler.postDelayed(opTimeout, OP_TIMEOUT_MS)
        val started = runCatching { op.start(g) }.getOrElse {
            BleLog.ble("op threw: ${op.name}: $it")
            false
        }
        if (!started) {
            BleLog.ble("op could not start: ${op.name}")
            handler.removeCallbacks(opTimeout)
            currentOp = null
            if (op.fatal) {
                fail("could not start ${op.name}")
            } else {
                op.onStartFailed?.invoke()
                runNextOp()
            }
        }
    }

    private fun opDone() {
        handler.removeCallbacks(opTimeout)
        currentOp = null
        runNextOp()
    }

    // ---- teardown ---------------------------------------------------------------------------------------------

    private fun handleDisconnected(g: BluetoothGatt, status: Int) {
        if (g !== gatt) return
        BleLog.ble("disconnected status=${connectionStatusName(status)} user=$userDisconnect fatal=$fatalReason")
        teardownGatt()
        val reason = when {
            fatalReason != null -> DisconnectReason.FATAL
            userDisconnect -> DisconnectReason.USER
            else -> DisconnectReason.LINK_LOST
        }
        if (reason == DisconnectReason.USER) setPhase(LinkPhase.IDLE, "disconnected")
        callbacks.onDisconnected(reason)
    }

    private fun teardownGatt() {
        handler.removeCallbacks(opTimeout)
        handler.removeCallbacks(requestTimeout)
        gatt?.let {
            BleLog.ble("closing GATT")
            it.close()
        }
        gatt = null
        ops.clear()
        currentOp = null
        requests.clear()
        inFlight = null
        reassembler = null
        liveUids.clear()
        notificationSource = null
        controlPoint = null
        dataSource = null
    }

    private fun fail(reason: String) {
        BleLog.ble("FAIL: $reason")
        fatalReason = reason
        setPhase(LinkPhase.FAILED, reason)
        val g = gatt
        if (g == null) {
            callbacks.onDisconnected(DisconnectReason.FATAL)
            return
        }
        g.disconnect()
        handler.postDelayed({ if (gatt === g) handleDisconnected(g, -1) }, DISCONNECT_FALLBACK_MS)
    }

    private fun setPhase(newPhase: LinkPhase, detail: String) {
        if (newPhase != phase) BleLog.ble("phase $phase -> $newPhase ($detail)")
        phase = newPhase
        callbacks.onPhase(newPhase, detail)
    }

    private fun describe(target: BluetoothDevice?): String = target?.let { it.name ?: it.address } ?: "device"

    private companion object {
        const val OP_MTU = "request MTU"
        const val OP_READY = "ready"
        const val OP_SUBSCRIBE_PREFIX = "subscribe "

        /** iOS supports an ATT MTU of up to 185 bytes (ANCS responses are fragmented regardless). */
        const val REQUESTED_MTU = 185
        const val DEFAULT_MTU = 23

        const val OP_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 8_000L
        const val DISCONNECT_FALLBACK_MS = 2_000L

        /** Defensive pause after bonding completes before using the link; harmless if not needed. */
        const val BOND_SETTLE_MS = 500L

        /**
         * On a new session the iPhone replays existing notifications flagged PreExisting. Posting them all would
         * flood the watch with old items, so they are skipped.
         */
        const val POST_PRE_EXISTING = false

        fun bondName(state: Int) = when (state) {
            BluetoothDevice.BOND_NONE -> "BOND_NONE"
            BluetoothDevice.BOND_BONDING -> "BOND_BONDING"
            BluetoothDevice.BOND_BONDED -> "BOND_BONDED"
            else -> "bond($state)"
        }

        fun stateName(state: Int) = when (state) {
            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "state($state)"
        }

        /** Status of a GATT operation (ATT errors). */
        fun gattStatusName(status: Int): String = when (status) {
            BluetoothGatt.GATT_SUCCESS -> "SUCCESS(0)"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "INSUFFICIENT_AUTHENTICATION(5)"
            BluetoothGatt.GATT_INSUFFICIENT_AUTHORIZATION -> "INSUFFICIENT_AUTHORIZATION(8)"
            BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "INSUFFICIENT_ENCRYPTION(15)"
            BluetoothGatt.GATT_FAILURE -> "FAILURE(257)"
            else -> ControlPointError.describe(status)?.let { "$it(0x%02X)".format(status) }
                ?: "status $status (0x%02X)".format(status)
        }

        /** Status of a connection-state callback; these carry HCI-style codes, so 8 means a connection timeout here. */
        fun connectionStatusName(status: Int): String = when (status) {
            0 -> "SUCCESS(0)"
            8 -> "CONNECTION_TIMEOUT(8)"
            19 -> "REMOTE_USER_TERMINATED(19)"
            22 -> "LOCAL_HOST_TERMINATED(22)"
            62 -> "CONNECTION_FAILED_TO_BE_ESTABLISHED(62)"
            133 -> "GATT_ERROR(133)"
            else -> "status $status"
        }
    }
}
