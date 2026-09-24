package io.glucostride.pump

import org.junit.Assert.*
import org.junit.Test

class PumpConnectionPolicyTest {
    @Test
    fun bridgeStartsForSavedPairingWithoutWaitingForAuthenticationOrAnotherButton() {
        assertTrue(PumpConnectionPolicy.startWatch(true, false, "paired-pump", false))
        assertFalse(PumpConnectionPolicy.startWatch(true, false, "paired-pump", true))
    }

    @Test
    fun firstPairWaitsForPinnedPeerAndStopCannotRestartWatch() {
        assertFalse(PumpConnectionPolicy.startWatch(true, false, null, false))
        assertFalse(PumpConnectionPolicy.startWatch(false, false, "paired-pump", false))
        assertFalse(PumpConnectionPolicy.startWatch(true, true, "paired-pump", false))
    }

    @Test
    fun pairedPumpKeepsRetryingOrdinaryRangeLossWithCappedDelay() {
        assertEquals(5_000L, PumpConnectionPolicy.reconnectDelayMillis(true, false, 1))
        assertEquals(10_000L, PumpConnectionPolicy.reconnectDelayMillis(true, false, 2))
        for (attempt in listOf(3, 4, 10, 100, Int.MAX_VALUE)) {
            assertEquals(15_000L, PumpConnectionPolicy.reconnectDelayMillis(true, false, attempt))
        }
    }

    @Test
    fun authenticationFailuresAndUnpairedTimeoutsStillStop() {
        assertNull(PumpConnectionPolicy.reconnectDelayMillis(true, true, 1))
        assertNull(PumpConnectionPolicy.reconnectDelayMillis(false, false, 1))
        assertNull(PumpConnectionPolicy.reconnectDelayMillis(false, true, 1))
    }
}
