package io.glucostride.bridge

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import io.glucostride.compat.BluetoothCompat
import io.glucostride.core.LiveGlucoseProtocol
import java.util.UUID

@SuppressLint("MissingPermission") // Service verifies grants; revocation is handled at each entry point.
class GlucoseGattServer(
    private val context: Context,
    private val packet: () -> ByteArray,
    private val onTransport: (String, Int, Int) -> Unit,
    private val onIssue: (String) -> Unit,
    private val onFatal: (String) -> Unit,
    private val excludedPeer: String,
    private val onNotification: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val serviceId = UUID.fromString(LiveGlucoseProtocol.SERVICE_UUID)
    private val valueId = UUID.fromString(LiveGlucoseProtocol.CHARACTERISTIC_UUID)
    private val cccdId = UUID.fromString(LiveGlucoseProtocol.CCCD_UUID)
    private val characteristic = BluetoothGattCharacteristic(
        valueId,
        BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_READ,
    ).apply {
        addDescriptor(BluetoothGattDescriptor(
            cccdId,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        ))
    }
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val connected = linkedSetOf<BluetoothDevice>()
    private val subscriptions = linkedSetOf<BluetoothDevice>()
    private val pending = linkedMapOf<BluetoothDevice, ByteArray>()
    private var inFlight: BluetoothDevice? = null
    private var closed = false
    private var phase = "Opening GATT server"
    private var advertised = false

    private val startupTimeout = Runnable { fail("BLE startup timed out. Stop and retry.") }
    private val notificationTimeout = Runnable {
        fail("BLE notification stalled. Bridge stopped; restart it from the app.")
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) = dispatch {
            handler.removeCallbacks(startupTimeout)
            advertised = true
            phase = "Advertising live service"
            report()
        }

        override fun onStartFailure(errorCode: Int) = dispatch {
            fail("BLE advertising failed (code $errorCode). Check phone peripheral support.")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) = dispatch {
            if (service.uuid != serviceId) return@dispatch
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not register glucose service (GATT $status).")
            } else {
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build()
                val data = AdvertiseData.Builder()
                    .addServiceUuid(ParcelUuid(serviceId))
                    .setIncludeDeviceName(false)
                    .setIncludeTxPowerLevel(false)
                    .build()
                requireNotNull(advertiser).startAdvertising(settings, data, advertiseCallback)
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) =
            dispatch {
                if (!allowed(device)) return@dispatch
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    connected.add(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED ||
                    status != BluetoothGatt.GATT_SUCCESS) {
                    connected.remove(device)
                    subscriptions.remove(device)
                    pending.remove(device)
                    if (inFlight == device) {
                        inFlight = null
                        handler.removeCallbacks(notificationTimeout)
                        drain()
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) onIssue("BLE connection ended (GATT $status).")
                }
                report()
            }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            requested: BluetoothGattCharacteristic,
        ) = dispatch {
            if (!allowed(device)) {
                respond(device, requestId, BluetoothCompat.GATT_INSUFFICIENT_AUTHORIZATION, offset, null)
            } else if (requested.uuid != valueId) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            } else {
                read(device, requestId, offset, packet())
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) = dispatch {
            if (!allowed(device)) {
                respond(device, requestId, BluetoothCompat.GATT_INSUFFICIENT_AUTHORIZATION, offset, null)
            } else if (descriptor.uuid != cccdId || descriptor.characteristic.uuid != valueId) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
            } else {
                read(device, requestId, offset, if (device in subscriptions)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) = dispatch {
            val status = when {
                !allowed(device) -> BluetoothCompat.GATT_INSUFFICIENT_AUTHORIZATION
                descriptor.uuid != cccdId || descriptor.characteristic.uuid != valueId ->
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                preparedWrite -> BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                offset != 0 -> BluetoothGatt.GATT_INVALID_OFFSET
                value.size != 2 -> BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH
                !value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) &&
                    !value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) ->
                    BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED
                else -> BluetoothGatt.GATT_SUCCESS
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    connected.add(device)
                    subscriptions.add(device)
                    pending[device] = packet()
                } else {
                    subscriptions.remove(device)
                    pending.remove(device)
                }
            }
            if (responseNeeded) respond(device, requestId, status, offset, null)
            report()
            drain()
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) = dispatch {
            if (responseNeeded) respond(
                device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, offset, null,
            )
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = dispatch {
            respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = dispatch {
            if (device != inFlight) return@dispatch
            handler.removeCallbacks(notificationTimeout)
            inFlight = null
            if (status != BluetoothGatt.GATT_SUCCESS) onIssue("Notification failed (GATT $status).")
            else onNotification()
            drain()
        }
    }

    fun start() {
        check(Looper.myLooper() == Looper.getMainLooper())
        val manager = context.getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter ?: error("No Bluetooth adapter.")
        check(adapter.isEnabled) { "Turn on Bluetooth before starting." }
        advertiser = adapter.bluetoothLeAdvertiser ?: error("BLE advertising is not supported.")
        server = manager.openGattServer(context, callback) ?: error("Could not open a BLE GATT server.")
        val service = BluetoothGattService(serviceId, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(characteristic)
        check(requireNotNull(server).addService(service)) { "Could not queue BLE service registration." }
        handler.postDelayed(startupTimeout, 15_000)
        report()
    }

    fun publish() {
        if (closed) return
        try {
            val value = packet()
            subscriptions.forEach { pending[it] = value }
            drain()
        } catch (error: SecurityException) {
            fail("Bluetooth permission was revoked. Grant Nearby devices and restart.")
        }
    }

    fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (error: SecurityException) {
            onIssue("Advertising cleanup could not run after Bluetooth permission revocation.")
        }
        try {
            server?.close()
        } catch (error: SecurityException) {
            onIssue("GATT cleanup could not run after Bluetooth permission revocation.")
        }
        server = null
        advertiser = null
        connected.clear()
        subscriptions.clear()
        pending.clear()
        inFlight = null
    }

    private fun drain() {
        if (closed || inFlight != null) return
        while (pending.isNotEmpty()) {
            val next = pending.entries.first()
            val device = next.key
            val bytes = next.value
            pending.remove(device)
            if (device !in subscriptions) continue
            val result = BluetoothCompat.notify(requireNotNull(server), device, characteristic, bytes)
            if (result == BluetoothStatusCodes.SUCCESS) {
                inFlight = device
                handler.postDelayed(notificationTimeout, 10_000)
                return
            }
            onIssue("BLE rejected a notification (code $result). Will retry on the next heartbeat.")
        }
    }

    private fun read(device: BluetoothDevice, request: Int, offset: Int, bytes: ByteArray) {
        if (offset !in 0..bytes.size) {
            respond(device, request, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
        } else {
            respond(device, request, BluetoothGatt.GATT_SUCCESS, offset, bytes.copyOfRange(offset, bytes.size))
        }
    }

    private fun respond(device: BluetoothDevice, request: Int, status: Int, offset: Int, bytes: ByteArray?) {
        if (server?.sendResponse(device, request, status, offset, bytes) != true) {
            onIssue("BLE response could not be sent. The central may have disconnected.")
        }
    }

    private fun report() {
        onTransport(if (advertised && subscriptions.isNotEmpty()) {
            "Watch subscribed (see reading status)"
        } else phase,
            connected.size, subscriptions.size)
    }

    private fun allowed(device: BluetoothDevice): Boolean =
        !device.address.equals(excludedPeer, ignoreCase = true)

    private fun dispatch(block: () -> Unit) {
        handler.post {
            if (!closed) {
                try {
                    block()
                } catch (error: SecurityException) {
                    fail("Bluetooth permission was revoked. Grant Nearby devices and restart.")
                }
            }
        }
    }

    private fun fail(message: String) {
        if (closed) return
        close()
        onFatal(message)
    }
}
