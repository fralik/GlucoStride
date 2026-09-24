package io.glucostride.pump

import io.glucostride.pump.data.PumpSnapshot

data class PumpUiState(
    val running: Boolean = false,
    val stopping: Boolean = false,
    val phase: PumpConnectionState = PumpConnectionState.STOPPED,
    val message: String = "Pump reader stopped.",
    val issue: String? = null,
    val reading: PumpSnapshot? = null,
    val balancedDiscovery: Boolean = false,
    val lastRecordAtElapsedMillis: Long? = null,
    val recordsReceived: Long = 0,
) {
    fun restoreLastIssue(savedIssue: String?): PumpUiState =
        if (running || stopping || issue != null || savedIssue == null) this
        else copy(issue = savedIssue)
}

// Published only on the main looper. Readings are never persisted.
object PumpState {
    var current = PumpUiState()
        private set

    fun update(change: (PumpUiState) -> PumpUiState) {
        current = change(current)
    }
}
