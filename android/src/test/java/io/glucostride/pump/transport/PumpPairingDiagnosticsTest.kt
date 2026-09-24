package io.glucostride.pump.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class PumpPairingDiagnosticsTest {
    @Test
    fun noPeerOrHandshakeIsAssumedBeforeObservation() {
        assertEquals("phase=STARTING, bond=NOT_OBSERVED, SAKE=not-started", PumpPairingDiagnostics().summary())
    }

    @Test
    fun disconnectRetainsTheStatusAndLastPairingStep() {
        val diagnostics = PumpPairingDiagnostics().apply {
            phase = PumpPairingDiagnostics.Phase.AUTHENTICATING
            bond = PumpPairingDiagnostics.Bond.BONDED
            sakeStage = 1
        }
        assertEquals(
            "Pump disconnected (server; GATT 19) [phase=AUTHENTICATING, bond=BONDED, SAKE=1]",
            diagnostics.failure("Pump disconnected (server; GATT 19)"),
        )
    }

    @Test
    fun androidBondingIsDistinguishableFromAnAuthenticatedPumpSession() {
        val diagnostics = PumpPairingDiagnostics().apply {
            phase = PumpPairingDiagnostics.Phase.AUTHENTICATING
            bond = PumpPairingDiagnostics.Bond.BONDING
        }
        assertEquals("phase=AUTHENTICATING, bond=BONDING, SAKE=not-started", diagnostics.summary())
    }

    @Test
    fun pacingReportsLocalSubmissionWithoutClaimingNegotiationOrAuthentication() {
        val diagnostics = PumpPairingDiagnostics().apply {
            phase = PumpPairingDiagnostics.Phase.AUTHENTICATING
            bond = PumpPairingDiagnostics.Bond.BONDED
            pacing = PumpDiscoveryPacing.Status.REQUEST_SUBMITTED
        }
        assertEquals(
            "phase=AUTHENTICATING, bond=BONDED, SAKE=not-started, pacing=REQUEST_SUBMITTED",
            diagnostics.summary(),
        )
    }
}
