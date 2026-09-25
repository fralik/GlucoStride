package io.glucostride.pump

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.glucostride.MainActivity
import io.glucostride.R
import io.glucostride.bridge.LiveBridgeState
import io.glucostride.bridge.LiveWatchBridge
import io.glucostride.compat.BluetoothCompat
import io.glucostride.core.LiveGlucoseProtocol
import io.glucostride.pump.data.PumpDataException
import io.glucostride.pump.data.PumpReadingTracker
import io.glucostride.pump.data.PumpSnapshot
import io.glucostride.pump.transport.PumpClient

class PumpService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tracker = PumpReadingTracker()
    private val failureStore by lazy { PumpFailureStore(this) }
    private var client: PumpClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var nextWakeRenewal = 0L
    private var lastRecordAt: Long? = null
    private var running = false
    private var stopping = false
    private var receiverRegistered = false
    private val liveWatch by lazy { LiveWatchBridge(this, ::watchReading) }
    private var nextWatchHeartbeat = 0L
    private var pumpAddress: String? = null
    private var nextWatchStart = 0L

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                stopMonitoring("Bluetooth turned off. Turn it on and start the pump reader again.")
            }
        }
    }

    private val listener = object : PumpListener {
        override fun onState(state: PumpConnectionState, message: String) = publish {
            if (state != PumpConnectionState.CONNECTED) {
                tracker.clear()
                lastRecordAt = null
            }
            PumpState.update { it.copy(phase = state, message = message,
                reading = if (state == PumpConnectionState.CONNECTED) it.reading else null,
                lastRecordAtElapsedMillis = lastRecordAt) }
        }

        override fun onPaired(address: String) = publish {
            try {
                PumpPairingStore(this@PumpService).save(address)
                pumpAddress = address
                startLiveWatch()
            } catch (error: PumpPairingException) {
                stopMonitoring(error.message)
            }
        }

        override fun onRecord(record: RawCgmRecord) = publish {
            val now = SystemClock.elapsedRealtime()
            if (!PumpReadFreshness.isRecent(record.receivedAtElapsedMillis, now)) {
                tracker.clear()
                lastRecordAt = null
                PumpState.update { it.copy(reading = null, lastRecordAtElapsedMillis = null,
                    issue = "The completed CGM read arrived too late.") }
                return@publish
            }
            try {
                tracker.accept(record)
                lastRecordAt = record.receivedAtElapsedMillis
                val reading = tracker.snapshot(now)
                PumpState.update { it.copy(reading = reading, issue = reading.reason,
                    lastRecordAtElapsedMillis = lastRecordAt, recordsReceived = it.recordsReceived + 1) }
            } catch (error: PumpDataException) {
                tracker.clear()
                lastRecordAt = null
                PumpState.update { it.copy(reading = null, lastRecordAtElapsedMillis = null,
                    recordsReceived = it.recordsReceived + 1, issue = error.message) }
            }
        }

        override fun onNoData() = publish {
            tracker.clear()
            lastRecordAt = null
            PumpState.update { it.copy(reading = null, lastRecordAtElapsedMillis = null,
                issue = "The pump reported no stored CGM record.") }
        }

        override fun onFailure(message: String, terminal: Boolean) = publish {
            tracker.clear()
            lastRecordAt = null
            if (terminal) stopMonitoring(message)
            else PumpState.update { it.copy(reading = null, lastRecordAtElapsedMillis = null, issue = message) }
        }
    }

    private val pulse = object : Runnable {
        override fun run() {
            if (stopping || !running) return
            if (!hasPermissions()) {
                stopMonitoring("Nearby devices permission was revoked.")
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now >= nextWakeRenewal) {
                wakeLock?.acquire(90_000)
                nextWakeRenewal = now + 30_000
            }
            val last = lastRecordAt
            if (last != null && !PumpReadFreshness.isRecent(last, now)) {
                tracker.clear()
                lastRecordAt = null
                PumpState.update { it.copy(reading = null, lastRecordAtElapsedMillis = null,
                    issue = "No completed CGM read in 90 seconds.") }
            } else if (last != null) {
                val reading = tracker.snapshot(now)
                PumpState.update { it.copy(reading = reading, issue = reading.reason) }
            }
            if (!LiveBridgeState.current.running && now >= nextWatchStart) startLiveWatch()
            if (now >= nextWatchHeartbeat) {
                liveWatch.publish()
                nextWatchHeartbeat = now + LiveGlucoseProtocol.HEARTBEAT_MILLIS
            }
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        if (intent?.action !in listOf(ACTION_PAIR, ACTION_CONNECT)) {
            stopMonitoring("Unsupported pump command. Start monitoring from the app.")
            return START_NOT_STICKY
        }
        if (running || stopping) return START_NOT_STICKY
        if (!hasPermissions()) {
            stopMonitoring("Grant Nearby devices before starting the pump reader.")
            return START_NOT_STICKY
        }
        running = true
        val balancedDiscovery = intent?.getBooleanExtra(EXTRA_BALANCED_DISCOVERY, false) == true
        val diagnosticCleared = failureStore.clear()
        PumpState.update { PumpUiState(running = true, message = "Starting pump connection and watch bridge.",
            issue = if (diagnosticCleared) null else "Could not clear the saved pump diagnostic.",
            balancedDiscovery = balancedDiscovery) }
        try {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            val savedAddress = PumpPairingStore(this).load()
            if (intent?.action == ACTION_PAIR && savedAddress != null) {
                stopMonitoring("A pairing already exists. Use Connect, or explicitly forget local pairing first.")
                return START_NOT_STICKY
            }
            if (intent?.action == ACTION_CONNECT && savedAddress == null) {
                stopMonitoring("No local pump pairing exists. Use Start pump pairing.")
                return START_NOT_STICKY
            }
            BluetoothCompat.registerReceiver(this, bluetoothState, BluetoothAdapter.ACTION_STATE_CHANGED)
            receiverRegistered = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlucoStride:PumpReader")
                .apply { setReferenceCounted(false) }
            client = PumpClient(this, listener)
            requireNotNull(client).start(savedAddress, balancedDiscovery)
            pumpAddress = savedAddress
            if (savedAddress != null) startLiveWatch() else {
                LiveBridgeState.update { it.copy(transport = "Starts automatically when pump pairing completes.") }
            }
            handler.post(pulse)
        } catch (error: PumpPairingException) {
            stopMonitoring(error.message)
        } catch (_: SecurityException) {
            stopMonitoring("Android denied Bluetooth or foreground-service access.")
        } catch (_: IllegalStateException) {
            stopMonitoring("The pump reader could not start. Check Bluetooth and start again from the app.")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun publish(action: () -> Unit) {
        handler.post {
            if (running && !stopping) {
                action()
                liveWatch.publish()
            }
        }
    }

    private fun watchReading(): PumpSnapshot? {
        val now = SystemClock.elapsedRealtime()
        val last = lastRecordAt ?: return null
        if (stopping || !running || PumpState.current.phase != PumpConnectionState.CONNECTED ||
            !PumpReadFreshness.isRecent(last, now)) return null
        return tracker.snapshot(now)
    }

    private fun startLiveWatch() {
        if (!PumpConnectionPolicy.startWatch(running, stopping, pumpAddress, LiveBridgeState.current.running) ||
            !hasPermissions()) return
        val address = pumpAddress ?: return
        liveWatch.start(address)
        nextWatchStart = SystemClock.elapsedRealtime() + 30_000
        nextWatchHeartbeat = 0L
    }

    private fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun stopMonitoring(issue: String? = null) {
        if (stopping) return
        stopping = true
        handler.removeCallbacks(pulse)
        liveWatch.stop()
        pumpAddress = null
        tracker.clear()
        lastRecordAt = null
        val retainedIssue = if (issue != null && !failureStore.save(issue)) {
            "$issue (Could not retain this issue after the app closes.)"
        } else issue
        PumpState.update { it.copy(stopping = running, reading = null, lastRecordAtElapsedMillis = null,
            message = if (running) "Closing pump connection." else "Pump reader stopped.",
            issue = retainedIssue ?: it.issue) }
        val transport = client
        client = null
        val complete = {
            handler.post {
                if (receiverRegistered) {
                    unregisterReceiver(bluetoothState)
                    receiverRegistered = false
                }
                wakeLock?.let { if (it.isHeld) it.release() }
                wakeLock = null
                running = false
                PumpState.update { it.copy(running = false, stopping = false,
                    phase = PumpConnectionState.STOPPED, message = "Pump reader stopped.", reading = null) }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            Unit
        }
        if (transport == null) complete() else transport.stop(complete)
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.pump_notification_channel), NotificationManager.IMPORTANCE_LOW,
        ))
        val open = PendingIntent.getActivity(this, 20, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 21, Intent(this, PumpService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_glucostride_notification)
            .setContentTitle(getString(R.string.pump_notification_title))
            .setContentText(getString(R.string.pump_notification_text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.pump_stop), stop).build())
            .build()
    }

    companion object {
        const val EXTRA_BALANCED_DISCOVERY = "io.glucostride.BALANCED_DISCOVERY"
        const val ACTION_PAIR = "io.glucostride.PAIR_PUMP"
        const val ACTION_CONNECT = "io.glucostride.CONNECT_PUMP"
        const val ACTION_STOP = "io.glucostride.STOP_PUMP"
        private const val CHANNEL = "pump_reader"
        private const val NOTIFICATION_ID = 2
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}
