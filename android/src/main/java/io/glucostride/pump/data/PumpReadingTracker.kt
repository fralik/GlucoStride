/*
 * SPDX-License-Identifier: GPL-3.0-only
 * GlucoStride source-time/status tracking. Byte parser provenance is in CgmWire.kt.
 */
package io.glucostride.pump.data

import io.glucostride.core.SensorStatus
import io.glucostride.pump.RawCgmRecord

data class PumpSnapshot(
    val status: SensorStatus,
    val glucoseMgDl: Double?,
    val trendMgDlPerMinute: Double?,
    val ageSeconds: Long?,
    val measuredAtEpochMillis: Long?,
    val sampleIdentity: String?,
    val reason: String? = null,
)

/**
 * In-memory CGM tracker. No Android clock, phone time zone, persistence, or demo encoder.
 *
 * The first trustworthy receipt anchors UTC to elapsedRealtime. All later source times
 * are compared to that monotonic projection, so changing the phone clock cannot renew
 * an old sample (or make the next one artificially younger). clear() only hides the
 * display; create a new tracker for a new user-started service lifetime.
 */
class PumpReadingTracker {
    private var anchor: ClockAnchor? = null
    private var pumpClockOffsetMillis: Long? = null
    private var pumpClockFailed = false
    private var lastReceiptElapsed: Long? = null
    private var lastSnapshotElapsed: Long? = null
    private var monotonicFailed = false
    private var latest: Sample? = null
    private var visible: Sample? = null
    private var visibleStatus = SensorStatus.UNAVAILABLE
    private var reason: String? = "Waiting for the first completed pump reading."

    @Synchronized
    fun accept(record: RawCgmRecord) {
        visible = null
        visibleStatus = SensorStatus.SENSOR_ERROR
        reason = "The pump's CGM record could not be decoded."
        try {
            acceptValidated(record)
        } catch (error: PumpDataException) {
            visibleStatus = SensorStatus.SENSOR_ERROR
            reason = error.message
            throw error
        } catch (_: ArithmeticException) {
            visibleStatus = SensorStatus.TIME_UNKNOWN
            reason = "The pump's source time is outside the supported range."
        }
    }

    private fun acceptValidated(record: RawCgmRecord) {
        val feature = decode("CGM feature") { CgmWire.feature(record.feature) }
        val measurement = decode("CGM measurement") { CgmWire.measurement(record.measurement, feature) }
        val current = decode("CGM status") { CgmWire.currentStatus(record.sensorStatus, feature.useCrc) }
        var sessionBefore = decode("CGM session start") {
            CgmWire.sessionEpochMillis(record.sessionStartBefore, feature.useCrc)
        }
        var sessionAfter = decode("CGM session recheck") {
            CgmWire.sessionEpochMillis(record.sessionStartAfter, feature.useCrc)
        }

        visibleStatus = SensorStatus.TIME_UNKNOWN
        val receivedElapsed = record.receivedAtElapsedMillis
        if (receivedElapsed < 0 || (lastReceiptElapsed?.let { receivedElapsed < it } == true)) {
            monotonicFailed = true
        }
        if (monotonicFailed) {
            reason = "The phone's elapsed clock moved backwards. Restart monitoring."
            return
        }
        lastReceiptElapsed = receivedElapsed
        val clock = anchor ?: run {
            if (record.receivedAtEpochMillis < 0) {
                reason = "The phone clock cannot be used to calculate source age."
                return
            }
            ClockAnchor(record.receivedAtEpochMillis, receivedElapsed)
        }
        if (sessionBefore == null && sessionAfter == null && record.clockRead != null) {
            val localBefore = CgmWire.sessionLocalMillis(record.sessionStartBefore, feature.useCrc)
            val localAfter = CgmWire.sessionLocalMillis(record.sessionStartAfter, feature.useCrc)
            if (localBefore != null && localAfter != null) {
                val offset = resolvePumpClockOffset(record, clock) ?: return
                sessionBefore = Math.addExact(localBefore, offset)
                sessionAfter = Math.addExact(localAfter, offset)
            }
        } else if (pumpClockOffsetMillis != null) {
            reason = "The pump's timezone reference changed. Restart monitoring."
            return
        }
        if (sessionBefore == null || sessionAfter == null) {
            reason = if (record.sessionStartBefore.size >= 9 && record.sessionStartAfter.size >= 9 &&
                (record.sessionStartBefore[7] == 0x80.toByte() || record.sessionStartAfter[7] == 0x80.toByte() ||
                    record.sessionStartBefore[8] == 0xff.toByte() || record.sessionStartAfter[8] == 0xff.toByte())) {
                "The pump reports an unknown session timezone or daylight-saving offset."
            } else "The pump reports a missing or invalid CGM session date."
            return
        }
        if (sessionBefore != sessionAfter || !record.sessionStartBefore.contentEquals(record.sessionStartAfter)) {
            reason = "The CGM session changed while reading. Waiting for a consistent record."
            return
        }
        val measurementStatus = CgmWire.status(measurement.annunciation, feature)
        val currentStatus = CgmWire.status(current.annunciation, feature)
        if (measurementStatus == SensorStatus.TIME_UNKNOWN || currentStatus == SensorStatus.TIME_UNKNOWN) {
            reason = "The pump marks the CGM time as out of sync."
            return
        }
        if (current.offsetMinutes < measurement.offsetMinutes) {
            reason = "The CGM status timestamp precedes the measurement timestamp."
            return
        }

        val nowEpoch = clock.epochAt(receivedElapsed)
        val measuredAt = Math.addExact(sessionBefore, measurement.offsetMinutes * MINUTE_MILLIS)
        val statusAt = Math.addExact(sessionBefore, current.offsetMinutes * MINUTE_MILLIS)
        if (sessionBefore < 0 || measuredAt > nowEpoch || statusAt > nowEpoch) {
            reason = "The pump's CGM timestamp is ahead of the phone clock."
            return
        }

        val previous = latest
        if (previous != null) {
            if (sessionBefore == previous.sessionEpochMillis) {
                if (feature.type != previous.sampleType || feature.location != previous.sampleLocation) {
                    throw PumpDataException()
                }
                if (measurement.offsetMinutes < previous.measurement.offsetMinutes ||
                    current.offsetMinutes < previous.statusOffsetMinutes
                ) {
                    reason = "The pump returned a record older than the last observed record."
                    return
                }
                if (measurement.offsetMinutes == previous.measurement.offsetMinutes &&
                    measurement != previous.measurement
                ) throw PumpDataException()
            } else {
                // A new session must start after the last observed sample. Rewriting the
                // old reference or wrapping uint16 cannot masquerade as a fresh session.
                if (sessionBefore <= previous.measuredAtEpochMillis) {
                    reason = "The new CGM session would move the source time backwards."
                    return
                }
            }
            if (measuredAt < previous.measuredAtEpochMillis) {
                reason = "The pump returned an older measurement timestamp."
                return
            }
        }

        val ageAtReceipt = Math.subtractExact(nowEpoch, measuredAt)
        val sample = Sample(
            sessionBefore, feature.type, feature.location, measurement, current.offsetMinutes,
            measuredAt, receivedElapsed, ageAtReceipt,
        )
        anchor = clock
        latest = sample
        visible = sample
        visibleStatus = when {
            measurementStatus == SensorStatus.SENSOR_ERROR || currentStatus == SensorStatus.SENSOR_ERROR ->
                SensorStatus.SENSOR_ERROR
            measurementStatus != SensorStatus.OK -> measurementStatus
            currentStatus != SensorStatus.OK -> currentStatus
            !feature.isPatientSample -> SensorStatus.UNAVAILABLE
            measurement.invalidNumber -> SensorStatus.SENSOR_ERROR
            else -> SensorStatus.OK
        }
        reason = when (visibleStatus) {
            SensorStatus.OK -> null
            SensorStatus.SENSOR_ERROR -> "The pump reports an invalid sensor reading or sensor fault."
            else -> "The pump reports that the sensor reading is not currently available."
        }
    }

    private fun resolvePumpClockOffset(record: RawCgmRecord, clock: ClockAnchor): Long? {
        if (pumpClockFailed) {
            reason = "The pump's clock changed during monitoring. Restart monitoring."
            return null
        }
        val read = requireNotNull(record.clockRead)
        val local = decode("Pump current time") { CgmWire.currentTimeLocalMillis(read.value) }
        if (local == null) {
            reason = "The pump reports an invalid current date/time."
            return null
        }
        val start = read.startedAtElapsedMillis
        val end = read.completedAtElapsedMillis
        if (start < 0 || end < start || end - start > 5_000 || end > record.receivedAtElapsedMillis) {
            reason = "The pump clock read was too slow or its timing is invalid."
            return null
        }
        // Bound the source age conservatively by the read request and 1/256-second
        // clock resolution. Keep this mapping fixed so rereads cannot renew a sample.
        val earliest = Math.subtractExact(clock.epochAt(start), Math.addExact(local, 4L))
        val latest = Math.subtractExact(clock.epochAt(end), local)
        val previous = pumpClockOffsetMillis
        if (previous != null && (previous < earliest - 2_000 || previous > latest + 2_000)) {
            pumpClockFailed = true
            reason = "The pump's clock changed during monitoring. Restart monitoring."
            return null
        }
        return previous ?: earliest.also {
            pumpClockOffsetMillis = it
            anchor = clock
        }
    }

    /** Hide visible data without losing the bounded last-sample and monotonic clock history. */
    @Synchronized
    fun clear() {
        visible = null
        visibleStatus = SensorStatus.UNAVAILABLE
        reason = "Waiting for a completed pump reading."
    }

    @Synchronized
    fun snapshot(nowElapsedMillis: Long): PumpSnapshot {
        if (nowElapsedMillis < 0 ||
            lastSnapshotElapsed?.let { nowElapsedMillis < it } == true ||
            lastReceiptElapsed?.let { nowElapsedMillis < it } == true
        ) monotonicFailed = true
        lastSnapshotElapsed = nowElapsedMillis
        if (monotonicFailed) return empty(SensorStatus.TIME_UNKNOWN,
            "The phone's elapsed clock moved backwards. Restart monitoring.")
        val sample = visible ?: return empty(visibleStatus)
        val ageMillis = try {
            Math.addExact(sample.ageAtReceiptMillis, Math.subtractExact(nowElapsedMillis, sample.receivedElapsedMillis))
        } catch (_: ArithmeticException) {
            return empty(SensorStatus.TIME_UNKNOWN)
        }
        if (ageMillis < 0) return empty(SensorStatus.TIME_UNKNOWN)
        val ageSeconds = ageMillis / 1000
        val status = if (visibleStatus == SensorStatus.OK && ageSeconds >= STALE_SECONDS) {
            SensorStatus.STALE
        } else visibleStatus
        return PumpSnapshot(
            status,
            sample.measurement.glucoseMgDl.takeIf { status == SensorStatus.OK },
            sample.measurement.trendMgDlPerMinute.takeIf { status == SensorStatus.OK },
            ageSeconds, sample.measuredAtEpochMillis,
            "${sample.sessionEpochMillis}:${sample.measurement.offsetMinutes}",
            if (status == SensorStatus.STALE) "The source measurement is at least 10 minutes old." else reason,
        )
    }

    private fun empty(status: SensorStatus, detail: String? = reason) =
        PumpSnapshot(status, null, null, null, null, null, detail)

    private fun <T> decode(stage: String, block: () -> T): T = try {
        block()
    } catch (_: PumpDataException) {
        throw PumpDataException("$stage validation failed (format, checksum or unsupported fields).")
    }

    private data class ClockAnchor(val epochMillis: Long, val elapsedMillis: Long) {
        fun epochAt(elapsed: Long): Long = Math.addExact(epochMillis, Math.subtractExact(elapsed, elapsedMillis))
    }

    private data class Sample(
        val sessionEpochMillis: Long,
        val sampleType: Int,
        val sampleLocation: Int,
        val measurement: CgmMeasurement,
        val statusOffsetMinutes: Int,
        val measuredAtEpochMillis: Long,
        val receivedElapsedMillis: Long,
        val ageAtReceiptMillis: Long,
    )

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        // Prototype display gate, not a clinical threshold.
        const val STALE_SECONDS = 600L
    }
}
