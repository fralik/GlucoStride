/*
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Adapted from GlycemicGPT/android-unofficial CgmFeature.kt, CgmMeasurement.kt and
 * MedtronicCodec.kt at 59e68104df17614c50173bed954843a5f56e588b (GPL-3.0).
 * Those parsers were ported from OpenMinimed PythonPumpConnector (GPL-3.0).
 * Copyright (C) OpenMinimed contributors: palmarci (Pal Marci), drfubar,
 * Morten Fyhn Amundsen, Stenium; original medtronic-bt-decrypt PoC by @planiitis.
 *
 * Layout/status/time references: Bluetooth SIG CGMS 1.0.2 sections 1.7, 3.1-3.4,
 * 3.10; GATT Specification Supplement sections 3.42-3.47, 3.79, 3.86, 3.255.
 * This is a Medtronic parser, not a general-purpose Bluetooth CGM implementation.
 */
package io.glucostride.pump.data

import io.glucostride.core.SensorStatus
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.pow

/** Deliberately never contains source bytes, glucose, device identifiers, or causes. */
class PumpDataException(message: String = "Invalid CGM data") : Exception(message)

internal data class CgmFeature(
    val bits: Int,
    val type: Int,
    val location: Int,
) {
    val useCrc: Boolean get() = bits and 0x1000 != 0
    val isPatientSample: Boolean get() = type != 0x0a && location != 0x04
}

internal data class CgmMeasurement(
    val offsetMinutes: Int,
    val glucoseMgDl: Double?,
    val trendMgDlPerMinute: Double?,
    val quality: Double?,
    val annunciation: Int,
    val invalidNumber: Boolean,
)

internal data class CgmCurrentStatus(val offsetMinutes: Int, val annunciation: Int)

internal object CgmWire {
    private const val KNOWN_FEATURES = 0x01ffff
    private const val KNOWN_MEASUREMENT_FLAGS = 0xe3

    fun feature(bytes: ByteArray): CgmFeature {
        check(bytes.size == 6)
        val bits = uint(bytes, 0, 3)
        check(bits and KNOWN_FEATURES.inv() == 0)
        val result = CgmFeature(bits, uint(bytes, 3) and 0x0f, uint(bytes, 3) ushr 4)
        check(result.type in 1..10)
        check(result.location in 1..5 || result.location == 15)
        if (result.useCrc) verifyCrc(bytes) else check(uint(bytes, 4, 2) == 0xffff)
        // CGMS mandates mg/dL; type/location are NOT a unit selector. No unit is inferred
        // from a numerical range, pump display preference, or the phone's locale.
        return result
    }

    fun measurement(bytes: ByteArray, feature: CgmFeature): CgmMeasurement {
        val end = payloadEnd(bytes, feature.useCrc, 6)
        check(uint(bytes, 0) == bytes.size)
        val flags = uint(bytes, 1)
        check(flags and KNOWN_MEASUREMENT_FLAGS.inv() == 0)
        check(flags and 1 == 0 || feature.bits and 0x8000 != 0)
        check(flags and 2 == 0 || feature.bits and 0x10000 != 0)
        var cursor = 6
        fun consume(count: Int): Int {
            check(cursor + count <= end)
            val value = uint(bytes, cursor, count)
            cursor += count
            return value
        }
        var annunciation = 0
        if (flags and 0x80 != 0) annunciation = consume(1)
        if (flags and 0x40 != 0) annunciation = annunciation or (consume(1) shl 8)
        if (flags and 0x20 != 0) annunciation = annunciation or (consume(1) shl 16)
        val glucose = sfloat(uint(bytes, 2, 2))
        val trend = if (flags and 1 != 0) sfloat(consume(2)) else null
        val quality = if (flags and 2 != 0) sfloat(consume(2)) else null
        check(cursor == end)
        checkContradictoryStatus(annunciation)
        return CgmMeasurement(
            uint(bytes, 4, 2), glucose, trend, quality, annunciation,
            glucose == null || glucose < 0 || (flags and 1 != 0 && trend == null) ||
                (flags and 2 != 0 && quality == null),
        )
    }

    fun currentStatus(bytes: ByteArray, useCrc: Boolean): CgmCurrentStatus {
        check(payloadEnd(bytes, useCrc, 5) == 5)
        val annunciation = uint(bytes, 2, 3)
        checkContradictoryStatus(annunciation)
        return CgmCurrentStatus(uint(bytes, 0, 2), annunciation)
    }

    /** Null is unknown/invalid source time, never an invitation to use local or receipt time. */
    fun sessionEpochMillis(bytes: ByteArray, useCrc: Boolean): Long? {
        val local = sessionLocalMillis(bytes, useCrc) ?: return null
        val zoneQuarters = bytes[7].toInt()
        val dstQuarters = uint(bytes, 8)
        if (zoneQuarters == -128 || dstQuarters == 255) return null
        return local - (zoneQuarters + dstQuarters) * 900_000L
    }

    /** Wall-clock fields represented on a UTC axis for subtraction, NOT an inferred time zone. */
    fun sessionLocalMillis(bytes: ByteArray, useCrc: Boolean): Long? {
        if (bytes.isEmpty()) return null
        check(payloadEnd(bytes, useCrc, 9) == 9)
        val zoneQuarters = bytes[7].toInt()
        val dstQuarters = uint(bytes, 8)
        if ((zoneQuarters !in -48..56 && zoneQuarters != -128) ||
            dstQuarters !in intArrayOf(0, 2, 4, 8, 255)
        ) return null
        return dateTime(bytes)?.toInstant(ZoneOffset.UTC)?.toEpochMilli()
    }

    fun currentTimeLocalMillis(bytes: ByteArray): Long? {
        check(bytes.size == 10)
        check(uint(bytes, 7) in 0..7 && uint(bytes, 9) and 0xf0 == 0)
        val local = dateTime(bytes) ?: return null
        val day = uint(bytes, 7)
        check(day == 0 || day == local.dayOfWeek.value)
        return local.toInstant(ZoneOffset.UTC).toEpochMilli() + uint(bytes, 8) * 1000L / 256
    }

    private fun dateTime(bytes: ByteArray): LocalDateTime? {
        val year = uint(bytes, 0, 2)
        if (year !in 1582..9999) return null
        return try {
            LocalDateTime.of(
                year, uint(bytes, 2), uint(bytes, 3), uint(bytes, 4),
                uint(bytes, 5), uint(bytes, 6),
            )
        } catch (_: DateTimeException) {
            null
        }
    }

    fun status(annunciation: Int, feature: CgmFeature): SensorStatus {
        if (annunciation and 0x0080c0 != 0) return SensorStatus.SENSOR_ERROR
        if (annunciation and 0x000100 != 0) return SensorStatus.TIME_UNKNOWN
        // Never ignore an asserted fault merely because its capability bit is absent.
        if (hasUnsupportedStatus(annunciation, feature.bits)) return SensorStatus.SENSOR_ERROR
        if (annunciation and 0xc0383c != 0) return SensorStatus.SENSOR_ERROR
        if (annunciation and 0x000001 != 0) return SensorStatus.UNAVAILABLE
        // SIG has no dedicated warm-up bit. Calibration-not-allowed/pending can occur
        // during startup, but do not prove warm-up. Suppress without inventing that diagnosis.
        if (annunciation and 0x004200 != 0) return SensorStatus.UNAVAILABLE
        // Battery-low, calibration-recommended, and glucose/rate threshold warnings
        // do not themselves invalidate the measured concentration.
        return SensorStatus.OK
    }

    private fun hasUnsupportedStatus(status: Int, features: Int): Boolean {
        val requirements = intArrayOf(
            0x004e00, 0x030000, 0x040000, 0x080000, 0x300000, 0x000010,
            0x000008, 0x003000, 0xc00000, 0x000002, 0x000004, 0x000020,
        )
        return requirements.indices.any {
            status and requirements[it] != 0 && features and (1 shl it) == 0
        }
    }

    private fun checkContradictoryStatus(status: Int) {
        for (pair in intArrayOf(0x003000, 0x030000, 0x0c0000, 0x300000, 0xc00000)) {
            check(status and pair != pair)
        }
    }

    /**
     * The reference Medtronic protocol uses CCITT-FALSE, seed FFFF, LE checksum.
     * This intentionally differs from the reflected SIG CGMS 3.11 example (012F).
     * Do not silently accept both algorithms or fall back after a checksum failure.
     * Hardware conformance of this vendor dialect remains a physical-device gate.
     */
    fun medtronicCrc(bytes: ByteArray, length: Int = bytes.size): Int {
        check(length in 0..bytes.size)
        var crc = 0xffff
        for (i in 0 until length) {
            crc = crc xor (uint(bytes, i) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xffff
            }
        }
        return crc
    }

    fun sfloat(raw: Int): Double? {
        val mantissaBits = raw and 0x0fff
        // Specials have exponent zero (SIG Personal Health Devices Transcoding 2.2.2).
        // Masking the mantissa here would incorrectly reject ordinary finite SFLOATs.
        if (raw in 0x07fe..0x0802) return null
        val exponent = (raw ushr 12 and 0x0f).let { if (it >= 8) it - 16 else it }
        val mantissa = if (mantissaBits >= 0x0800) mantissaBits - 0x1000 else mantissaBits
        return mantissa.toDouble() * 10.0.pow(exponent)
    }

    private fun payloadEnd(bytes: ByteArray, useCrc: Boolean, minimum: Int): Int {
        val crcSize = if (useCrc) 2 else 0
        check(bytes.size >= minimum + crcSize)
        if (useCrc) verifyCrc(bytes)
        return bytes.size - crcSize
    }

    private fun verifyCrc(bytes: ByteArray) {
        check(bytes.size >= 2)
        check(medtronicCrc(bytes, bytes.size - 2) == uint(bytes, bytes.size - 2, 2))
    }

    private fun uint(bytes: ByteArray, offset: Int, count: Int = 1): Int {
        check(offset >= 0 && count in 1..3 && offset + count <= bytes.size)
        var value = 0
        for (i in 0 until count) value = value or ((bytes[offset + i].toInt() and 0xff) shl (8 * i))
        return value
    }

    private fun check(condition: Boolean) {
        if (!condition) throw PumpDataException()
    }
}
