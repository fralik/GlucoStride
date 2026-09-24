package io.glucostride.pump

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpReadFreshnessTest {
    @Test
    fun expiresAtNinetySecondsWithoutRefreshingTheActualReceipt() {
        assertTrue(PumpReadFreshness.isRecent(1_000, 1_000))
        assertTrue(PumpReadFreshness.isRecent(1_000, 90_999))
        assertFalse(PumpReadFreshness.isRecent(1_000, 91_000))
        assertFalse(PumpReadFreshness.isRecent(1_000, 100_000))
    }

    @Test
    fun invalidAndRegressingElapsedTimesCannotDisplayData() {
        assertFalse(PumpReadFreshness.isRecent(-1, 1_000))
        assertFalse(PumpReadFreshness.isRecent(1_000, 999))
        assertFalse(PumpReadFreshness.isRecent(0, -1))
        assertFalse(PumpReadFreshness.isRecent(Long.MAX_VALUE, 0))
    }
}
