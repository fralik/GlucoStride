// SPDX-License-Identifier: GPL-3.0-only
package io.glucostride.pump.data

import io.glucostride.core.SensorStatus
import io.glucostride.core.LiveGlucoseProtocol
import io.glucostride.pump.PumpClockRead
import io.glucostride.pump.RawCgmRecord
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PumpClockReferenceTest {
    private fun clockBytes(localMillis: Long): ByteArray =
        CgmFixtures.session(localMillis).copyOfRange(0, 7) + byteArrayOf(0, 0, 0)

    private fun record(
        age: Long = 90_000,
        offset: Int = 60,
        elapsed: Long = CgmFixtures.elapsed,
        localShift: Long = 7_200_000,
    ): RawCgmRecord {
        val raw = CgmFixtures.record(offset = offset, ageMillis = age, receivedElapsed = elapsed)
        val session = CgmFixtures.session(CgmFixtures.start + localShift).also {
            it[7] = 0x80.toByte()
            it[8] = 0xff.toByte()
        }
        return raw.copy(
            sessionStartBefore = session, sessionStartAfter = session.copyOf(),
            clockRead = PumpClockRead(
                clockBytes(raw.receivedAtEpochMillis + localShift), elapsed - 250, elapsed - 100,
            ),
        )
    }

    @Test fun unknownTimezoneUsesPumpClockAndPreservesActualSourceAge() {
        for (shift in listOf(-43_200_000L, 0L, 7_200_000L, 50_400_000L)) {
            val input = record(localShift = shift)
            val tracker = PumpReadingTracker()
            tracker.accept(input)
            val actual = tracker.snapshot(input.receivedAtElapsedMillis)
            assertEquals(SensorStatus.OK, actual.status)
            assertEquals(90L, actual.ageSeconds)
            assertEquals(123.0, actual.glucoseMgDl)
            assertEquals(CgmFixtures.start + 3_600_000 - 254, actual.measuredAtEpochMillis)
            assertNull(actual.reason)
        }
    }

    @Test fun resolvedPumpReadingReachesLivePayloadAndExpiresWithoutNewSourceSample() {
        val tracker = PumpReadingTracker()
        tracker.accept(record())
        val fresh = LiveGlucoseProtocol.encode(tracker.snapshot(CgmFixtures.elapsed), 1)
        assertEquals(20, fresh.size)
        assertEquals(2, fresh[0].toInt())
        assertEquals(2, fresh[1].toInt())
        assertEquals(SensorStatus.OK.wire, fresh[2].toInt())
        val wire = ByteBuffer.wrap(fresh).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(123, wire.getShort(4).toInt())
        assertEquals(90, wire.getInt(8))
        assertTrue(wire.getInt(12) > 0)
        val expired = ByteBuffer.wrap(LiveGlucoseProtocol.encode(
            tracker.snapshot(CgmFixtures.elapsed + 510_000), 2,
        )).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(SensorStatus.STALE.wire, expired.get(2).toInt())
        assertEquals(0xffff, expired.getShort(4).toInt() and 0xffff)
        assertEquals(wire.getInt(12), expired.getInt(12))
    }

    @Test fun repeatedReadsAndPhoneClockEditsCannotRejuvenateSample() {
        val tracker = PumpReadingTracker()
        tracker.accept(record())
        val first = tracker.snapshot(CgmFixtures.elapsed)
        tracker.clear()
        tracker.accept(record(age = 150_000, elapsed = CgmFixtures.elapsed + 60_000).copy(
            receivedAtEpochMillis = 0,
        ))
        val repeated = tracker.snapshot(CgmFixtures.elapsed + 60_000)
        assertEquals(SensorStatus.OK, repeated.status)
        assertEquals(150L, repeated.ageSeconds)
        assertEquals(first.measuredAtEpochMillis, repeated.measuredAtEpochMillis)
        assertEquals(first.sampleIdentity, repeated.sampleIdentity)
        tracker.accept(record(offset = 61, elapsed = CgmFixtures.elapsed + 60_000))
        val next = tracker.snapshot(CgmFixtures.elapsed + 60_000)
        assertEquals(SensorStatus.OK, next.status)
        assertEquals(90L, next.ageSeconds)
        assertNotEquals(first.sampleIdentity, next.sampleIdentity)
    }

    @Test fun pumpClockJumpLatchesFailureEvenAfterClockReturnsOrSourceIsCleared() {
        val tracker = PumpReadingTracker()
        tracker.accept(record())
        val next = record(age = 150_000, elapsed = CgmFixtures.elapsed + 60_000)
        tracker.accept(next.copy(clockRead = next.clockRead!!.copy(
            value = clockBytes(next.receivedAtEpochMillis + 7_260_000),
        )))
        assertEquals(SensorStatus.TIME_UNKNOWN, tracker.snapshot(CgmFixtures.elapsed + 60_000).status)
        tracker.clear()
        tracker.accept(next)
        val result = tracker.snapshot(CgmFixtures.elapsed + 60_000)
        assertEquals(SensorStatus.TIME_UNKNOWN, result.status)
        assertTrue(result.reason!!.contains("clock changed"))
        assertNull(result.glucoseMgDl)
    }

    @Test fun missingClockInvalidSessionAndSlowReadsDoNotInventTimes() {
        val raw = record()
        val invalidSession = raw.sessionStartBefore.copyOf().also { it[2] = 0 }
        val cases = listOf(
            raw.copy(clockRead = null),
            raw.copy(sessionStartBefore = invalidSession, sessionStartAfter = invalidSession),
            raw.copy(clockRead = raw.clockRead!!.copy(startedAtElapsedMillis = CgmFixtures.elapsed - 6_000)),
            raw.copy(clockRead = raw.clockRead!!.copy(completedAtElapsedMillis = CgmFixtures.elapsed + 1)),
            raw.copy(clockRead = raw.clockRead!!.copy(value = ByteArray(10))),
        )
        for (input in cases) {
            val tracker = PumpReadingTracker()
            tracker.accept(input)
            val result = tracker.snapshot(input.receivedAtElapsedMillis)
            assertEquals(SensorStatus.TIME_UNKNOWN, result.status)
            assertNull(result.glucoseMgDl)
            assertNotNull(result.reason)
        }
    }

    @Test fun staleAndSensorFaultChecksStillApplyWithClockReference() {
        for ((input, status) in listOf(
            record(age = 599_000) to SensorStatus.OK,
            record(age = 600_000) to SensorStatus.STALE,
            record().copy(sensorStatus = CgmFixtures.status(bits = 0x100)) to SensorStatus.TIME_UNKNOWN,
            record().copy(sensorStatus = CgmFixtures.status(bits = 0x80)) to SensorStatus.SENSOR_ERROR,
        )) {
            val tracker = PumpReadingTracker()
            tracker.accept(input)
            val result = tracker.snapshot(input.receivedAtElapsedMillis)
            assertEquals(status, result.status)
            if (status != SensorStatus.OK) assertNull(result.glucoseMgDl)
        }
    }

    @Test fun currentTimeRequiresExactStandardLayoutAndValidCalendar() {
        val clock = clockBytes(CgmFixtures.start)
        assertEquals(CgmFixtures.start, CgmWire.currentTimeLocalMillis(clock))
        assertEquals(CgmFixtures.start + 500, CgmWire.currentTimeLocalMillis(clock.copyOf().also { it[8] = 128.toByte() }))
        for (invalid in listOf(
            clock.copyOf(9), clock.copyOf(11),
            clock.copyOf().also { it[7] = 8 },
            clock.copyOf().also { it[7] = 1 },
            clock.copyOf().also { it[9] = 0x10 },
        )) {
            try {
                CgmWire.currentTimeLocalMillis(invalid)
                fail("Invalid clock accepted")
            } catch (_: PumpDataException) { }
        }
        assertNull(CgmWire.currentTimeLocalMillis(clock.copyOf().also { it[2] = 0 }))
    }
}
