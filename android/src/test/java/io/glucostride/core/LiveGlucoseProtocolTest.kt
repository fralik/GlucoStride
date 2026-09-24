package io.glucostride.core

import io.glucostride.pump.data.PumpSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LiveGlucoseProtocolTest {
    private val reading = PumpSnapshot(SensorStatus.OK, 126.0, 0.5, 30, 7_000, "session:offset")

    private fun packet(value: PumpSnapshot? = reading, sequence: Long = 9): ByteBuffer =
        ByteBuffer.wrap(LiveGlucoseProtocol.encode(value, sequence)).order(ByteOrder.LITTLE_ENDIAN)

    private fun assertHidden(value: PumpSnapshot?, status: SensorStatus) {
        val bytes = packet(value)
        assertEquals(status.wire, bytes.get(2).toInt())
        assertEquals(65535, bytes.getShort(4).toInt() and 0xffff)
        assertEquals(-32768, bytes.getShort(6).toInt())
    }

    @Test
    fun canonicalLiveFixtureHasSeparateVersionFlagAndService() {
        assertArrayEquals(
            byteArrayOf(2, 2, 0, 0, 126, 0, 50, 0, 30, 0, 0, 0, 7, 0, 0, 0, 9, 0, 0, 0),
            LiveGlucoseProtocol.encode(reading, 9),
        )
        assertNotEquals("7b9e1000-6d8b-4f3a-9c21-2e8a6f0d5b47", LiveGlucoseProtocol.SERVICE_UUID)
        assertNotEquals("7b9e1001-6d8b-4f3a-9c21-2e8a6f0d5b47", LiveGlucoseProtocol.CHARACTERISTIC_UUID)
    }

    @Test
    fun noReadingAndEveryInvalidStatusSuppressNumbers() {
        assertHidden(null, SensorStatus.UNAVAILABLE)
        for (status in SensorStatus.entries.filter { it != SensorStatus.OK }) {
            assertHidden(reading.copy(status = status), status)
        }
        assertEquals(-1, packet(null).getInt(8))
        assertEquals(0, packet(null).getInt(12))
    }

    @Test
    fun staleBoundaryIsExactly600Seconds() {
        assertEquals(0, packet(reading.copy(ageSeconds = 599)).get(2).toInt())
        assertHidden(reading.copy(ageSeconds = 600), SensorStatus.STALE)
        assertHidden(reading.copy(ageSeconds = 601), SensorStatus.STALE)
    }

    @Test
    fun unknownOrUnrepresentableTimeCannotProduceAnOkPacket() {
        for (age in listOf(null, -1L, 0xffff_ffffL, Long.MAX_VALUE)) {
            assertHidden(reading.copy(ageSeconds = age), SensorStatus.TIME_UNKNOWN)
        }
        for (time in listOf(null, -1L, 0L, 999L, 0x1_0000_0000L * 1_000, Long.MAX_VALUE)) {
            assertHidden(reading.copy(measuredAtEpochMillis = time), SensorStatus.TIME_UNKNOWN)
        }
    }

    @Test
    fun fractionalGlucoseAndTrendAreRoundedNotTruncated() {
        val bytes = packet(reading.copy(glucoseMgDl = 126.6, trendMgDlPerMinute = -1.236))
        assertEquals(127, bytes.getShort(4).toInt())
        assertEquals(-124, bytes.getShort(6).toInt())
    }

    @Test
    fun invalidNumbersProduceExplicitErrorAndUnknownTrendStaysUnknown() {
        for (glucose in listOf(null, 0.0, -1.0, 65535.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertHidden(reading.copy(glucoseMgDl = glucose), SensorStatus.SENSOR_ERROR)
        }
        for (trend in listOf(-327.68, 327.68, Double.NaN, Double.NEGATIVE_INFINITY)) {
            assertHidden(reading.copy(trendMgDlPerMinute = trend), SensorStatus.SENSOR_ERROR)
        }
        val unknown = packet(reading.copy(trendMgDlPerMinute = null))
        assertEquals(0, unknown.get(2).toInt())
        assertEquals(-32768, unknown.getShort(6).toInt())
    }

    @Test
    fun identityIsStableAcrossRepeatsAndDistinctForEqualGlucoseAtNewTime() {
        val first = packet()
        val repeat = packet(reading.copy(ageSeconds = 45), sequence = 10)
        assertEquals(first.getInt(12), repeat.getInt(12))
        assertEquals(45, repeat.getInt(8))
        assertNotEquals(first.getInt(16), repeat.getInt(16))
        assertNotEquals(first.getInt(12), packet(reading.copy(measuredAtEpochMillis = 67_000)).getInt(12))
        assertEquals(first.getInt(12), packet().getInt(12))
    }

    @Test
    fun unsignedSampleAgeAndSequenceRemainLittleEndian() {
        val bytes = packet(reading.copy(measuredAtEpochMillis = 0xffff_ffffL * 1_000,
            ageSeconds = 0xffff_fffeL), 0xffff_ffffL)
        assertEquals(-1, bytes.getInt(12))
        assertEquals(-2, bytes.getInt(8))
        assertEquals(-1, bytes.getInt(16))
        assertEquals(0, packet(sequence = 0).getInt(16))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangeSequence() {
        packet(sequence = 0x1_0000_0000L)
    }
}
