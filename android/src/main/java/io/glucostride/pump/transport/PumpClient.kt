/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from GlycemicGPT/android-unofficial at
 * 59e68104df17614c50173bed954843a5f56e588b:
 * AndroidMedtronicPeripheral.kt, AndroidMedtronicGattLink.kt,
 * MedtronicBleConnectionManager.kt, SakeHandshakeDriver.kt and MedtronicSessionReader.kt.
 * Copyright GlycemicGPT contributors. Their choreography derives from OpenMinimed
 * JavaPumpConnector and PythonPumpConnector (GPL-3.0), by Pal Marci (palmarci),
 * drfubar, Morten Fyhn Amundsen, Stenium; original PoC by planiitis.
 *
 * GlucoStride changes: no coroutines/Timber, single-worker BLE and cipher ownership,
 * pinned peer, bounded lifecycle/operations, versioned value snapshots, strict CGM/clock
 * operation allowlist and single-record exchange. No therapy, history or SOCP access.
 */
package io.glucostride.pump.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import io.glucostride.compat.BluetoothCompat
import io.glucostride.compat.GattValueSnapshot
import io.glucostride.pump.PumpConnectionState
import io.glucostride.pump.PumpConnectionPolicy
import io.glucostride.pump.PumpListener
import io.glucostride.pump.PumpClockRead
import io.glucostride.pump.RawCgmRecord
import org.bouncycastle.crypto.RuntimeCryptoException
import org.openminimed.sake.MacFailureException
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Call start only after permission/user confirmation. This class never scans or initiates bonding. */
@SuppressLint("MissingPermission")
class PumpClient(context: Context, private val listener: PumpListener) {
    private val appContext = context.applicationContext
    private val debugDiagnostics = appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val lifecycleLock = Any()
    private var worker: Worker? = null
    private var requestVersion = 0L

    /** Null explicitly starts first pairing; otherwise only that previously paired peer is accepted. */
    fun start(pairedAddress: String?, balancedDiscovery: Boolean = false) {
        synchronized(lifecycleLock) {
            val version = ++requestVersion
            val owner = worker ?: Worker().also { worker = it }
            owner.handler.post { owner.start(pairedAddress, version, balancedDiscovery) }
        }
    }

    /**
     * Asynchronous, idempotent teardown. Completion runs on the worker after both GATT
     * handles/advertising are closed and callbacks/retries invalidated; if already stopped,
     * it runs immediately on the caller. Wait for completion before a subsequent start.
     */
    fun stop(onStopped: () -> Unit = {}) {
        val alreadyStopped = synchronized(lifecycleLock) {
            val version = ++requestVersion
            val owner = worker
            if (owner == null) {
                true
            } else {
                owner.handler.post {
                    owner.stop()
                    retire(owner, version)
                    onStopped()
                }
                false
            }
        }
        if (alreadyStopped) onStopped()
    }

    private fun retire(owner: Worker, version: Long) {
        synchronized(lifecycleLock) {
            if (worker === owner && requestVersion == version) {
                worker = null
                owner.thread.quitSafely()
            }
        }
    }

    private enum class OperationKind { CONNECT, DISCOVER, READ, WRITE, DESCRIPTOR }

    private class Operation(
        val kind: OperationKind,
        val target: Any?,
        val done: (ByteArray) -> Unit,
    )

    private class Poll {
        var feature = byteArrayOf()
        var before = byteArrayOf()
        var after = byteArrayOf()
        var status = byteArrayOf()
        var measurement: ByteArray? = null
        var receiptEpoch = 0L
        var receiptElapsed = 0L
        var clockRead: PumpClockRead? = null
        var requested = false
        var requestAcknowledged = false
        var responseReceived = false
        var closing = false
        val collector = ReportLastCollector()
    }

    private inner class Worker {
        val thread = HandlerThread("GlucoStride-pump").apply { start() }
        val handler = Handler(thread.looper)
        private var startVersion = 0L
        private var generation = 0L
        private var running = false
        private var reconnectMode = false
        private var pinnedAddress: String? = null
        private var retries = 0
        private var peer: BluetoothDevice? = null
        private var server: BluetoothGattServer? = null
        private var client: BluetoothGatt? = null
        private var balancedDiscovery = false
        private var pacing = PumpDiscoveryPacing()
        private var pacingClient: BluetoothGatt? = null
        private var pacingConnectTimer: Runnable? = null
        private var pacingRequestTimer: Runnable? = null
        private var advertiser: BluetoothLeAdvertiser? = null
        private var advertisingCallback: AdvertiseCallback? = null
        private var acceptingPeer = false
        private var diagnostics = PumpPairingDiagnostics()
        private var bondReceiver: BroadcastReceiver? = null
        private var sakeCharacteristic: BluetoothGattCharacteristic? = null
        private var sake: PumpSakeSession? = null
        private var subscribed = false
        private var notifying = false
        private val handshakeWrites = ArrayDeque<ByteArray>()
        private val servicesToAdd = ArrayDeque<BluetoothGattService>()
        private var addingService: BluetoothGattService? = null
        private val deviceInfo = mutableMapOf<UUID, ByteArray>()
        private var cgmCharacteristics = emptyMap<UUID, BluetoothGattCharacteristic>()
        private var operation: Operation? = null
        private var poll: Poll? = null
        private var phaseTimer: Runnable? = null
        private var operationTimer: Runnable? = null
        private var notificationTimer: Runnable? = null
        private var exchangeTimer: Runnable? = null
        private var nextPollTimer: Runnable? = null
        private var retryTimer: Runnable? = null
        // Only ingress accounting crosses threads; all BLE/session state remains on handler.
        private val queuedCallbacks = AtomicInteger()
        private val callbackOverflow = AtomicBoolean()

        fun start(address: String?, version: Long, balancedDiscovery: Boolean) {
            cleanup()
            this.balancedDiscovery = balancedDiscovery
            startVersion = version
            running = true
            retries = 0
            pinnedAddress = address?.uppercase(Locale.ROOT)
            reconnectMode = address != null
            if (address != null && !BluetoothAdapter.checkBluetoothAddress(pinnedAddress)) {
                fail("Invalid saved pairing", true)
                return
            }
            safe { openPeripheral() }
        }

        fun stop() {
            running = false
            cleanup()
            pinnedAddress = null
            listener.onState(PumpConnectionState.STOPPED, "Pump monitoring stopped")
        }

        private fun safe(block: () -> Unit) {
            try {
                block()
            } catch (_: SecurityException) {
                fail("Bluetooth permission unavailable", true)
            } catch (_: IllegalStateException) {
                fail("Bluetooth state unavailable", false)
            } catch (_: IllegalArgumentException) {
                fail("Bluetooth operation rejected", false)
            } catch (error: TransportProtocolException) {
                fail(error.label, false)
            }
        }

        private fun post(token: Long, block: () -> Unit) {
            if (queuedCallbacks.incrementAndGet() > MAX_QUEUED_CALLBACKS) {
                queuedCallbacks.decrementAndGet()
                if (callbackOverflow.compareAndSet(false, true)) {
                    handler.post {
                        callbackOverflow.set(false)
                        if (running && token == generation) fail("Bluetooth callback queue overflow", false)
                    }
                }
                return
            }
            if (!handler.post {
                try {
                    if (running && token == generation) safe(block)
                } finally {
                    queuedCallbacks.decrementAndGet()
                }
            }) queuedCallbacks.decrementAndGet()
        }

        private fun timer(delay: Long, block: () -> Unit): Runnable {
            val token = generation
            return Runnable { if (running && token == generation) safe(block) }
                .also { handler.postDelayed(it, delay) }
        }

        private fun cancel(timer: Runnable?) {
            if (timer != null) handler.removeCallbacks(timer)
        }

        private fun diagnostic(event: String) {
            diagnostics.sakeStage = sake?.stage
            diagnostics.pacing = pacing.status
            if (debugDiagnostics) Log.i(DIAGNOSTIC_TAG, "$event [${diagnostics.summary()}]")
        }

        private fun observeBond(state: Int) {
            pacing.observeBond(state == BluetoothDevice.BOND_BONDED)
            diagnostics.bond = when (state) {
                BluetoothDevice.BOND_NONE -> PumpPairingDiagnostics.Bond.NONE
                BluetoothDevice.BOND_BONDING -> PumpPairingDiagnostics.Bond.BONDING
                BluetoothDevice.BOND_BONDED -> PumpPairingDiagnostics.Bond.BONDED
                else -> PumpPairingDiagnostics.Bond.UNKNOWN
            }
        }

        private fun registerBondDiagnostics(token: Long) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val device = BluetoothCompat.deviceExtra(intent)
                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    post(token) {
                        if (device != null && selected(device)) {
                            observeBond(state)
                            diagnostic("android_bond_changed")
                            schedulePacingRequest()
                        }
                    }
                }
            }
            BluetoothCompat.registerReceiver(appContext, receiver, BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            bondReceiver = receiver
        }

        private fun fail(label: String, terminal: Boolean) {
            if (!running) return
            val delay = PumpConnectionPolicy.reconnectDelayMillis(reconnectMode, terminal, retries + 1)
            val stop = delay == null
            diagnostic("failure terminal=$stop: $label")
            val failure = diagnostics.failure(label)
            cleanup()
            listener.onFailure(failure, stop)
            if (stop) {
                running = false
                listener.onState(PumpConnectionState.STOPPED, "Pump monitoring stopped")
                retire(this, startVersion)
            } else {
                retries = (retries + 1).coerceAtMost(3)
                listener.onState(PumpConnectionState.RECONNECTING, "Waiting to reconnect to pump")
                retryTimer = timer(requireNotNull(delay)) { openPeripheral() }
            }
        }

        private fun openPeripheral() {
            diagnostics = PumpPairingDiagnostics()
            pacing = PumpDiscoveryPacing(balancedDiscovery)
            diagnostic("start reconnect=$reconnectMode")
            val manager = appContext.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            if (adapter == null || !adapter.isEnabled ||
                !adapter.isMultipleAdvertisementSupported
            ) {
                fail("Bluetooth peripheral unavailable", true)
                return
            }
            advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                fail("Bluetooth advertising unavailable", true)
                return
            }
            val token = generation
            registerBondDiagnostics(token)
            diagnostics.phase = PumpPairingDiagnostics.Phase.REGISTERING_SERVICES
            server = manager.openGattServer(appContext, peripheralCallback(token))
            if (server == null) {
                fail("Bluetooth server unavailable", false)
                return
            }
            servicesToAdd.add(deviceInfoService())
            servicesToAdd.add(sakeService())
            addNextService()
        }

        private fun deviceInfoService(): BluetoothGattService {
            val service = BluetoothGattService(PumpUuids.DEVICE_INFO, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val ascii = Charsets.US_ASCII
            deviceInfo.putAll(
                mapOf(
                    PumpUuids.sig(0x2a29) to "GlucoStride".toByteArray(ascii),
                    PumpUuids.sig(0x2a24) to "Mobile".toByteArray(ascii),
                    PumpUuids.sig(0x2a25) to LOCAL_NAME.toByteArray(ascii),
                    PumpUuids.sig(0x2a27) to "0".toByteArray(ascii),
                    PumpUuids.sig(0x2a26) to "0".toByteArray(ascii),
                    PumpUuids.sig(0x2a28) to "0".toByteArray(ascii),
                    PumpUuids.sig(0x2a23) to ByteArray(8),
                    PumpUuids.sig(0x2a50) to ByteArray(7),
                    PumpUuids.sig(0x2a2a) to ByteArray(0),
                ),
            )
            for ((uuid, _) in deviceInfo) {
                service.addCharacteristic(
                    BluetoothGattCharacteristic(
                        uuid, BluetoothGattCharacteristic.PROPERTY_READ,
                        BluetoothGattCharacteristic.PERMISSION_READ,
                    ),
                )
            }
            return service
        }

        private fun sakeService(): BluetoothGattService {
            val service = BluetoothGattService(PumpUuids.FIRST_PAIR, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                PumpUuids.SAKE,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE,
                BluetoothGattCharacteristic.PERMISSION_WRITE,
            )
            characteristic.addDescriptor(
                BluetoothGattDescriptor(
                    PumpUuids.CCCD,
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                ),
            )
            service.addCharacteristic(characteristic)
            sakeCharacteristic = characteristic
            return service
        }

        private fun addNextService() {
            cancel(phaseTimer)
            val service = servicesToAdd.pollFirst()
            addingService = service
            if (service == null) {
                advertise()
                return
            }
            phaseTimer = timer(OPERATION_TIMEOUT_MS) { fail("Bluetooth service registration timed out", false) }
            if (server?.addService(service) != true) fail("Bluetooth service registration failed", false)
        }

        private fun advertise() {
            val token = generation
            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    post(token) {
                        if (advertisingCallback !== this || !acceptingPeer) return@post
                        cancel(phaseTimer)
                        diagnostics.phase = PumpPairingDiagnostics.Phase.ADVERTISING
                        diagnostic("advertising_ready")
                        listener.onState(
                            if (reconnectMode) PumpConnectionState.RECONNECTING else PumpConnectionState.ADVERTISING,
                            if (reconnectMode) "Waiting for paired pump" else "Select $LOCAL_NAME on the pump",
                        )
                        phaseTimer = timer(if (reconnectMode) RECONNECT_WAIT_MS else PAIRING_WAIT_MS) {
                            fail("Pump connection wait timed out", false)
                        }
                    }
                }

                override fun onStartFailure(errorCode: Int) {
                    post(token) {
                        if (advertisingCallback === this) fail("Bluetooth advertising failed (code $errorCode)", false)
                    }
                }
            }
            val name = LOCAL_NAME.toByteArray(Charsets.US_ASCII)
            val manufacturer = ByteArray(name.size + 2).also { name.copyInto(it, 1) }
            val data = AdvertiseData.Builder()
                .addManufacturerData(0x01f9, manufacturer)
                .addServiceUuid(ParcelUuid(if (reconnectMode) PumpUuids.RECONNECT else PumpUuids.FIRST_PAIR))
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(true)
                .build()
            val settings = AdvertiseSettings.Builder()
                .setConnectable(true)
                .setAdvertiseMode(
                    if (reconnectMode) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
                    else AdvertiseSettings.ADVERTISE_MODE_BALANCED,
                )
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setTimeout(0)
                .build()
            acceptingPeer = true
            advertisingCallback = callback
            phaseTimer = timer(OPERATION_TIMEOUT_MS) { fail("Bluetooth advertising start timed out", false) }
            advertiser?.startAdvertising(settings, data, callback)
        }

        private fun stopAdvertising() {
            acceptingPeer = false
            val callback = advertisingCallback
            advertisingCallback = null
            if (callback != null) advertiser?.stopAdvertising(callback)
        }

        private fun selected(device: BluetoothDevice): Boolean = peer == device

        private fun centralConnected(device: BluetoothDevice, status: Int) {
            if (selected(device)) return
            // Android may report the watch link to both GATT servers. Never cancel
            // an unrelated peer: attribute requests are separately authorized below.
            if (!PumpPeerPolicy.canSelect(acceptingPeer, peer != null, pinnedAddress, device.address)) return
            if (status != BluetoothGatt.GATT_SUCCESS) return
            peer = device
            pinnedAddress = device.address
            observeBond(device.bondState)
            diagnostics.phase = PumpPairingDiagnostics.Phase.AUTHENTICATING
            diagnostic("peer_selected")
            stopAdvertising()
            cancel(phaseTimer)
            phaseTimer = timer(HANDSHAKE_TIMEOUT_MS) { fail("Pump authentication timed out", true) }
            listener.onState(PumpConnectionState.AUTHENTICATING, "Authenticating pump")
            if (pacing.enabled) openPacingClient()
        }

        private fun openPacingClient() {
            val target = peer ?: throw TransportProtocolException("Pacing peer unavailable")
            check(pacingClient == null)
            val token = generation
            diagnostic("pacing_attach_existing_link")
            pacingConnectTimer = timer(CONNECT_TIMEOUT_MS) { fail("Experimental pacing link attach timed out", true) }
            // Parameter-only handle on the pinned link. It never discovers services or accesses pump data.
            // Keep it until teardown so closing the handle cannot interrupt the handoff to the CGM client.
            pacingClient = target.connectGatt(appContext, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = post(token) {
                    if (gatt !== pacingClient) return@post
                    diagnostic("pacing_link state=$newState status=$status")
                    if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        fail("Experimental pacing link disconnected (GATT $status)", status == 5 || status == 137)
                    } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                        cancel(pacingConnectTimer)
                        pacingConnectTimer = null
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail("Experimental pacing link attach failed (GATT $status)", true)
                            return@post
                        }
                        pacing.observeLink(true)
                        observeBond(target.bondState)
                        schedulePacingRequest()
                    }
                }
            }, BluetoothDevice.TRANSPORT_LE)
            if (pacingClient == null) fail("Experimental pacing link attach was rejected", true)
        }

        private fun schedulePacingRequest() {
            if (pacing.status != PumpDiscoveryPacing.Status.SETTLING) {
                cancel(pacingRequestTimer)
                pacingRequestTimer = null
                return
            }
            if (pacingRequestTimer != null) return
            diagnostic("balanced_request_waiting_for_bond_settle")
            pacingRequestTimer = timer(PACING_SETTLE_MS) {
                pacingRequestTimer = null
                val target = peer ?: throw TransportProtocolException("Pacing peer unavailable")
                observeBond(target.bondState)
                if (!pacing.beginRequest()) {
                    diagnostic("balanced_request_deferred_bond_not_ready")
                    return@timer
                }
                val handle = pacingClient ?: throw TransportProtocolException("Pacing link unavailable")
                val submitted = handle.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
                pacing.completeRequest(submitted)
                diagnostic("balanced_request submitted=$submitted negotiated_interval=unconfirmed")
                if (!submitted) {
                    fail("Android rejected the experimental BALANCED request", true)
                } else {
                    listener.onState(PumpConnectionState.AUTHENTICATING,
                        "Experimental BALANCED request submitted; interval unconfirmed. Waiting for pump authentication.")
                }
            }
        }

        private fun respond(device: BluetoothDevice, id: Int, status: Int, offset: Int = 0, data: ByteArray? = null) {
            if (server?.sendResponse(device, id, status, offset, data) != true && selected(device)) {
                fail("Bluetooth server response failed", false)
            }
        }

        private fun readBlob(device: BluetoothDevice, id: Int, offset: Int, data: ByteArray?) {
            when {
                !selected(device) -> respond(device, id, BluetoothCompat.GATT_INSUFFICIENT_AUTHORIZATION)
                data == null -> respond(device, id, BluetoothGatt.GATT_READ_NOT_PERMITTED)
                offset < 0 || offset > data.size -> respond(device, id, BluetoothGatt.GATT_INVALID_OFFSET)
                else -> respond(device, id, BluetoothGatt.GATT_SUCCESS, offset, data.copyOfRange(offset, data.size))
            }
        }

        private fun writeStatus(device: BluetoothDevice, prepared: Boolean, offset: Int): Int = when {
            !selected(device) -> BluetoothCompat.GATT_INSUFFICIENT_AUTHORIZATION
            prepared -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
            offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
            else -> BluetoothGatt.GATT_SUCCESS
        }

        private fun peripheralCallback(token: Long) = object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) = post(token) {
                if (addingService?.uuid != service.uuid) return@post
                diagnostic("service_added status=$status")
                if (status != BluetoothGatt.GATT_SUCCESS) fail("Bluetooth service registration failed (GATT $status)", false)
                else addNextService()
            }

            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = post(token) {
                if (selected(device)) observeBond(device.bondState)
                diagnostic("server_connection state=$newState status=$status selected=${selected(device)}")
                if (newState == BluetoothProfile.STATE_CONNECTED) centralConnected(device, status)
                else if (newState == BluetoothProfile.STATE_DISCONNECTED && selected(device)) {
                    fail("Pump disconnected (server; GATT $status)", status == 5 || status == 137)
                }
            }

            override fun onCharacteristicReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
            ) = post(token) {
                if (selected(device) && characteristic.service?.uuid == PumpUuids.DEVICE_INFO) {
                    diagnostic("device_information_read field=${characteristic.uuid}")
                }
                readBlob(
                    device, requestId, offset,
                    if (characteristic.service?.uuid == PumpUuids.DEVICE_INFO) deviceInfo[characteristic.uuid] else null,
                )
            }

            override fun onDescriptorReadRequest(
                device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
            ) = post(token) {
                readBlob(
                    device, requestId, offset,
                    if (isSakeCccd(descriptor)) byteArrayOf(if (subscribed) 1 else 0, 0) else null,
                )
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
            ) {
                val copy = GattValueSnapshot.copy(value)
                post(token) {
                    var status = writeStatus(device, preparedWrite, offset)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        status = when {
                            characteristic !== sakeCharacteristic || !subscribed || sake?.complete != false ->
                                BluetoothGatt.GATT_WRITE_NOT_PERMITTED
                            copy.size != 20 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                            handshakeWrites.size >= MAX_HANDSHAKE_WRITES -> BluetoothGatt.GATT_FAILURE
                            else -> BluetoothGatt.GATT_SUCCESS
                        }
                    }
                    if (responseNeeded) respond(device, requestId, status)
                    if (!running || generation != token) return@post
                    if (selected(device)) diagnostic("authentication_write bytes=${copy.size} status=$status")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        handshakeWrites.add(copy)
                        drainHandshake()
                    } else if (selected(device)) {
                        fail("Invalid authentication write", true)
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
            ) {
                val copy = GattValueSnapshot.copy(value)
                post(token) {
                    var status = writeStatus(device, preparedWrite, offset)
                    if (status == BluetoothGatt.GATT_SUCCESS &&
                        (!isSakeCccd(descriptor) ||
                            !(copy.contentEquals(byteArrayOf(1, 0)) || copy.contentEquals(byteArrayOf(0, 0))))
                    ) status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    if (responseNeeded) respond(device, requestId, status)
                    if (!running || generation != token) return@post
                    if (selected(device)) diagnostic("authentication_subscription bytes=${copy.size} status=$status")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        if (selected(device)) fail("Invalid authentication subscription", true)
                        return@post
                    }
                    if (copy[0] == 1.toByte()) {
                        diagnostic("authentication_subscribe")
                        if (!subscribed) {
                            if (sake != null) {
                                fail("Authentication resubscription refused", true)
                                return@post
                            }
                            subscribed = true
                            val session = crypto { PumpSakeSession() } ?: return@post
                            sake = session
                            notifyHandshake(session.wakeUp())
                        }
                    } else {
                        diagnostic("authentication_unsubscribe")
                        subscribed = false
                        if (sake?.complete != true) fail("Authentication subscription ended", true)
                    }
                }
            }

            override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = post(token) {
                respond(
                    device, requestId,
                    if (selected(device)) BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                    else BluetoothCompat.GATT_INSUFFICIENT_AUTHORIZATION,
                )
            }

            override fun onNotificationSent(device: BluetoothDevice, status: Int) = post(token) {
                if (!selected(device) || !notifying) return@post
                diagnostic("authentication_notification_complete status=$status")
                cancel(notificationTimer)
                notifying = false
                if (status != BluetoothGatt.GATT_SUCCESS) fail("Authentication notification failed (GATT $status)", true)
                else drainHandshake()
            }
        }

        private fun isSakeCccd(descriptor: BluetoothGattDescriptor): Boolean =
            descriptor.uuid == PumpUuids.CCCD && descriptor.characteristic === sakeCharacteristic

        private fun notifyHandshake(frame: ByteArray) {
            val target = peer ?: throw TransportProtocolException("Authentication peer unavailable")
            val characteristic = sakeCharacteristic ?: throw TransportProtocolException("Authentication service unavailable")
            check(!notifying)
            diagnostic("authentication_notify bytes=${frame.size}")
            notifying = true
            notificationTimer = timer(OPERATION_TIMEOUT_MS) { fail("Authentication notification timed out", true) }
            if (server?.let { BluetoothCompat.notify(it, target, characteristic, frame) } != BluetoothStatusCodes.SUCCESS) {
                fail("Authentication notification rejected", true)
            }
        }

        private fun <T> crypto(block: () -> T): T? {
            return try {
                block()
            } catch (_: MacFailureException) {
                fail("Pump authentication failed", true)
                null
            } catch (_: IllegalArgumentException) {
                fail("Invalid authenticated payload", true)
                null
            } catch (_: IllegalStateException) {
                fail("Authentication state invalid", true)
                null
            } catch (_: RuntimeCryptoException) {
                fail("Cryptographic processing failed", true)
                null
            } catch (error: TransportProtocolException) {
                fail(error.label, true)
                null
            }
        }

        private fun drainHandshake() {
            if (notifying || handshakeWrites.isEmpty()) return
            val session = sake ?: return
            val result = crypto { session.handshake(handshakeWrites.removeFirst()) to session.complete } ?: return
            diagnostic("authentication_progress")
            val response = result.first
            if (response != null) notifyHandshake(response)
            else {
                if (!result.second || handshakeWrites.isNotEmpty()) {
                    fail("Unexpected authentication sequence", true)
                    return
                }
                cancel(phaseTimer)
                cancel(pacingRequestTimer)
                cancel(pacingConnectTimer)
                pacingRequestTimer = null
                pacingConnectTimer = null
                pacing.finish()
                reconnectMode = true
                val address = pinnedAddress ?: throw TransportProtocolException("Authentication peer unavailable")
                listener.onPaired(address)
                openClient()
            }
        }

        private fun openClient() {
            val target = peer ?: throw TransportProtocolException("Pump connection unavailable")
            val token = generation
            diagnostics.phase = PumpPairingDiagnostics.Phase.CONNECTING_CGM
            diagnostic("cgm_connect")
            operation = Operation(OperationKind.CONNECT, null) {
                diagnostics.phase = PumpPairingDiagnostics.Phase.DISCOVERING_CGM
                diagnostic("cgm_discover")
                beginOperation(OperationKind.DISCOVER, null, { discoverCgm() }) {
                    client?.discoverServices() == true
                }
            }
            operationTimer = timer(CONNECT_TIMEOUT_MS) { fail("Pump GATT connection timed out", false) }
            client = target.connectGatt(appContext, false, clientCallback(token), BluetoothDevice.TRANSPORT_LE)
            if (client == null) fail("Pump GATT connection failed", false)
        }

        private fun discoverCgm() {
            val services = client?.services?.filter { it.uuid == PumpUuids.CGM }.orEmpty()
            if (services.size != 1) throw TransportProtocolException("CGM service unavailable or ambiguous")
            val required = setOf(
                PumpUuids.FEATURE, PumpUuids.SESSION_START, PumpUuids.STATUS, PumpUuids.MEASUREMENT, PumpUuids.RACP,
            )
            val chars = services.single().characteristics.filter { it.uuid in required }
            if (chars.size != required.size || chars.map { it.uuid }.toSet() != required) {
                throw TransportProtocolException("CGM characteristics unavailable or ambiguous")
            }
            cgmCharacteristics = chars.associateBy { it.uuid }
            val clocks = client?.services.orEmpty()
                .filter { it.uuid == PumpUuids.CURRENT_TIME_SERVICE }
                .flatMap { it.characteristics }
                .filter { it.uuid == PumpUuids.CURRENT_TIME }
            if (clocks.size > 1) throw TransportProtocolException("Pump clock characteristic ambiguous")
            clocks.singleOrNull()?.let { cgmCharacteristics = cgmCharacteristics + (it.uuid to it) }
            diagnostics.phase = PumpPairingDiagnostics.Phase.READING_CGM
            diagnostic("cgm_ready")
            listener.onState(PumpConnectionState.CONNECTED, "Pump authenticated; reading glucose")
            startPoll()
        }

        private fun characteristic(uuid: UUID): BluetoothGattCharacteristic =
            cgmCharacteristics[uuid] ?: throw TransportProtocolException("CGM characteristic unavailable")

        private fun beginOperation(
            kind: OperationKind, target: Any?, done: (ByteArray) -> Unit, issue: () -> Boolean,
        ) {
            check(operation == null)
            operation = Operation(kind, target, done)
            operationTimer = timer(OPERATION_TIMEOUT_MS) { fail("Pump GATT operation timed out", false) }
            if (!issue()) fail("Pump GATT operation rejected", false)
        }

        private fun completeOperation(kind: OperationKind, target: Any?, status: Int, value: ByteArray = byteArrayOf()) {
            val current = operation ?: return
            if (current.kind != kind || current.target !== target) {
                fail("Unexpected pump GATT completion", false)
                return
            }
            cancel(operationTimer)
            operation = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(
                    "Pump GATT operation failed ($kind; GATT $status)",
                    status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION ||
                        status == BluetoothCompat.GATT_INSUFFICIENT_AUTHORIZATION ||
                        status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION,
                )
            } else {
                current.done(value)
            }
        }

        private fun clientCallback(token: Long) = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = post(token) {
                if (gatt !== client) return@post
                diagnostic("client_connection state=$newState status=$status")
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    fail("Pump disconnected (client; GATT $status)", status == 5 || status == 137)
                } else if (newState == BluetoothProfile.STATE_CONNECTED && operation?.kind == OperationKind.CONNECT) {
                    completeOperation(OperationKind.CONNECT, null, status)
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = post(token) {
                if (gatt === client) completeOperation(OperationKind.DISCOVER, null, status)
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int,
            ) {
                readSnapshot(gatt, characteristic, GattValueSnapshot.copy(value), status)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
            ) {
                val copy = GattValueSnapshot.legacy(Build.VERSION.SDK_INT) { characteristic.value } ?: return
                readSnapshot(gatt, characteristic, copy, status)
            }

            private fun readSnapshot(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, copy: ByteArray, status: Int,
            ) {
                post(token) {
                    if (gatt === client) completeOperation(OperationKind.READ, characteristic, status, copy)
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int,
            ) = post(token) {
                if (gatt === client) completeOperation(OperationKind.WRITE, characteristic, status)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int,
            ) = post(token) {
                if (gatt === client) completeOperation(OperationKind.DESCRIPTOR, descriptor, status)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
            ) {
                notificationSnapshot(gatt, characteristic, GattValueSnapshot.copy(value))
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                val copy = GattValueSnapshot.legacy(Build.VERSION.SDK_INT) { characteristic.value } ?: return
                notificationSnapshot(gatt, characteristic, copy)
            }

            private fun notificationSnapshot(
                gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, copy: ByteArray,
            ) {
                val receivedEpoch = System.currentTimeMillis()
                val receivedElapsed = SystemClock.elapsedRealtime()
                post(token) {
                    if (gatt === client) notification(characteristic, copy, receivedEpoch, receivedElapsed)
                }
            }
        }

        private fun read(uuid: UUID, encrypted: Boolean, done: (ByteArray) -> Unit) {
            val char = characteristic(uuid)
            check(PumpOperationPolicy.permitsRead(char.service.uuid, uuid))
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) {
                throw TransportProtocolException("CGM characteristic is not readable")
            }
            beginOperation(OperationKind.READ, char, { bytes ->
                if (bytes.isEmpty()) throw TransportProtocolException("Empty CGM read")
                val plain = if (encrypted) {
                    crypto { sake?.decrypt(bytes) ?: throw TransportProtocolException("CGM cipher unavailable") }
                        ?: return@beginOperation
                } else bytes
                done(plain)
            }) { client?.readCharacteristic(char) == true }
        }

        private fun subscribe(uuid: UUID, enabled: Boolean, done: () -> Unit) {
            val char = characteristic(uuid)
            val descriptor = char.getDescriptor(PumpUuids.CCCD)
                ?: throw TransportProtocolException("CGM subscription unavailable")
            val indication = char.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            if (!indication && char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
                throw TransportProtocolException("CGM notification unavailable")
            }
            val value = byteArrayOf(if (!enabled) 0 else if (indication) 2 else 1, 0)
            check(PumpOperationPolicy.permitsDescriptorWrite(char.service.uuid, uuid, descriptor.uuid, value))
            if (enabled && client?.setCharacteristicNotification(char, true) != true) {
                throw TransportProtocolException("CGM notification routing failed")
            }
            beginOperation(OperationKind.DESCRIPTOR, descriptor, {
                if (!enabled && client?.setCharacteristicNotification(char, false) != true) {
                    throw TransportProtocolException("CGM notification cleanup failed")
                }
                done()
            }) { client?.let { BluetoothCompat.writeDescriptor(it, descriptor, value) } == true }
        }

        private fun startPoll() {
            check(poll == null)
            val current = Poll()
            poll = current
            exchangeTimer = timer(EXCHANGE_TIMEOUT_MS) { fail("CGM exchange timed out", false) }
            if (PumpUuids.CURRENT_TIME in cgmCharacteristics) {
                val started = SystemClock.elapsedRealtime()
                read(PumpUuids.CURRENT_TIME, false) { value ->
                    current.clockRead = PumpClockRead(value, started, SystemClock.elapsedRealtime())
                    readCgm(current)
                }
            } else {
                readCgm(current)
            }
        }

        private fun readCgm(current: Poll) {
            read(PumpUuids.FEATURE, false) { feature ->
                current.feature = feature
                read(PumpUuids.SESSION_START, true) { before ->
                    current.before = before
                    read(PumpUuids.STATUS, true) { status ->
                        current.status = status
                        subscribe(PumpUuids.MEASUREMENT, true) {
                            subscribe(PumpUuids.RACP, true) { reportLast(current) }
                        }
                    }
                }
            }
        }

        private fun reportLast(current: Poll) {
            val char = characteristic(PumpUuids.RACP)
            val value = byteArrayOf(1, 6)
            check(PumpOperationPolicy.permitsWrite(char.service.uuid, char.uuid, value))
            if (char.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                throw TransportProtocolException("CGM report-last unavailable")
            }
            current.requested = true
            beginOperation(OperationKind.WRITE, char, {
                current.requestAcknowledged = true
                finishExchangeIfReady(current)
            }) {
                client?.let { BluetoothCompat.writeCharacteristic(it, char, value) } == true
            }
        }

        private fun notification(
            char: BluetoothGattCharacteristic, value: ByteArray, receivedEpoch: Long, receivedElapsed: Long,
        ) {
            if (char.service?.uuid != PumpUuids.CGM ||
                char.uuid !in setOf(PumpUuids.MEASUREMENT, PumpUuids.RACP)
            ) return
            if (cgmCharacteristics[char.uuid] !== char) {
                fail("Unexpected CGM notification source", false)
                return
            }
            val current = poll
            if (char.uuid == PumpUuids.MEASUREMENT) {
                val plain = crypto {
                    decryptMeasurementNotification(
                        value, current != null && current.requested && !current.responseReceived,
                    ) { encrypted ->
                        sake?.decrypt(encrypted) ?: throw TransportProtocolException("CGM cipher unavailable")
                    }
                } ?: return
                val active = requireNotNull(current)
                active.collector.offer(plain)
                if (active.collector.complete) {
                    active.receiptEpoch = receivedEpoch
                    active.receiptElapsed = receivedElapsed
                }
            } else {
                if (current == null || !current.requested || current.responseReceived) {
                    fail("Unsolicited or duplicate CGM control response", false)
                    return
                }
                current.measurement = current.collector.finish(value)
                current.responseReceived = true
                finishExchangeIfReady(current)
            }
        }

        private fun finishExchangeIfReady(current: Poll) {
            // A fast pump can indicate its response before Android acknowledges our RACP write.
            if (!current.responseReceived || !current.requestAcknowledged || current.closing) return
            current.closing = true
            subscribe(PumpUuids.MEASUREMENT, false) {
                subscribe(PumpUuids.RACP, false) {
                    read(PumpUuids.SESSION_START, true) { after ->
                        current.after = after
                        cancel(exchangeTimer)
                        poll = null
                        retries = 0
                        val measurement = current.measurement
                        if (measurement == null) {
                            listener.onNoData()
                        } else {
                            listener.onRecord(
                                RawCgmRecord(
                                    current.feature, current.before, current.after, current.status, measurement,
                                    current.receiptEpoch, current.receiptElapsed,
                                    current.clockRead,
                                ),
                            )
                        }
                        nextPollTimer = timer(POLL_INTERVAL_MS) { startPoll() }
                    }
                }
            }
        }

        private fun cleanupCall(block: () -> Unit) {
            try {
                block()
            } catch (_: SecurityException) {
                Log.w("GlucoStride", "Bluetooth cleanup denied after permission revocation.")
            } catch (_: IllegalStateException) {
                Log.w("GlucoStride", "Bluetooth handle unavailable during cleanup.")
            }
        }

        private fun cleanup() {
            generation++
            val receiver = bondReceiver
            bondReceiver = null
            if (receiver != null) cleanupCall { appContext.unregisterReceiver(receiver) }
            pacing.finish()
            listOf(phaseTimer, operationTimer, notificationTimer, exchangeTimer, nextPollTimer, retryTimer,
                pacingConnectTimer, pacingRequestTimer)
                .forEach { cancel(it) }
            pacingConnectTimer = null
            pacingRequestTimer = null
            phaseTimer = null
            operationTimer = null
            notificationTimer = null
            exchangeTimer = null
            nextPollTimer = null
            retryTimer = null
            operation = null
            poll = null
            sake = null
            subscribed = false
            notifying = false
            handshakeWrites.clear()
            servicesToAdd.clear()
            addingService = null
            cgmCharacteristics = emptyMap()
            deviceInfo.clear()
            cleanupCall { stopAdvertising() }
            advertiser = null
            val oldClient = client
            client = null
            if (oldClient != null) {
                cleanupCall { oldClient.disconnect() }
                cleanupCall { oldClient.close() }
            }
            val oldPacingClient = pacingClient
            pacingClient = null
            if (oldPacingClient != null) {
                cleanupCall { oldPacingClient.disconnect() }
                cleanupCall { oldPacingClient.close() }
            }
            val oldServer = server
            val oldPeer = peer
            server = null
            peer = null
            sakeCharacteristic = null
            if (oldServer != null) {
                if (oldPeer != null) cleanupCall { oldServer.cancelConnection(oldPeer) }
                cleanupCall { oldServer.close() }
            }
        }
    }

    companion object {
        private const val DIAGNOSTIC_TAG = "GlucoStridePairing"
        private const val LOCAL_NAME = "Mobile Gluco"
        private const val OPERATION_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val PACING_SETTLE_MS = 500L
        private const val HANDSHAKE_TIMEOUT_MS = 45_000L
        private const val PAIRING_WAIT_MS = 120_000L
        private const val RECONNECT_WAIT_MS = 60_000L
        private const val EXCHANGE_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 15_000L
        private const val MAX_HANDSHAKE_WRITES = 4
        private const val MAX_QUEUED_CALLBACKS = 64
    }
}
