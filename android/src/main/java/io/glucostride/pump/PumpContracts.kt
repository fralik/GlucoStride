package io.glucostride.pump

enum class PumpConnectionState {
    STOPPED, ADVERTISING, AUTHENTICATING, CONNECTED, RECONNECTING
}

data class PumpClockRead(
    val value: ByteArray,
    val startedAtElapsedMillis: Long,
    val completedAtElapsedMillis: Long,
)

// Decrypted CGM data and an optional read-only pump clock reference.
data class RawCgmRecord(
    val feature: ByteArray,
    val sessionStartBefore: ByteArray,
    val sessionStartAfter: ByteArray,
    val sensorStatus: ByteArray,
    val measurement: ByteArray,
    val receivedAtEpochMillis: Long,
    val receivedAtElapsedMillis: Long,
    val clockRead: PumpClockRead? = null,
)

interface PumpListener {
    fun onState(state: PumpConnectionState, message: String)
    fun onPaired(address: String)
    fun onRecord(record: RawCgmRecord)
    fun onNoData()
    fun onFailure(message: String, terminal: Boolean)
}
