package io.glucostride.compat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Test

class GattValueSnapshotTest {
    @Test
    fun android12And12LCopyTheLegacyValueBeforeDispatch() {
        for (sdk in listOf(31, 32)) {
            val mutable = byteArrayOf(1, 2, 3)
            val snapshot = GattValueSnapshot.legacy(sdk) { mutable }
            mutable[0] = 99
            assertArrayEquals(byteArrayOf(1, 2, 3), snapshot)
        }
    }

    @Test
    fun modernAndroidIgnoresLegacyCallbacksWithoutReadingMutableValues() {
        for (sdk in 33..37) {
            assertNull(GattValueSnapshot.legacy(sdk) { error("Legacy callback must not read or deliver twice") })
        }
    }

    @Test
    fun modernValuesAreAlsoCopiedBeforeDispatch() {
        val mutable = byteArrayOf(1, 2)
        val snapshot = GattValueSnapshot.copy(mutable)
        mutable.fill(0)
        assertArrayEquals(byteArrayOf(1, 2), snapshot)
    }

    @Test
    fun missingAndOversizedInputRemainInvalidRatherThanBeingTruncatedToAValidFrame() {
        assertArrayEquals(byteArrayOf(), GattValueSnapshot.copy(null))
        assertArrayEquals(byteArrayOf(), GattValueSnapshot.copy(ByteArray(516)))
        assertArrayEquals(byteArrayOf(), GattValueSnapshot.legacy(31) { null })
        assertArrayEquals(byteArrayOf(), GattValueSnapshot.legacy(32) { ByteArray(516) })
    }

    @Test
    fun existingInboundBoundStillAllowsTheLargestFrame() {
        val frame = ByteArray(515) { it.toByte() }
        val snapshot = GattValueSnapshot.copy(frame)
        assertArrayEquals(frame, snapshot)
        assertNotSame(frame, snapshot)
    }
}
