/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from GlycemicGPT/android-unofficial MedtronicSakeSession.kt and
 * SakeHandshakeDriver.kt at 59e68104df17614c50173bed954843a5f56e588b.
 * Copyright GlycemicGPT contributors; choreography from OpenMinimed
 * JavaPumpConnector/JavaSake contributors (GPL-3.0).
 * GlucoStride changes: worker-confined CGM-only wrapper, explicit peer type
 * verification; no raw crypto logging, keys, encryption or control-write API.
 */
package io.glucostride.pump.transport

import org.openminimed.sake.Constants
import org.openminimed.sake.DeviceType
import org.openminimed.sake.KeyDatabase
import org.openminimed.sake.SakeServer
import org.openminimed.sake.Session

internal class PumpSakeSession(keyDatabase: KeyDatabase = Constants.KEYDB_PUMP_EXTRACTED) {
    private val server = SakeServer(keyDatabase, DeviceType.MOBILE_APPLICATION)

    val stage: Int get() = server.stage
    val complete: Boolean get() = stage == 6

    fun wakeUp(): ByteArray = ByteArray(Session.MESSAGE_SIZE)

    fun handshake(frame: ByteArray): ByteArray? {
        val response = server.handshake(frame)
        if (server.stage > 1 && server.session().clientDeviceType() != DeviceType.INSULIN_PUMP) {
            throw TransportProtocolException("Unexpected authentication peer")
        }
        return response
    }

    fun decrypt(frame: ByteArray): ByteArray {
        check(complete)
        // Retains the Android reference's client-originating cipher. Do not retry a MAC
        // failure using another direction or modify its sequence counters.
        return server.session().clientCrypt().decrypt(frame)
    }
}
