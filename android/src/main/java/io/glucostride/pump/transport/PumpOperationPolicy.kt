/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from GlycemicGPT/android-unofficial MedtronicProtocol.kt at
 * 59e68104df17614c50173bed954843a5f56e588b (GlycemicGPT contributors).
 * Protocol inventory derives from OpenMinimed contributors: Pal Marci (palmarci),
 * drfubar, Morten Fyhn Amundsen, Stenium; original PoC by planiitis.
 * GlucoStride changes: CGM-only inventory and exact, service-scoped operation allowlist.
 */
package io.glucostride.pump.transport

import java.util.UUID

internal object PumpUuids {
    fun sig(code: Int): UUID =
        UUID.fromString("0000${code.toString(16).padStart(4, '0')}-0000-1000-8000-00805f9b34fb")

    fun vendor(code: Int): UUID =
        UUID.fromString("0000${code.toString(16).padStart(4, '0')}-0000-1000-0000-009132591325")

    val FIRST_PAIR = sig(0xfe82)
    val RECONNECT = sig(0xfe81)
    val SAKE = vendor(0xfe82)
    val DEVICE_INFO = vendor(0x0900)
    val CGM = sig(0x181f)
    val CURRENT_TIME_SERVICE = sig(0x1805)
    val CURRENT_TIME = sig(0x2a2b)
    val FEATURE = sig(0x2aa8)
    val SESSION_START = sig(0x2aaa)
    val STATUS = sig(0x2aa9)
    val MEASUREMENT = sig(0x2aa7)
    val RACP = sig(0x2a52)
    val CCCD = sig(0x2902)
}

/** The only outbound pump operations, apart from the separate SAKE server handshake. */
internal object PumpOperationPolicy {
    fun permitsRead(service: UUID, characteristic: UUID): Boolean =
        (service == PumpUuids.CGM && characteristic in
            setOf(PumpUuids.FEATURE, PumpUuids.SESSION_START, PumpUuids.STATUS)) ||
            (service == PumpUuids.CURRENT_TIME_SERVICE && characteristic == PumpUuids.CURRENT_TIME)

    fun permitsWrite(service: UUID, characteristic: UUID, value: ByteArray): Boolean =
        service == PumpUuids.CGM && characteristic == PumpUuids.RACP &&
            value.contentEquals(byteArrayOf(1, 6))

    fun permitsDescriptorWrite(
        service: UUID,
        characteristic: UUID,
        descriptor: UUID,
        value: ByteArray,
    ): Boolean =
        service == PumpUuids.CGM &&
            characteristic in setOf(PumpUuids.MEASUREMENT, PumpUuids.RACP) &&
            descriptor == PumpUuids.CCCD && value.size == 2 && value[1] == 0.toByte() &&
            value[0].toInt() in 0..2
}
