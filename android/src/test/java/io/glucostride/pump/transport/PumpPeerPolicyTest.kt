package io.glucostride.pump.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpPeerPolicyTest {
    private val pump = "00:11:22:33:44:AA"
    private val watch = "00:11:22:33:44:BB"

    @Test
    fun initialPairingSelectsOnlyWhileAcceptingAndBeforeAPeerExists() {
        assertTrue(PumpPeerPolicy.canSelect(true, false, null, pump))
        assertFalse(PumpPeerPolicy.canSelect(false, false, null, pump))
        assertFalse(PumpPeerPolicy.canSelect(true, true, null, watch))
    }

    @Test
    fun watchCannotReplacePumpOrBecomePumpDuringReconnect() {
        for (accepting in listOf(false, true)) {
            for (hasPeer in listOf(false, true)) {
                assertFalse(PumpPeerPolicy.canSelect(accepting, hasPeer, pump, watch))
            }
        }
        assertTrue(PumpPeerPolicy.canSelect(true, false, pump, pump.lowercase()))
        assertFalse(PumpPeerPolicy.canSelect(true, true, pump, pump))
    }
}
