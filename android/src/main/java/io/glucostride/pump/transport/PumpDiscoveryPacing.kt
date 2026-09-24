package io.glucostride.pump.transport

internal class PumpDiscoveryPacing(val enabled: Boolean = false) {
    enum class Status { OFF, WAITING_LINK, WAITING_BOND, SETTLING, REQUESTING, REQUEST_SUBMITTED, REQUEST_REJECTED, SKIPPED }

    var status = if (enabled) Status.WAITING_LINK else Status.OFF
        private set
    private var connected = false
    private var bonded = false

    fun observeLink(connected: Boolean) {
        this.connected = connected
        updateWaiting()
    }

    fun observeBond(bonded: Boolean) {
        this.bonded = bonded
        updateWaiting()
    }

    private fun updateWaiting() {
        if (status !in setOf(Status.WAITING_LINK, Status.WAITING_BOND, Status.SETTLING)) return
        status = when {
            !connected -> Status.WAITING_LINK
            !bonded -> Status.WAITING_BOND
            else -> Status.SETTLING
        }
    }

    fun beginRequest(): Boolean {
        if (status != Status.SETTLING) return false
        check(connected && bonded)
        status = Status.REQUESTING
        return true
    }

    fun completeRequest(submitted: Boolean) {
        check(status == Status.REQUESTING)
        // Submission is only the local API result, never proof of a negotiated interval.
        status = if (submitted) Status.REQUEST_SUBMITTED else Status.REQUEST_REJECTED
    }

    fun finish() {
        if (status in setOf(Status.WAITING_LINK, Status.WAITING_BOND, Status.SETTLING)) {
            status = Status.SKIPPED
        }
    }
}
