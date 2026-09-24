package io.glucostride.pump.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpDiscoveryPacingTest {
    @Test
    fun disabledByDefaultAndCannotSendARequest() {
        val pacing = PumpDiscoveryPacing()
        pacing.observeLink(true)
        pacing.observeBond(true)
        assertFalse(pacing.beginRequest())
        assertEquals(PumpDiscoveryPacing.Status.OFF, pacing.status)
    }

    @Test
    fun requiresBothBondingAndTheParameterOnlyClientInEitherOrder() {
        for (bondFirst in listOf(true, false)) {
            val pacing = PumpDiscoveryPacing(true)
            if (bondFirst) pacing.observeBond(true) else pacing.observeLink(true)
            assertFalse(pacing.beginRequest())
            if (bondFirst) pacing.observeLink(true) else pacing.observeBond(true)
            assertEquals(PumpDiscoveryPacing.Status.SETTLING, pacing.status)
            assertTrue(pacing.beginRequest())
        }
    }

    @Test
    fun cachedBondFollowedByRebondingCancelsReadiness() {
        val pacing = PumpDiscoveryPacing(true)
        pacing.observeBond(true)
        pacing.observeLink(true)
        pacing.observeBond(false)
        assertEquals(PumpDiscoveryPacing.Status.WAITING_BOND, pacing.status)
        assertFalse(pacing.beginRequest())
        pacing.observeBond(true)
        assertTrue(pacing.beginRequest())
    }

    @Test
    fun linkLossBeforeTheTimerFiresPreventsTheRequest() {
        val pacing = PumpDiscoveryPacing(true)
        pacing.observeLink(true)
        pacing.observeBond(true)
        pacing.observeLink(false)
        assertFalse(pacing.beginRequest())
        assertEquals(PumpDiscoveryPacing.Status.WAITING_LINK, pacing.status)
    }

    @Test
    fun duplicateEventsCannotSubmitTwiceOrRetryARejection() {
        for (submitted in listOf(true, false)) {
            val pacing = PumpDiscoveryPacing(true)
            pacing.observeLink(true)
            pacing.observeBond(true)
            assertTrue(pacing.beginRequest())
            assertFalse(pacing.beginRequest())
            pacing.completeRequest(submitted)
            repeat(3) {
                pacing.observeBond(false)
                pacing.observeBond(true)
                pacing.observeLink(true)
                assertFalse(pacing.beginRequest())
            }
            assertEquals(
                if (submitted) PumpDiscoveryPacing.Status.REQUEST_SUBMITTED else PumpDiscoveryPacing.Status.REQUEST_REJECTED,
                pacing.status,
            )
        }
    }

    @Test
    fun authenticationOrStopBeforeSubmissionPreventsALateRequest() {
        val pacing = PumpDiscoveryPacing(true)
        pacing.observeLink(true)
        pacing.observeBond(true)
        pacing.finish()
        pacing.observeBond(true)
        pacing.observeLink(true)
        assertFalse(pacing.beginRequest())
        assertEquals(PumpDiscoveryPacing.Status.SKIPPED, pacing.status)
    }

    @Test
    fun finishRetainsTheActualSubmissionResultForDiagnostics() {
        val pacing = PumpDiscoveryPacing(true)
        pacing.observeLink(true)
        pacing.observeBond(true)
        pacing.beginRequest()
        pacing.completeRequest(true)
        pacing.finish()
        assertEquals(PumpDiscoveryPacing.Status.REQUEST_SUBMITTED, pacing.status)
        assertFalse(pacing.beginRequest())
    }

    @Test
    fun newAttemptStartsFreshWithoutReusingOldReadiness() {
        val previous = PumpDiscoveryPacing(true)
        previous.observeLink(true)
        previous.observeBond(true)
        previous.beginRequest()
        previous.completeRequest(true)
        previous.finish()
        val next = PumpDiscoveryPacing(true)
        assertEquals(PumpDiscoveryPacing.Status.WAITING_LINK, next.status)
        assertFalse(next.beginRequest())
    }

    @Test
    fun cannotRecordSuccessWithoutActuallyStartingARequest() {
        assertThrows(IllegalStateException::class.java) {
            PumpDiscoveryPacing(true).completeRequest(true)
        }
    }
}
