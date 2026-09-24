package io.glucostride.compat

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Parcelable

internal object BluetoothCompat {
    // ATT defines 0x08 on older stacks too; Android exposed the named constant only in API 33.
    const val GATT_INSUFFICIENT_AUTHORIZATION = 0x08

    // Callers serialize writes/notifications and wait for completion before the next operation.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun notify(
        server: BluetoothGattServer, device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic, value: ByteArray,
    ): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return server.notifyCharacteristicChanged(device, characteristic, false, value)
        }
        return if (characteristic.setValue(value.copyOf()) &&
            server.notifyCharacteristicChanged(device, characteristic, false)
        ) BluetoothStatusCodes.SUCCESS else BluetoothStatusCodes.ERROR_UNKNOWN
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun writeCharacteristic(
        gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray,
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        }
        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return characteristic.setValue(value.copyOf()) && gatt.writeCharacteristic(characteristic)
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun writeDescriptor(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        }
        return descriptor.setValue(value.copyOf()) && gatt.writeDescriptor(descriptor)
    }

    @Suppress("DEPRECATION")
    fun deviceExtra(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra<Parcelable>(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
        }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Legacy branch only accepts protected system broadcasts.
    fun registerReceiver(context: Context, receiver: BroadcastReceiver, action: String) {
        require(action == BluetoothAdapter.ACTION_STATE_CHANGED || action == BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }
}
