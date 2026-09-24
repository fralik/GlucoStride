// SPDX-License-Identifier: GPL-3.0-only
package io.glucostride.pump.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportLastCollectorTest {
    private fun record(size: Int = 14) = ByteArray(size).apply { this[0] = size.toByte() }
    private val success = byteArrayOf(6, 0, 1, 1)
    private val noRecords = byteArrayOf(6, 0, 1, 6)

    @Test fun singleRecordCompletesOnDeclaredSize() {
        val data = record()
        val collector = ReportLastCollector()
        collector.offer(data)
        assertTrue(collector.complete)
        assertArrayEquals(data, collector.finish(success))
    }

    @Test fun fragmentedAndExactTwentyByteRecordNeedNoShortTerminator() {
        val data = record(40)
        val collector = ReportLastCollector()
        collector.offer(data.copyOfRange(0, 20))
        assertFalse(collector.complete)
        collector.offer(data.copyOfRange(20, 40))
        assertTrue(collector.complete)
        assertArrayEquals(data, collector.finish(success))
    }

    @Test fun shortFragmentsAreNotAssumedToTerminateARecord() {
        val data = record()
        val collector = ReportLastCollector()
        for (byte in data) collector.offer(byteArrayOf(byte))
        assertArrayEquals(data, collector.finish(success))
    }

    @Test fun explicitNoRecordsIsDistinctFromMissingData() {
        assertNull(ReportLastCollector().finish(noRecords))
        assertThrows(TransportProtocolException::class.java) { ReportLastCollector().finish(success) }
    }

    @Test fun truncatedRecordFailsOnEitherTerminalResponse() {
        for (response in listOf(success, noRecords)) {
            val collector = ReportLastCollector()
            collector.offer(byteArrayOf(14, 0, 0))
            assertThrows(TransportProtocolException::class.java) { collector.finish(response) }
        }
    }

    @Test fun multipleRecordsAndExtraBytesFail() {
        val collector = ReportLastCollector()
        collector.offer(record())
        assertThrows(TransportProtocolException::class.java) { collector.offer(record()) }
        assertThrows(TransportProtocolException::class.java) { ReportLastCollector().offer(record() + record()) }
        assertThrows(TransportProtocolException::class.java) { ReportLastCollector().offer(record() + byteArrayOf(0)) }
    }

    @Test fun malformedLengthsAndEmptyFragmentsFail() {
        for (size in 0..5) {
            assertThrows(TransportProtocolException::class.java) {
                ReportLastCollector().offer(byteArrayOf(size.toByte()))
            }
        }
        assertThrows(TransportProtocolException::class.java) { ReportLastCollector().offer(byteArrayOf()) }
        assertThrows(TransportProtocolException::class.java) { ReportLastCollector().offer(ByteArray(516)) }
    }

    @Test fun fragmentsAreBoundedAndInputIsCopied() {
        val collector = ReportLastCollector()
        val first = byteArrayOf(255.toByte())
        collector.offer(first)
        first[0] = 0
        repeat(ReportLastCollector.MAX_FRAGMENTS - 1) { collector.offer(byteArrayOf(0)) }
        assertThrows(TransportProtocolException::class.java) { collector.offer(byteArrayOf(0)) }

        val data = record()
        val copied = ReportLastCollector()
        copied.offer(data)
        data.fill(0)
        assertArrayEquals(record(), copied.finish(success))
    }

    @Test fun malformedOrUnsuccessfulRacpIsNeverNoData() {
        for (response in listOf(
            byteArrayOf(), byteArrayOf(6, 0, 1), byteArrayOf(6, 0, 1, 1, 0),
            byteArrayOf(5, 0, 1, 6), byteArrayOf(6, 1, 1, 6), byteArrayOf(6, 0, 2, 6), byteArrayOf(6, 0, 1, 8),
        )) {
            assertThrows(TransportProtocolException::class.java) { ReportLastCollector().finish(response) }
        }
    }

    @Test fun noRecordsCannotContradictReceivedRecord() {
        val collector = ReportLastCollector()
        collector.offer(record())
        assertThrows(TransportProtocolException::class.java) { collector.finish(noRecords) }
    }

    @Test fun terminalResponseIsSingleUse() {
        val collector = ReportLastCollector()
        assertNull(collector.finish(noRecords))
        assertThrows(TransportProtocolException::class.java) { collector.finish(noRecords) }
        assertThrows(TransportProtocolException::class.java) { collector.offer(record()) }
    }
}
