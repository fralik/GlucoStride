/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from GlycemicGPT/android-unofficial MedtronicSessionReader.kt at
 * 59e68104df17614c50173bed954843a5f56e588b (GlycemicGPT contributors),
 * originally based on OpenMinimed PythonPumpConnector SGReader (GPL-3.0).
 * GlucoStride changes: bounded size-prefixed single-record framing, strict RACP
 * completion/no-record validation; no logging or unrelated control points.
 */
package io.glucostride.pump.transport

internal class TransportProtocolException(val label: String) : Exception(label)

internal fun decryptMeasurementNotification(
    ciphertext: ByteArray,
    collect: Boolean,
    decrypt: (ByteArray) -> ByteArray,
): ByteArray? {
    if (ciphertext.size < 4) throw TransportProtocolException("Invalid encrypted CGM notification")
    // Enabling notifications may emit a live sample before RACP is ready, or while
    // disabling the subscription. Authenticate it to keep the session sequence aligned.
    val plaintext = decrypt(ciphertext)
    return if (collect) plaintext else null
}

/**
 * Receives already-authenticated plaintext fragments. The CGM size byte, not an ATT
 * short-packet heuristic, determines the record boundary. The decoder validates CRC/fields.
 */
internal class ReportLastCollector {
    private val bytes = ByteArray(255)
    private var size = 0
    private var expectedSize = 0
    private var fragments = 0
    private var finished = false

    val complete: Boolean get() = expectedSize != 0 && size == expectedSize

    fun offer(fragment: ByteArray) {
        if (finished || complete) throw TransportProtocolException("Multiple CGM records")
        if (fragment.isEmpty() || ++fragments > MAX_FRAGMENTS) {
            throw TransportProtocolException("Invalid CGM fragments")
        }
        if (size == 0) {
            expectedSize = fragment[0].toInt() and 0xff
            if (expectedSize < 6) throw TransportProtocolException("Invalid CGM record length")
        }
        if (fragment.size > expectedSize - size) {
            throw TransportProtocolException("CGM record exceeds declared length")
        }
        fragment.copyInto(bytes, size)
        size += fragment.size
    }

    /** Null means an explicit RACP No Records Found response, never a missing notification. */
    fun finish(response: ByteArray): ByteArray? {
        if (finished) throw TransportProtocolException("Duplicate RACP response")
        finished = true
        if (response.size != 4 || response[0] != 6.toByte() ||
            response[1] != 0.toByte() || response[2] != 1.toByte()
        ) {
            throw TransportProtocolException("Malformed RACP response")
        }
        return when (response[3].toInt() and 0xff) {
            1 -> {
                if (!complete) throw TransportProtocolException("Missing or truncated CGM record")
                bytes.copyOf(size)
            }
            6 -> {
                if (size != 0) throw TransportProtocolException("Contradictory RACP no-record response")
                null
            }
            else -> throw TransportProtocolException("RACP report-last rejected")
        }
    }

    companion object {
        const val MAX_FRAGMENTS = 32
    }
}
