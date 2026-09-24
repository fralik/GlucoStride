/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Synthetic source-time/status fixtures only.
 */
package io.glucostride.pump.data

import io.glucostride.core.SensorStatus
import io.glucostride.pump.RawCgmRecord
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class PumpReadingTrackerTest {
    @Test
    fun startsUnavailableAndClearRemovesAllVisibleState() {
        val tracker = PumpReadingTracker()
        hidden(SensorStatus.UNAVAILABLE, tracker.snapshot(CgmFixtures.elapsed))
        tracker.accept(CgmFixtures.record())
        assertEquals(SensorStatus.OK, tracker.snapshot(CgmFixtures.elapsed).status)
        tracker.clear()
        val cleared = tracker.snapshot(CgmFixtures.elapsed + 1)
        hidden(SensorStatus.UNAVAILABLE, cleared)
        assertNull(cleared.sampleIdentity)
        assertNull(cleared.measuredAtEpochMillis)
        assertNull(cleared.ageSeconds)
    }

    @Test
    fun clearAndReconnectPreserveDuplicateAgeDespiteEitherWallClockJump() {
        for (crc in listOf(false, true)) for (jump in listOf(-86_400_000L, 86_400_000L)) {
            val tracker = PumpReadingTracker()
            val original = CgmFixtures.record(crc = crc)
            tracker.accept(original)
            val before = tracker.snapshot(CgmFixtures.elapsed)
            tracker.clear()
            for (second in 1L..600L) {
                val hidden = tracker.snapshot(CgmFixtures.elapsed + second * 1000)
                hidden(SensorStatus.UNAVAILABLE, hidden)
                assertNull(hidden.ageSeconds)
                assertNull(hidden.measuredAtEpochMillis)
                assertNull(hidden.sampleIdentity)
            }
            tracker.accept(original.copy(
                receivedAtElapsedMillis = CgmFixtures.elapsed + 600_000,
                receivedAtEpochMillis = original.receivedAtEpochMillis + jump,
                sensorStatus = CgmFixtures.status(71, crc = crc),
            ))
            val after = tracker.snapshot(CgmFixtures.elapsed + 600_000)
            hidden(SensorStatus.STALE, after)
            assertEquals(690L, after.ageSeconds)
            assertEquals(before.sampleIdentity, after.sampleIdentity)
            assertEquals(before.measuredAtEpochMillis, after.measuredAtEpochMillis)
        }
    }

    @Test
    fun repeatedErrorClearsCannotResetTheClockAnchor() {
        val tracker = PumpReadingTracker()
        val original = CgmFixtures.record()
        tracker.accept(original)
        for (attempt in 1L..3L) {
            invalid(tracker, original.copy(measurement = byteArrayOf()))
            tracker.clear()
            hidden(SensorStatus.UNAVAILABLE, tracker.snapshot(CgmFixtures.elapsed + attempt * 90_000))
        }
        tracker.accept(original.copy(
            receivedAtElapsedMillis = CgmFixtures.elapsed + 300_000,
            receivedAtEpochMillis = original.receivedAtEpochMillis - 60_000,
            sensorStatus = CgmFixtures.status(66),
        ))
        val snapshot = tracker.snapshot(CgmFixtures.elapsed + 300_000)
        assertEquals(SensorStatus.OK, snapshot.status)
        assertEquals(390L, snapshot.ageSeconds)
    }

    @Test
    fun newSampleAfterReconnectKeepsMonotonicAgeAndCanHaveSameGlucose() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record())
        val original = tracker.snapshot(CgmFixtures.elapsed)
        tracker.clear()
        val next = CgmFixtures.record(offset = 65, receivedElapsed = CgmFixtures.elapsed + 300_000)
        tracker.accept(next.copy(receivedAtEpochMillis = next.receivedAtEpochMillis - 86_400_000))
        val snapshot = tracker.snapshot(CgmFixtures.elapsed + 300_000)
        assertEquals(SensorStatus.OK, snapshot.status)
        assertEquals(90L, snapshot.ageSeconds)
        assertEquals(original.glucoseMgDl, snapshot.glucoseMgDl)
        assertNotEquals(original.sampleIdentity, snapshot.sampleIdentity)
    }

    @Test
    fun clearDoesNotPermitAnOlderOrContradictorySampleToReappear() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record())
        tracker.clear()
        tracker.accept(CgmFixtures.record(offset = 59, receivedElapsed = CgmFixtures.elapsed + 60_000))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed + 60_000))
        tracker.clear()
        invalid(tracker, CgmFixtures.record(glucose = 124, receivedElapsed = CgmFixtures.elapsed + 120_000))
        hidden(SensorStatus.SENSOR_ERROR, tracker.snapshot(CgmFixtures.elapsed + 120_000))
    }

    @Test
    fun clearingBeforeFirstRecordDoesNotInventAnAnchor() {
        val tracker = PumpReadingTracker()
        tracker.clear()
        hidden(SensorStatus.UNAVAILABLE, tracker.snapshot(CgmFixtures.elapsed - 1))
        tracker.accept(CgmFixtures.record())
        assertEquals(90L, tracker.snapshot(CgmFixtures.elapsed).ageSeconds)
    }

    @Test
    fun sourceTimestampAndAgeComeFromSessionAndOffsetInEitherCrcMode() {
        for (crc in listOf(false, true)) {
            val tracker = PumpReadingTracker()
            tracker.accept(CgmFixtures.record(crc = crc, trend = 0xef85))
            val snapshot = tracker.snapshot(CgmFixtures.elapsed + 1234)
            assertEquals(SensorStatus.OK, snapshot.status)
            assertEquals(123.0, snapshot.glucoseMgDl!!, 0.0)
            assertEquals(-1.23, snapshot.trendMgDlPerMinute!!, 0.000001)
            assertEquals(91L, snapshot.ageSeconds)
            assertEquals(CgmFixtures.start + 3_600_000, snapshot.measuredAtEpochMillis)
            assertEquals("${CgmFixtures.start}:60", snapshot.sampleIdentity)
        }
    }

    @Test
    fun noClinicalRangeIsInventedForFiniteGlucoseOrTrend() {
        for ((raw, value) in listOf(1 to 1.0, 0 to 0.0, 1000 to 1000.0, 0x77ff to 20_470_000_000.0)) {
            val tracker = PumpReadingTracker()
            tracker.accept(CgmFixtures.record(glucose = raw, trend = raw))
            val snapshot = tracker.snapshot(CgmFixtures.elapsed)
            assertEquals(SensorStatus.OK, snapshot.status)
            assertEquals(value, snapshot.glucoseMgDl!!, 0.0)
            assertEquals(value, snapshot.trendMgDlPerMinute!!, 0.0)
        }
    }

    @Test
    fun negativeConcentrationIsInvalidButNegativeTrendIsNot() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record(glucose = 126, trend = 0x0fff))
        assertEquals(-1.0, tracker.snapshot(CgmFixtures.elapsed).trendMgDlPerMinute!!, 0.0)
        val invalid = PumpReadingTracker()
        invalid.accept(CgmFixtures.record(glucose = 0x0fff))
        hidden(SensorStatus.SENSOR_ERROR, invalid.snapshot(CgmFixtures.elapsed))
    }

    @Test
    fun staleBoundaryIncludesSourceAgeAndMillisecondRemainders() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record(ageMillis = 599_500))
        assertEquals(SensorStatus.OK, tracker.snapshot(CgmFixtures.elapsed + 499).status)
        val stale = tracker.snapshot(CgmFixtures.elapsed + 500)
        hidden(SensorStatus.STALE, stale)
        assertEquals(600L, stale.ageSeconds)
        assertNotNull(stale.measuredAtEpochMillis)
    }

    @Test
    fun firstRetrievedOldSampleIsAlreadyStale() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record(ageMillis = 3_600_000))
        val stale = tracker.snapshot(CgmFixtures.elapsed)
        hidden(SensorStatus.STALE, stale)
        assertEquals(3600L, stale.ageSeconds)
    }

    @Test
    fun repeatedLastRecordNeverRenewsAgeEvenWhenPhoneClockChanges() {
        for (jump in listOf(-86_400_000L, 86_400_000L)) {
            val tracker = PumpReadingTracker()
            val original = CgmFixtures.record()
            tracker.accept(original)
            val identity = tracker.snapshot(CgmFixtures.elapsed).sampleIdentity
            tracker.accept(original.copy(
                receivedAtEpochMillis = original.receivedAtEpochMillis + jump,
                receivedAtElapsedMillis = CgmFixtures.elapsed + 300_000,
                sensorStatus = CgmFixtures.status(66),
            ))
            val repeated = tracker.snapshot(CgmFixtures.elapsed + 300_000)
            assertEquals(identity, repeated.sampleIdentity)
            assertEquals(390L, repeated.ageSeconds)
            assertEquals(original.receivedAtEpochMillis - 90_000, repeated.measuredAtEpochMillis)
            tracker.accept(original.copy(
                receivedAtEpochMillis = original.receivedAtEpochMillis + jump,
                receivedAtElapsedMillis = CgmFixtures.elapsed + 600_000,
                sensorStatus = CgmFixtures.status(71),
            ))
            hidden(SensorStatus.STALE, tracker.snapshot(CgmFixtures.elapsed + 600_000))
        }
    }

    @Test
    fun newSampleWithSameGlucoseIsNotDeduplicatedAndIgnoresPhoneClockJump() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record())
        val old = tracker.snapshot(CgmFixtures.elapsed)
        val next = CgmFixtures.record(offset = 65, receivedElapsed = CgmFixtures.elapsed + 300_000)
        tracker.accept(next.copy(receivedAtEpochMillis = next.receivedAtEpochMillis - 86_400_000))
        val result = tracker.snapshot(CgmFixtures.elapsed + 300_000)
        assertEquals(SensorStatus.OK, result.status)
        assertEquals(old.glucoseMgDl, result.glucoseMgDl)
        assertNotEquals(old.sampleIdentity, result.sampleIdentity)
        assertEquals(90L, result.ageSeconds)
    }

    @Test
    fun repeatedSessionReadWithEquivalentAbsoluteZoneDoesNotReanchor() {
        val tracker = PumpReadingTracker()
        val original = CgmFixtures.record()
        tracker.accept(original)
        val source = CgmFixtures.session(zoneQuarters = 4, dstQuarters = 4)
        tracker.accept(original.copy(
            sessionStartBefore = source, sessionStartAfter = source.copyOf(),
            receivedAtElapsedMillis = CgmFixtures.elapsed + 60_000,
            receivedAtEpochMillis = original.receivedAtEpochMillis - 10_000_000,
            sensorStatus = CgmFixtures.status(62),
        ))
        assertEquals(150L, tracker.snapshot(CgmFixtures.elapsed + 60_000).ageSeconds)
    }

    @Test
    fun dateAndOffsetCrossMidnightAndUnsignedSignBoundary() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record(offset = 40_000, ageMillis = 30_000))
        val snapshot = tracker.snapshot(CgmFixtures.elapsed)
        assertEquals(SensorStatus.OK, snapshot.status)
        assertEquals(CgmFixtures.start + 40_000 * 60_000L, snapshot.measuredAtEpochMillis)
        assertEquals(30L, snapshot.ageSeconds)
    }

    @Test
    fun coherentNewSessionCanRestartOffsetButOldSessionCannotReappear() {
        val tracker = PumpReadingTracker()
        val old = CgmFixtures.record()
        tracker.accept(old)
        val start = CgmFixtures.start + 3_720_000
        val next = CgmFixtures.record(
            offset = 0, sessionStart = start, ageMillis = 30_000,
            receivedElapsed = CgmFixtures.elapsed + 60_000,
        )
        tracker.accept(next)
        val result = tracker.snapshot(CgmFixtures.elapsed + 60_000)
        assertEquals(SensorStatus.OK, result.status)
        assertEquals("$start:0", result.sampleIdentity)
        tracker.accept(old.copy(receivedAtElapsedMillis = CgmFixtures.elapsed + 120_000))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed + 120_000))
    }

    @Test
    fun unsignedMaximumOffsetIsValidButRolloverWithoutNewSessionIsNot() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record(offset = 65_535, ageMillis = 30_000))
        assertEquals(SensorStatus.OK, tracker.snapshot(CgmFixtures.elapsed).status)
        tracker.accept(CgmFixtures.record(offset = 0, receivedElapsed = CgmFixtures.elapsed + 60_000))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed + 60_000))
    }

    @Test
    fun changedSessionDuringReadHidesEvenIfBothDatesAreValid() {
        val tracker = PumpReadingTracker()
        val record = CgmFixtures.record()
        tracker.accept(record.copy(sessionStartAfter = CgmFixtures.session(CgmFixtures.start + 60_000)))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed))
    }

    @Test
    fun changedReferenceCannotRetimestampTheSameOffset() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record())
        tracker.accept(CgmFixtures.record(
            sessionStart = CgmFixtures.start + 60_000,
            receivedElapsed = CgmFixtures.elapsed + 60_000,
        ))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed + 60_000))
    }

    @Test
    fun backwardMeasurementOrCurrentStatusOffsetsAreNotFreshData() {
        for (backward in listOf(
            CgmFixtures.record(offset = 59, receivedElapsed = CgmFixtures.elapsed + 60_000),
            CgmFixtures.record(receivedElapsed = CgmFixtures.elapsed + 60_000).copy(sensorStatus = CgmFixtures.status(60)),
        )) {
            val tracker = PumpReadingTracker()
            tracker.accept(CgmFixtures.record())
            tracker.accept(backward)
            hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed + 60_000))
        }
    }

    @Test
    fun sourceAndStatusInFutureAreRejectedRatherThanClampedToAgeZero() {
        val base = CgmFixtures.record(ageMillis = 0)
        val candidates = listOf(
            base.copy(receivedAtEpochMillis = base.receivedAtEpochMillis - 1),
            base.copy(sensorStatus = CgmFixtures.status(61)),
            base.copy(sensorStatus = CgmFixtures.status(59)),
            base.copy(sessionStartBefore = CgmFixtures.session(base.receivedAtEpochMillis + 60_000),
                sessionStartAfter = CgmFixtures.session(base.receivedAtEpochMillis + 60_000)),
        )
        for (record in candidates) {
            val tracker = PumpReadingTracker()
            tracker.accept(record)
            hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed))
        }
    }

    @Test
    fun missingAndUnknownSourceTimesNeverUsePhoneTimezone() {
        val originalZone = TimeZone.getDefault()
        try {
            for (phoneZone in listOf("UTC", "America/New_York", "Asia/Kathmandu")) {
                TimeZone.setDefault(TimeZone.getTimeZone(phoneZone))
                for (session in listOf(
                    byteArrayOf(),
                    CgmFixtures.session().also { it[7] = 0x80.toByte() },
                    CgmFixtures.session().also { it[8] = 0xff.toByte() },
                    CgmFixtures.session().also { it[2] = 0 },
                    CgmFixtures.session().also { it[2] = 2; it[3] = 30 },
                )) {
                    val tracker = PumpReadingTracker()
                    tracker.accept(CgmFixtures.record().copy(sessionStartBefore = session, sessionStartAfter = session.copyOf()))
                    hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed))
                }
            }
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun unknownTimeInterruptionCannotResetPreviouslyAnchoredSampleAge() {
        val tracker = PumpReadingTracker()
        val base = CgmFixtures.record()
        tracker.accept(base)
        tracker.accept(base.copy(
            sessionStartBefore = byteArrayOf(), sessionStartAfter = byteArrayOf(),
            receivedAtElapsedMillis = CgmFixtures.elapsed + 300_000,
        ))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed + 300_000))
        tracker.accept(base.copy(
            receivedAtElapsedMillis = CgmFixtures.elapsed + 600_000,
            receivedAtEpochMillis = base.receivedAtEpochMillis - 1_000_000,
            sensorStatus = CgmFixtures.status(71),
        ))
        val result = tracker.snapshot(CgmFixtures.elapsed + 600_000)
        hidden(SensorStatus.STALE, result)
        assertEquals(690L, result.ageSeconds)
    }

    @Test
    fun currentFaultAndMeasurementFaultEachOverrideValidGlucose() {
        for (bits in listOf(4, 8, 16, 32, 64, 128, 0x800, 0x1000, 0x2000, 0x8000, 0x400000, 0x800000)) {
            for (measurementFlag in listOf(false, true)) {
                val tracker = PumpReadingTracker()
                tracker.accept(CgmFixtures.record(
                    trend = 1,
                    measurementStatus = if (measurementFlag) bits else 0,
                    currentStatus = if (measurementFlag) 0 else bits,
                ))
                hidden(SensorStatus.SENSOR_ERROR, tracker.snapshot(CgmFixtures.elapsed))
            }
        }
    }

    @Test
    fun synchronizationBitAlwaysSuppressesSourceTime() {
        for (isMeasurement in listOf(false, true)) {
            val tracker = PumpReadingTracker()
            tracker.accept(CgmFixtures.record(
                measurementStatus = if (isMeasurement) 0x100 else 0,
                currentStatus = if (isMeasurement) 0 else 0x100,
            ))
            val result = tracker.snapshot(CgmFixtures.elapsed)
            hidden(SensorStatus.TIME_UNKNOWN, result)
            assertNull(result.measuredAtEpochMillis)
            assertNull(result.ageSeconds)
        }
    }

    @Test
    fun stoppedOrWarmupLikeCalibrationStatesAreUnavailableNotInventedWarmup() {
        for (bits in listOf(1, 0x200, 0x4000)) for (isMeasurement in listOf(false, true)) {
            val tracker = PumpReadingTracker()
            tracker.accept(CgmFixtures.record(
                measurementStatus = if (isMeasurement) bits else 0,
                currentStatus = if (isMeasurement) 0 else bits,
            ))
            hidden(SensorStatus.UNAVAILABLE, tracker.snapshot(CgmFixtures.elapsed))
        }
    }

    @Test
    fun currentStatusRefreshDoesNotChangeSampleIdentityOrAge() {
        val tracker = PumpReadingTracker()
        val record = CgmFixtures.record()
        tracker.accept(record)
        val identity = tracker.snapshot(CgmFixtures.elapsed).sampleIdentity
        tracker.accept(record.copy(sensorStatus = CgmFixtures.status(bits = 8)))
        hidden(SensorStatus.SENSOR_ERROR, tracker.snapshot(CgmFixtures.elapsed))
        tracker.accept(record.copy(
            receivedAtElapsedMillis = CgmFixtures.elapsed + 60_000, sensorStatus = CgmFixtures.status(62),
        ))
        val recovered = tracker.snapshot(CgmFixtures.elapsed + 60_000)
        assertEquals(SensorStatus.OK, recovered.status)
        assertEquals(identity, recovered.sampleIdentity)
        assertEquals(150L, recovered.ageSeconds)
    }

    @Test
    fun thresholdWarningsAtDifferentTimesAreNotMistakenForContradictoryFlags() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record(measurementStatus = 0x010000, currentStatus = 0x020000))
        assertEquals(SensorStatus.OK, tracker.snapshot(CgmFixtures.elapsed).status)
    }

    @Test
    fun allSpecialSfloatFieldsSuppressValuesWithoutLeakingSentinels() {
        for (special in 0x07fe..0x0802) {
            for (record in listOf(
                CgmFixtures.record(glucose = special),
                CgmFixtures.record(trend = special),
                CgmFixtures.record(quality = special),
            )) {
                val tracker = PumpReadingTracker()
                tracker.accept(record)
                hidden(SensorStatus.SENSOR_ERROR, tracker.snapshot(CgmFixtures.elapsed))
            }
        }
    }

    @Test
    fun controlSolutionIsNotDisplayedAsPatientGlucose() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record().copy(feature = CgmFixtures.feature(typeLocation = 0x4a)))
        hidden(SensorStatus.UNAVAILABLE, tracker.snapshot(CgmFixtures.elapsed))
    }

    @Test
    fun malformedInputClearsVisibleValuesButCannotResetSampleClock() {
        val tracker = PumpReadingTracker()
        val base = CgmFixtures.record()
        tracker.accept(base)
        invalid(tracker, base.copy(measurement = byteArrayOf()))
        hidden(SensorStatus.SENSOR_ERROR, tracker.snapshot(CgmFixtures.elapsed))
        tracker.accept(base.copy(
            receivedAtElapsedMillis = CgmFixtures.elapsed + 600_000,
            sensorStatus = CgmFixtures.status(71),
        ))
        hidden(SensorStatus.STALE, tracker.snapshot(CgmFixtures.elapsed + 600_000))
    }

    @Test
    fun sameIdentityWithChangedMeasurementIsContradictory() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record())
        invalid(tracker, CgmFixtures.record(glucose = 124))
        hidden(SensorStatus.SENSOR_ERROR, tracker.snapshot(CgmFixtures.elapsed))
    }

    @Test
    fun typeAndSampleLocationCannotChangeWithinOneSession() {
        for (changed in listOf(0x58, 0xf9)) {
            val tracker = PumpReadingTracker()
            tracker.accept(CgmFixtures.record())
            invalid(tracker, CgmFixtures.record().copy(feature = CgmFixtures.feature(typeLocation = changed)))
            hidden(SensorStatus.SENSOR_ERROR, tracker.snapshot(CgmFixtures.elapsed))
        }
    }

    @Test
    fun monotonicRollbackRequiresNewTrackerNotMerelyClear() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record())
        assertEquals(SensorStatus.OK, tracker.snapshot(CgmFixtures.elapsed).status)
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed - 1))
        tracker.accept(CgmFixtures.record(receivedElapsed = CgmFixtures.elapsed + 60_000))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed + 60_000))
        tracker.clear()
        tracker.accept(CgmFixtures.record(receivedElapsed = CgmFixtures.elapsed + 120_000))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed + 120_000))
        val newServiceTracker = PumpReadingTracker()
        newServiceTracker.accept(CgmFixtures.record(receivedElapsed = 10))
        assertEquals(SensorStatus.OK, newServiceTracker.snapshot(10).status)
    }

    @Test
    fun backwardReceiptAndSnapshotBeforeReceiptNeverGenerateNegativeOrFreshAge() {
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record())
        tracker.accept(CgmFixtures.record(receivedElapsed = CgmFixtures.elapsed - 1))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed))
        val other = PumpReadingTracker()
        other.accept(CgmFixtures.record())
        hidden(SensorStatus.TIME_UNKNOWN, other.snapshot(CgmFixtures.elapsed - 1))
    }

    @Test
    fun invalidInitialClocksAndArithmeticOverflowFailClosed() {
        for (record in listOf(
            CgmFixtures.record().copy(receivedAtEpochMillis = -1),
            CgmFixtures.record(receivedElapsed = -1),
        )) {
            val tracker = PumpReadingTracker()
            tracker.accept(record)
            hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed))
        }
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record().copy(receivedAtEpochMillis = Long.MAX_VALUE))
        hidden(SensorStatus.TIME_UNKNOWN, tracker.snapshot(Long.MAX_VALUE))
    }

    @Test
    fun inputArraysAreNotRetainedAsMutableVisibleState() {
        val tracker = PumpReadingTracker()
        val record = CgmFixtures.record()
        tracker.accept(record)
        val before = tracker.snapshot(CgmFixtures.elapsed)
        record.measurement.fill(0)
        record.feature.fill(0)
        record.sessionStartBefore.fill(0)
        record.sessionStartAfter.fill(0)
        record.sensorStatus.fill(0)
        assertEquals(before, tracker.snapshot(CgmFixtures.elapsed))
    }

    private fun hidden(expected: SensorStatus, actual: PumpSnapshot) {
        assertEquals(expected, actual.status)
        assertNull(actual.glucoseMgDl)
        assertNull(actual.trendMgDlPerMinute)
    }

    private fun invalid(tracker: PumpReadingTracker, record: RawCgmRecord) {
        try {
            tracker.accept(record)
            fail("Expected sanitized failure")
        } catch (error: PumpDataException) {
            assertTrue(error.message == "Invalid CGM data" || error.message?.endsWith(
                "validation failed (format, checksum or unsupported fields).") == true)
            assertNull(error.cause)
        }
    }

    @Test
    fun unavailableReadingsExplainWhyInsteadOfReportingNoIssue() {
        val unknownZone = CgmFixtures.session().also { it[7] = 0x80.toByte() }
        val cases = listOf(
            CgmFixtures.record().copy(sessionStartBefore = unknownZone, sessionStartAfter = unknownZone) to "timezone",
            CgmFixtures.record().copy(sessionStartBefore = byteArrayOf()) to "session date",
            CgmFixtures.record().copy(sensorStatus = CgmFixtures.status(59)) to "precedes",
            CgmFixtures.record(ageMillis = -60_000).copy(sensorStatus = CgmFixtures.status(60)) to "ahead",
            CgmFixtures.record().copy(sessionStartAfter = CgmFixtures.session(CgmFixtures.start + 1_000)) to "changed",
        )
        for ((record, expected) in cases) {
            val tracker = PumpReadingTracker()
            tracker.accept(record)
            val snapshot = tracker.snapshot(record.receivedAtElapsedMillis)
            hidden(SensorStatus.TIME_UNKNOWN, snapshot)
            assertTrue(snapshot.reason, snapshot.reason?.contains(expected) == true)
        }
        val tracker = PumpReadingTracker()
        tracker.accept(CgmFixtures.record())
        assertNull(tracker.snapshot(CgmFixtures.elapsed).reason)
    }
}
