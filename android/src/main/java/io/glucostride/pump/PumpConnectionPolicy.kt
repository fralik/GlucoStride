package io.glucostride.pump

internal object PumpConnectionPolicy {
    fun startWatch(running: Boolean, stopping: Boolean, pumpAddress: String?, watchRunning: Boolean): Boolean =
        running && !stopping && pumpAddress != null && !watchRunning

    fun reconnectDelayMillis(paired: Boolean, terminal: Boolean, consecutiveFailures: Int): Long? {
        require(consecutiveFailures > 0)
        if (!paired || terminal) return null
        return consecutiveFailures.coerceAtMost(3) * 5_000L
    }
}
