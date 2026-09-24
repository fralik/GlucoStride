/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Synthetic fixtures only; no captured health data.
 */
package io.glucostride.pump.data

import io.glucostride.pump.RawCgmRecord
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

internal object CgmFixtures {
    val start: Long = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
    const val elapsed = 1_000_000L
    const val allFeatures = 0x01efff

    fun feature(crc: Boolean = false, bits: Int = allFeatures, typeLocation: Int = 0x59): ByteArray {
        val flags = if (crc) bits or 0x1000 else bits and 0x1000.inv()
        val payload = bytes(flags, flags ushr 8, flags ushr 16, typeLocation)
        return if (crc) crc(payload) else payload + bytes(0xff, 0xff)
    }

    fun session(
        epochMillis: Long = start,
        zoneQuarters: Int = 0,
        dstQuarters: Int = 0,
        crc: Boolean = false,
    ): ByteArray {
        val local = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(epochMillis),
            ZoneOffset.ofTotalSeconds((zoneQuarters + dstQuarters) * 900),
        )
        val payload = bytes(
            local.year, local.year ushr 8, local.monthValue, local.dayOfMonth,
            local.hour, local.minute, local.second, zoneQuarters, dstQuarters,
        )
        return if (crc) crc(payload) else payload
    }

    fun measurement(
        offset: Int = 60,
        glucose: Int = 123,
        trend: Int? = null,
        quality: Int? = null,
        status: Int? = null,
        calTemp: Int? = null,
        warning: Int? = null,
        crc: Boolean = false,
    ): ByteArray {
        var flags = 0
        var optional = byteArrayOf()
        if (status != null) { flags = flags or 0x80; optional += bytes(status) }
        if (calTemp != null) { flags = flags or 0x40; optional += bytes(calTemp) }
        if (warning != null) { flags = flags or 0x20; optional += bytes(warning) }
        if (trend != null) { flags = flags or 1; optional += bytes(trend, trend ushr 8) }
        if (quality != null) { flags = flags or 2; optional += bytes(quality, quality ushr 8) }
        val payload = bytes(
            6 + optional.size + if (crc) 2 else 0, flags, glucose, glucose ushr 8, offset, offset ushr 8,
        ) + optional
        return if (crc) crc(payload) else payload
    }

    fun status(offset: Int = 61, bits: Int = 0, crc: Boolean = false): ByteArray {
        val payload = bytes(offset, offset ushr 8, bits, bits ushr 8, bits ushr 16)
        return if (crc) crc(payload) else payload
    }

    fun record(
        offset: Int = 60,
        ageMillis: Long = 90_000,
        sessionStart: Long = start,
        receivedElapsed: Long = elapsed,
        crc: Boolean = false,
        glucose: Int = 123,
        trend: Int? = null,
        quality: Int? = null,
        measurementStatus: Int = 0,
        currentStatus: Int = 0,
        featureBits: Int = allFeatures,
    ): RawCgmRecord = RawCgmRecord(
        feature(crc, featureBits),
        session(sessionStart, crc = crc),
        session(sessionStart, crc = crc),
        status(offset + (ageMillis / 60_000).toInt(), currentStatus, crc),
        measurement(
            offset, glucose, trend, quality,
            status = (measurementStatus and 0xff).takeIf { it != 0 },
            calTemp = (measurementStatus ushr 8 and 0xff).takeIf { it != 0 },
            warning = (measurementStatus ushr 16 and 0xff).takeIf { it != 0 },
            crc = crc,
        ),
        sessionStart + offset * 60_000L + ageMillis,
        receivedElapsed,
    )

    fun bytes(vararg values: Int): ByteArray = values.map { it.toByte() }.toByteArray()

    fun crc(payload: ByteArray): ByteArray {
        var remainder = 0xffff
        for (octet in payload) {
            for (bit in 7 downTo 0) {
                val input = (octet.toInt() ushr bit) and 1
                val top = (remainder ushr 15) and 1
                remainder = (remainder shl 1) and 0xffff
                if (input != top) remainder = remainder xor 0x1021
            }
        }
        return payload + bytes(remainder, remainder ushr 8)
    }
}
