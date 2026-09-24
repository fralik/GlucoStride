// SPDX-License-Identifier: GPL-3.0-only
package io.glucostride.pump.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpOperationPolicyTest {
    @Test fun pumpClockAllowsOnlyItsServiceScopedRead() {
        assertTrue(PumpOperationPolicy.permitsRead(PumpUuids.CURRENT_TIME_SERVICE, PumpUuids.CURRENT_TIME))
        assertFalse(PumpOperationPolicy.permitsRead(PumpUuids.CGM, PumpUuids.CURRENT_TIME))
        assertFalse(PumpOperationPolicy.permitsRead(PumpUuids.CURRENT_TIME_SERVICE, PumpUuids.STATUS))
        assertFalse(PumpOperationPolicy.permitsWrite(
            PumpUuids.CURRENT_TIME_SERVICE, PumpUuids.CURRENT_TIME, ByteArray(10),
        ))
        assertFalse(PumpOperationPolicy.permitsDescriptorWrite(
            PumpUuids.CURRENT_TIME_SERVICE, PumpUuids.CURRENT_TIME, PumpUuids.CCCD, byteArrayOf(1, 0),
        ))
    }

    @Test fun onlyThreeCgmReadsAreAllowed() {
        for (uuid in listOf(PumpUuids.FEATURE, PumpUuids.SESSION_START, PumpUuids.STATUS)) {
            assertTrue(PumpOperationPolicy.permitsRead(PumpUuids.CGM, uuid))
            assertFalse(PumpOperationPolicy.permitsRead(PumpUuids.vendor(0x0100), uuid))
        }
        for (uuid in listOf(PumpUuids.MEASUREMENT, PumpUuids.RACP, PumpUuids.sig(0x2aac), PumpUuids.sig(0x2aab))) {
            assertFalse(PumpOperationPolicy.permitsRead(PumpUuids.CGM, uuid))
        }
    }

    @Test fun onlyExactReportLastRequestIsAllowed() {
        for (opcode in 0..255) for (operator in 0..255) {
            val permitted = PumpOperationPolicy.permitsWrite(
                PumpUuids.CGM, PumpUuids.RACP, byteArrayOf(opcode.toByte(), operator.toByte()),
            )
            if (opcode == 1 && operator == 6) assertTrue(permitted) else assertFalse(permitted)
        }
        assertFalse(PumpOperationPolicy.permitsWrite(PumpUuids.CGM, PumpUuids.RACP, byteArrayOf()))
        assertFalse(PumpOperationPolicy.permitsWrite(PumpUuids.CGM, PumpUuids.RACP, byteArrayOf(1)))
        assertFalse(PumpOperationPolicy.permitsWrite(PumpUuids.CGM, PumpUuids.RACP, byteArrayOf(1, 6, 0)))
    }

    @Test fun sharedControlPointUuidCannotReachAnotherService() {
        for (service in listOf(PumpUuids.vendor(0x0100), PumpUuids.vendor(0x0300), PumpUuids.DEVICE_INFO)) {
            assertFalse(PumpOperationPolicy.permitsWrite(service, PumpUuids.RACP, byteArrayOf(1, 6)))
            assertFalse(
                PumpOperationPolicy.permitsDescriptorWrite(service, PumpUuids.RACP, PumpUuids.CCCD, byteArrayOf(2, 0)),
            )
        }
        assertFalse(PumpOperationPolicy.permitsWrite(PumpUuids.CGM, PumpUuids.sig(0x2aac), byteArrayOf(1, 6)))
    }

    @Test fun onlyCgmDataAndRacpSubscriptionsAreAllowed() {
        for (uuid in listOf(PumpUuids.MEASUREMENT, PumpUuids.RACP)) {
            for (value in 0..255) {
                val allowed = PumpOperationPolicy.permitsDescriptorWrite(
                    PumpUuids.CGM, uuid, PumpUuids.CCCD, byteArrayOf(value.toByte(), 0),
                )
                if (value <= 2) assertTrue(allowed) else assertFalse(allowed)
            }
            assertFalse(
                PumpOperationPolicy.permitsDescriptorWrite(PumpUuids.CGM, uuid, PumpUuids.CCCD, byteArrayOf(1, 1)),
            )
            assertFalse(
                PumpOperationPolicy.permitsDescriptorWrite(PumpUuids.CGM, uuid, PumpUuids.CCCD, byteArrayOf(1)),
            )
            assertFalse(
                PumpOperationPolicy.permitsDescriptorWrite(PumpUuids.CGM, uuid, PumpUuids.sig(0x2901), byteArrayOf(1, 0)),
            )
        }
        assertFalse(
            PumpOperationPolicy.permitsDescriptorWrite(
                PumpUuids.CGM, PumpUuids.sig(0x2aac), PumpUuids.CCCD, byteArrayOf(1, 0),
            ),
        )
    }
}
