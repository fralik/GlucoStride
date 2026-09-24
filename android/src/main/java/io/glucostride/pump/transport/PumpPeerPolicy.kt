package io.glucostride.pump.transport

internal object PumpPeerPolicy {
    fun canSelect(accepting: Boolean, hasPeer: Boolean, pinnedAddress: String?, address: String): Boolean =
        accepting && !hasPeer && (pinnedAddress == null || address.equals(pinnedAddress, ignoreCase = true))
}
