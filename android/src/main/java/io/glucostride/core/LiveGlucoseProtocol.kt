package io.glucostride.core

import io.glucostride.pump.data.PumpSnapshot
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object LiveGlucoseProtocol {
    const val SERVICE_UUID = "7b9e2000-6d8b-4f3a-9c21-2e8a6f0d5b47"
    const val CHARACTERISTIC_UUID = "7b9e2001-6d8b-4f3a-9c21-2e8a6f0d5b47"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    const val STALE_SECONDS = 600L
    const val HEARTBEAT_MILLIS = 5_000L
    const val PAYLOAD_BYTES = 20

    fun encode(reading: PumpSnapshot?, sequence: Long): ByteArray {
        require(sequence in 0..0xffff_ffffL)
        val age = reading?.ageSeconds?.takeIf { it in 0..0xffff_fffeL }
        val sample = reading?.measuredAtEpochMillis?.takeIf { it >= 1_000 }
            ?.div(1_000)?.takeIf { it in 1..0xffff_ffffL }
        val glucose = reading?.glucoseMgDl?.takeIf { it.isFinite() && it in 1.0..65534.0 }
            ?.roundToInt()
        val rate = reading?.trendMgDlPerMinute
        val trend = rate?.takeIf { it.isFinite() && it * 100 in -32767.0..32767.0 }
            ?.let { (it * 100).roundToInt() }
        val status = when {
            reading == null -> SensorStatus.UNAVAILABLE
            reading.status != SensorStatus.OK -> reading.status
            age == null || sample == null -> SensorStatus.TIME_UNKNOWN
            age >= STALE_SECONDS -> SensorStatus.STALE
            glucose == null || (rate != null && trend == null) -> SensorStatus.SENSOR_ERROR
            else -> SensorStatus.OK
        }
        return ByteBuffer.allocate(PAYLOAD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            .put(2.toByte())
            .put(2.toByte())
            .put(status.wire.toByte())
            .put(0.toByte())
            .putShort((glucose.takeIf { status == SensorStatus.OK } ?: 0xffff).toShort())
            .putShort((trend.takeIf { status == SensorStatus.OK } ?: -32768).toShort())
            .putInt((age ?: 0xffff_ffffL).toInt())
            .putInt((sample ?: 0).toInt())
            .putInt(sequence.toInt())
            .array()
    }
}
