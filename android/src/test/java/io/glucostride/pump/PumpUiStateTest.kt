package io.glucostride.pump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PumpUiStateTest {
    @Test
    fun restoresFailureWithoutRestoringAMonitoringSessionOrReading() {
        val restored = PumpUiState().restoreLastIssue("Pump disconnected before authentication.")
        assertEquals("Pump disconnected before authentication.", restored.issue)
        assertFalse(restored.running)
        assertFalse(restored.stopping)
        assertEquals(PumpConnectionState.STOPPED, restored.phase)
        assertNull(restored.reading)
    }

    @Test
    fun oldFailureCannotReplaceALiveSessionOrAnOngoingStop() {
        val running = PumpUiState(running = true)
        val stopping = PumpUiState(stopping = true)
        assertSame(running, running.restoreLastIssue("Previous failure"))
        assertSame(stopping, stopping.restoreLastIssue("Previous failure"))
    }

    @Test
    fun currentFailureTakesPrecedenceOverSavedFailure() {
        val state = PumpUiState(issue = "Current failure")
        assertSame(state, state.restoreLastIssue("Previous failure"))
    }

    @Test
    fun absentSavedFailureDoesNotInventAnError() {
        val state = PumpUiState()
        assertSame(state, state.restoreLastIssue(null))
    }
}
