package io.glucostride.bridge

import android.content.Context
import android.os.SystemClock
import io.glucostride.core.LiveGlucoseProtocol
import io.glucostride.core.SensorStatus
import io.glucostride.pump.data.PumpSnapshot

data class LiveBridgeUiState(
    val running: Boolean = false,
    val transport: String = "Stopped",
    val clients: Int = 0,
    val subscribers: Int = 0,
    val issue: String? = null,
    val lastPacketStatus: SensorStatus? = null,
    val notificationsSent: Long = 0,
    val lastNotificationAtElapsedMillis: Long? = null,
)

object LiveBridgeState {
    var current = LiveBridgeUiState()
        private set

    fun update(change: (LiveBridgeUiState) -> LiveBridgeUiState) {
        current = change(current)
    }
}

// Owned by the existing pump service; no second pump client or radio lease.
class LiveWatchBridge(
    private val context: Context,
    private val reading: () -> PumpSnapshot?,
) {
    private var transport: GlucoseGattServer? = null
    private var sequence = 0L

    fun start(pumpAddress: String) {
        if (LiveBridgeState.current.running) return
        LiveBridgeState.update { LiveBridgeUiState(running = true, transport = "Starting live bridge") }
        try {
            val server = GlucoseGattServer(
                context,
                packet = {
                    sequence = (sequence + 1) and 0xffff_ffffL
                    LiveGlucoseProtocol.encode(reading(), sequence).also { bytes ->
                        LiveBridgeState.update { it.copy(lastPacketStatus =
                            SensorStatus.entries.first { status -> status.wire == bytes[2].toInt() }) }
                    }
                },
                onTransport = { phase, clients, subscribers ->
                    LiveBridgeState.update { it.copy(transport = phase, clients = clients, subscribers = subscribers) }
                },
                onIssue = { message -> LiveBridgeState.update { it.copy(issue = message) } },
                onFatal = { message -> stop(message) },
                excludedPeer = pumpAddress,
                onNotification = {
                    LiveBridgeState.update { it.copy(notificationsSent = it.notificationsSent + 1,
                        lastNotificationAtElapsedMillis = SystemClock.elapsedRealtime()) }
                },
            )
            transport = server
            server.start()
        } catch (_: SecurityException) {
            stop("Android denied Bluetooth access for the live watch bridge.")
        } catch (error: IllegalStateException) {
            stop("Live watch bridge could not start: ${error.message}")
        }
    }

    fun publish() {
        transport?.publish()
    }

    fun stop(issue: String? = null) {
        transport?.close()
        transport = null
        LiveBridgeState.update { LiveBridgeUiState(issue = issue ?: it.issue) }
    }
}
