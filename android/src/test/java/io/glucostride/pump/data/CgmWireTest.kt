/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Synthetic protocol fixtures; no pump captures or identifying data.
 */
package io.glucostride.pump.data

import io.glucostride.core.SensorStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CgmWireTest {
    @Test
    fun featureSupportsBothExplicitCrcLayouts() {
        for (crc in listOf(false, true)) {
            val parsed = CgmWire.feature(CgmFixtures.feature(crc))
            assertEquals(crc, parsed.useCrc)
            assertEquals(9, parsed.type)
            assertEquals(5, parsed.location)
            assertTrue(parsed.isPatientSample)
        }
    }

    @Test
    fun crcIsThePinnedMedtronicDialectNotSilentAlgorithmFallback() {
        assertEquals(0x29b1, CgmWire.medtronicCrc("123456789".toByteArray(Charsets.US_ASCII)))
        val sigVector = CgmFixtures.bytes(0x3e, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertEquals(0x562e, CgmWire.medtronicCrc(sigVector))
        // SIG CGMS 3.11 defines reflected CRC wire bytes 01 2F for this synthetic vector.
        assertNotEquals(0x2f01, CgmWire.medtronicCrc(sigVector))
    }

    @Test
    fun featureDoesNotTreatFfffAsCrcBypassWhenCapabilityIsSet() {
        val bad = CgmFixtures.feature(true)
        bad[4] = 0xff.toByte()
        bad[5] = 0xff.toByte()
        invalid { CgmWire.feature(bad) }
        val noCrc = CgmFixtures.feature()
        noCrc[4] = 0
        invalid { CgmWire.feature(noCrc) }
    }

    @Test
    fun everyFeatureTruncationAndExtraOctetIsRejected() {
        for (crc in listOf(false, true)) {
            val feature = CgmFixtures.feature(crc)
            for (length in 0 until feature.size) invalid { CgmWire.feature(feature.copyOf(length)) }
            invalid { CgmWire.feature(feature + byteArrayOf(0)) }
        }
    }

    @Test
    fun unknownFeatureBitsTypesAndLocationsAreNotGuessed() {
        invalid { CgmWire.feature(CgmFixtures.feature(bits = CgmFixtures.allFeatures or 0x020000)) }
        for (type in listOf(0, 11, 12, 13, 14, 15)) {
            invalid { CgmWire.feature(CgmFixtures.feature(typeLocation = 0x50 or type)) }
        }
        for (location in listOf(0, 6, 7, 8, 9, 10, 11, 12, 13, 14)) {
            invalid { CgmWire.feature(CgmFixtures.feature(typeLocation = (location shl 4) or 9)) }
        }
        assertFalse(CgmWire.feature(CgmFixtures.feature(typeLocation = 0x4a)).isPatientSample)
    }

    @Test
    fun allOptionalFieldCombinationsUseTheirOwnFlagsAndWireOrder() {
        for (crc in listOf(false, true)) for (combination in 0..31) {
            val hasTrend = combination and 1 != 0
            val hasQuality = combination and 2 != 0
            val hasStatus = combination and 4 != 0
            val hasCalTemp = combination and 8 != 0
            val hasWarning = combination and 16 != 0
            val parsed = CgmWire.measurement(
                CgmFixtures.measurement(
                    offset = 40_000, glucose = 0xf4d2,
                    trend = 0xef85.takeIf { hasTrend }, quality = 100.takeIf { hasQuality },
                    status = 2.takeIf { hasStatus }, calTemp = 4.takeIf { hasCalTemp },
                    warning = 1.takeIf { hasWarning }, crc = crc,
                ),
                CgmWire.feature(CgmFixtures.feature(crc)),
            )
            assertEquals(40_000, parsed.offsetMinutes)
            assertEquals(123.4, parsed.glucoseMgDl!!, 0.000001)
            if (hasTrend) assertEquals(-1.23, parsed.trendMgDlPerMinute!!, 0.000001)
            else assertNull(parsed.trendMgDlPerMinute)
            assertEquals(if (hasQuality) 100.0 else null, parsed.quality)
            assertEquals(
                (if (hasStatus) 2 else 0) or (if (hasCalTemp) 0x400 else 0) or
                    (if (hasWarning) 0x10000 else 0),
                parsed.annunciation,
            )
        }
    }

    @Test
    fun missingOptionalBytesAndTrailingBytesAreRejectedWithoutBoundsExceptions() {
        for (crc in listOf(false, true)) {
            val feature = CgmWire.feature(CgmFixtures.feature(crc))
            val full = CgmFixtures.measurement(
                trend = 25, quality = 100, status = 2, calTemp = 4, warning = 1, crc = crc,
            )
            for (length in 0 until full.size) invalid { CgmWire.measurement(full.copyOf(length), feature) }
            invalid { CgmWire.measurement(full + byteArrayOf(0), feature) }
        }
        val feature = CgmWire.feature(CgmFixtures.feature())
        for (flag in listOf(1, 2, 0x20, 0x40, 0x80)) {
            val promised = CgmFixtures.measurement().also { it[1] = flag.toByte() }
            invalid { CgmWire.measurement(promised, feature) }
        }
        val trailing = CgmFixtures.measurement() + byteArrayOf(0)
        trailing[0] = trailing.size.toByte()
        invalid { CgmWire.measurement(trailing, feature) }
    }

    @Test
    fun reservedMeasurementFlagsAndUnsupportedOptionsFailClosed() {
        val feature = CgmWire.feature(CgmFixtures.feature())
        for (bit in 2..4) {
            val bad = CgmFixtures.measurement().also { it[1] = (1 shl bit).toByte() }
            invalid { CgmWire.measurement(bad, feature) }
        }
        val noOptions = CgmWire.feature(CgmFixtures.feature(bits = 0))
        invalid { CgmWire.measurement(CgmFixtures.measurement(trend = 1), noOptions) }
        invalid { CgmWire.measurement(CgmFixtures.measurement(quality = 100), noOptions) }
        invalid { CgmWire.measurement(CgmFixtures.measurement(crc = true), feature) }
    }

    @Test
    fun everyProtectedCharacteristicRejectsCorruption() {
        val feature = CgmFixtures.feature(true).also { it[0] = (it[0].toInt() xor 1).toByte() }
        invalid { CgmWire.feature(feature) }
        val parsedFeature = CgmWire.feature(CgmFixtures.feature(true))
        val measurement = CgmFixtures.measurement(crc = true).also { it[2] = (it[2].toInt() xor 1).toByte() }
        invalid { CgmWire.measurement(measurement, parsedFeature) }
        val status = CgmFixtures.status(crc = true).also { it[0] = (it[0].toInt() xor 1).toByte() }
        invalid { CgmWire.currentStatus(status, true) }
        val session = CgmFixtures.session(crc = true).also { it[3] = (it[3].toInt() xor 1).toByte() }
        invalid { CgmWire.sessionEpochMillis(session, true) }
    }

    @Test
    fun currentStatusIsAlwaysThreeStatusOctetsWithUnsignedOffset() {
        for (crc in listOf(false, true)) {
            val full = CgmFixtures.status(65_535, 0x000102, crc)
            val parsed = CgmWire.currentStatus(full, crc)
            assertEquals(65_535, parsed.offsetMinutes)
            assertEquals(0x000102, parsed.annunciation)
            for (length in 0 until full.size) invalid { CgmWire.currentStatus(full.copyOf(length), crc) }
            invalid { CgmWire.currentStatus(full + byteArrayOf(0), crc) }
        }
    }

    @Test
    fun specialsAreFullSfloatCodesRatherThanMantissaClamps() {
        for (special in 0x07fe..0x0802) {
            assertNull(CgmWire.sfloat(special))
            assertNotNull(CgmWire.sfloat(special or 0xf000))
        }
        assertEquals(204.7, CgmWire.sfloat(0xf7ff)!!, 0.000001)
        assertEquals(-204.8, CgmWire.sfloat(0xf800)!!, 0.000001)
        assertEquals(0.00000001, CgmWire.sfloat(0x8001)!!, 0.0000000001)
        assertEquals(20_470_000_000.0, CgmWire.sfloat(0x77ff)!!, 0.0)
        assertEquals(-20_480_000_000.0, CgmWire.sfloat(0x7800)!!, 0.0)
        assertEquals(0.0, CgmWire.sfloat(0)!!, 0.0)
    }

    @Test
    fun zoneAndDstAreAddedToLocalOffsetThenSubtractedToFindUtc() {
        for (zone in listOf(-48, -20, 0, 4, 23, 56)) {
            for (dst in listOf(0, 2, 4, 8)) for (crc in listOf(false, true)) {
                assertEquals(
                    CgmFixtures.start,
                    CgmWire.sessionEpochMillis(CgmFixtures.session(zoneQuarters = zone, dstQuarters = dst, crc = crc), crc),
                )
            }
        }
        val direct = CgmFixtures.bytes(0xea, 7, 1, 1, 7, 45, 0, 23, 8)
        assertEquals(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), CgmWire.sessionEpochMillis(direct, false))
    }

    @Test
    fun unknownOrInvalidSourceComponentsDoNotAcquireDefaults() {
        for ((index, invalidValues) in listOf(
            2 to listOf(0, 13), 3 to listOf(0, 32), 4 to listOf(24),
            5 to listOf(60), 6 to listOf(60),
            7 to listOf(-128, -49, 57), 8 to listOf(1, 3, 5, 7, 9, 255),
        )) {
            for (value in invalidValues) {
                val bytes = CgmFixtures.session().also { it[index] = value.toByte() }
                assertNull(CgmWire.sessionEpochMillis(bytes, false))
            }
        }
        for (year in listOf(0, 1581, 10000, 65535)) {
            val bytes = CgmFixtures.session().also { it[0] = year.toByte(); it[1] = (year ushr 8).toByte() }
            assertNull(CgmWire.sessionEpochMillis(bytes, false))
        }
        val nonLeap = CgmFixtures.session().also { it[2] = 2; it[3] = 29 }
        assertNull(CgmWire.sessionEpochMillis(nonLeap, false))
        val leap = nonLeap.copyOf().also { it[0] = 0xe8.toByte() }
        assertNotNull(CgmWire.sessionEpochMillis(leap, false))
        assertNull(CgmWire.sessionEpochMillis(byteArrayOf(), false))
        for (length in 1..8) invalid { CgmWire.sessionEpochMillis(CgmFixtures.session().copyOf(length), false) }
        invalid { CgmWire.sessionEpochMillis(CgmFixtures.session() + byteArrayOf(0), false) }
    }

    @Test
    fun statusDistinguishesKnownWarningsFaultsAndUnknownBits() {
        val feature = CgmWire.feature(CgmFixtures.feature())
        for (bits in listOf(0, 2, 0x400, 0x10000, 0x20000, 0x40000, 0x80000, 0x100000, 0x200000)) {
            assertEquals(SensorStatus.OK, CgmWire.status(bits, feature))
        }
        for (bits in listOf(4, 8, 16, 32, 64, 128, 0x800, 0x1000, 0x2000, 0x8000, 0x400000, 0x800000)) {
            assertEquals(SensorStatus.SENSOR_ERROR, CgmWire.status(bits, feature))
        }
        assertEquals(SensorStatus.TIME_UNKNOWN, CgmWire.status(0x100, feature))
        for (bits in listOf(1, 0x200, 0x4000)) {
            assertEquals(SensorStatus.UNAVAILABLE, CgmWire.status(bits, feature))
        }
        val noCapabilities = CgmWire.feature(CgmFixtures.feature(bits = 0))
        assertEquals(SensorStatus.SENSOR_ERROR, CgmWire.status(2, noCapabilities))
        assertEquals(SensorStatus.SENSOR_ERROR, CgmWire.status(8, noCapabilities))
    }

    @Test
    fun contradictoryHighLowAndRiseFallFlagsAreMalformed() {
        for (bits in listOf(0x3000, 0x030000, 0x0c0000, 0x300000, 0xc00000)) {
            invalid { CgmWire.currentStatus(CgmFixtures.status(bits = bits), false) }
        }
    }

    private fun invalid(block: () -> Unit) {
        try {
            block()
            fail("Expected sanitized parse failure")
        } catch (error: PumpDataException) {
            assertEquals("Invalid CGM data", error.message)
            assertNull(error.cause)
        }
    }
}
