package io.glucostride.pump.transport

internal class PumpPairingDiagnostics {
    enum class Phase { STARTING, REGISTERING_SERVICES, ADVERTISING, AUTHENTICATING, CONNECTING_CGM, DISCOVERING_CGM, READING_CGM }
    enum class Bond { NOT_OBSERVED, NONE, BONDING, BONDED, UNKNOWN }

    var phase = Phase.STARTING
    var bond = Bond.NOT_OBSERVED
    var sakeStage: Int? = null
    var pacing = PumpDiscoveryPacing.Status.OFF

    fun summary(): String = "phase=$phase, bond=$bond, SAKE=${sakeStage ?: "not-started"}" +
        if (pacing == PumpDiscoveryPacing.Status.OFF) "" else ", pacing=$pacing"

    fun failure(label: String): String = "$label [${summary()}]"
}
