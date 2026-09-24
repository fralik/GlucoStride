package io.glucostride

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import io.glucostride.bridge.LiveBridgeState
import io.glucostride.core.GlucoseUnit
import io.glucostride.core.SensorStatus
import io.glucostride.pump.PumpFailureStore
import io.glucostride.pump.PumpPairingException
import io.glucostride.pump.PumpPairingStore
import io.glucostride.pump.PumpService
import io.glucostride.pump.PumpState
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pumpValueText: TextView
    private lateinit var pumpStatusText: TextView
    private lateinit var pumpDetailText: TextView
    private lateinit var pumpReadText: TextView
    private lateinit var pumpTransportText: TextView
    private lateinit var feedbackText: TextView
    private lateinit var pairPumpButton: Button
    private lateinit var connectPumpButton: Button
    private lateinit var stopPumpButton: Button
    private lateinit var forgetPumpButton: Button
    private lateinit var balancedDiscoveryBox: CheckBox
    private lateinit var liveWatchText: TextView
    private lateinit var liveWatchDiagnosticsText: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var notificationStatusText: TextView
    private lateinit var pairingStore: PumpPairingStore
    private lateinit var failureStore: PumpFailureStore
    private var pendingAction = PumpService.ACTION_CONNECT
    private var unit = GlucoseUnit.MMOL_L
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pairingStore = PumpPairingStore(this)
        failureStore = PumpFailureStore(this)
        PumpState.update { it.restoreLastIssue(failureStore.load()) }
        pendingAction = savedInstanceState?.getString("pendingAction")
            ?.takeIf { it == PumpService.ACTION_PAIR || it == PumpService.ACTION_CONNECT }
            ?: PumpService.ACTION_CONNECT
        unit = GlucoseUnit.entries.find { it.name == getPreferences(MODE_PRIVATE).getString("unit", null) }
            ?: GlucoseUnit.MMOL_L

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(28))
        }
        val scroll = ScrollView(this).apply { addView(content) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        content.addView(label(getString(R.string.app_name), 24, true))
        addReadingPanel(content)
        addPumpControls(content)
        addWatchPanel(content)
        addBatteryPanel(content)
        addTroubleshooting(content)
        content.addView(label(getString(R.string.safety), 13))
        content.addView(button(R.string.open_source_notices) { showLicenses() })
        render()
    }

    override fun onStart() {
        super.onStart()
        handler.post(refresh)
    }

    override fun onResume() {
        super.onResume()
        renderDeviceGuidance()
    }

    override fun onStop() {
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingAction", pendingAction)
        super.onSaveInstanceState(outState)
    }

    private fun addReadingPanel(content: LinearLayout) {
        pumpValueText = label(getString(R.string.no_value), 52, true).apply { id = R.id.pump_reading }
        pumpStatusText = label("", 16, true).apply {
            id = R.id.pump_reading_status
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        pumpDetailText = label("", 15).apply { id = R.id.pump_reading_detail }
        pumpReadText = label("", 13).apply { id = R.id.pump_read_diagnostics }
        content.addView(pumpValueText)
        content.addView(pumpStatusText)
        content.addView(pumpDetailText)
        content.addView(pumpReadText)
        content.addView(label(getString(R.string.units_title), 13).apply { labelFor = R.id.glucose_units })
        content.addView(Spinner(this).apply {
            id = R.id.glucose_units
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item,
                GlucoseUnit.entries.map { it.label }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            setSelection(unit.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    unit = GlucoseUnit.entries[position]
                    getPreferences(MODE_PRIVATE).edit().putString("unit", unit.name).apply()
                    render()
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        })
    }

    private fun addPumpControls(content: LinearLayout) {
        content.addView(label(getString(R.string.pump_title), 20, true))
        pumpTransportText = label("", 14).apply { id = R.id.pump_connection_status }
        content.addView(pumpTransportText)
        pairPumpButton = button(R.string.pump_pair) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pump_pair)
                .setMessage(getString(R.string.pump_pair_confirmation) +
                    if (balancedDiscoveryBox.isChecked) "\n\n" + getString(R.string.pump_balanced_discovery_detail) else "")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pump_pair) { _, _ -> requestStart(PumpService.ACTION_PAIR) }
                .show()
        }.apply { id = R.id.pump_pair }
        connectPumpButton = button(R.string.pump_connect) { requestStart(PumpService.ACTION_CONNECT) }
            .apply { id = R.id.pump_connect }
        stopPumpButton = button(R.string.pump_stop) {
            try {
                startService(Intent(this, PumpService::class.java).setAction(PumpService.ACTION_STOP))
            } catch (_: SecurityException) {
                feedbackText.setText(R.string.reader_stop_failed)
            } catch (_: IllegalStateException) {
                feedbackText.setText(R.string.reader_stop_failed)
            }
        }.apply { id = R.id.pump_stop }
        content.addView(pairPumpButton)
        content.addView(connectPumpButton)
        content.addView(stopPumpButton)
        feedbackText = label("", 14).apply {
            id = R.id.action_feedback
            setTextColor(WARNING_COLOR)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(feedbackText)
    }

    private fun addWatchPanel(content: LinearLayout) {
        content.addView(label(getString(R.string.live_bridge_title), 20, true))
        liveWatchText = label("", 14).apply { id = R.id.live_watch_status }
        content.addView(liveWatchText)
        liveWatchDiagnosticsText = label("", 13).apply { id = R.id.live_watch_diagnostics }
        content.addView(liveWatchDiagnosticsText)
        content.addView(label(getString(R.string.live_bridge_info), 13))
    }

    private fun addBatteryPanel(content: LinearLayout) {
        content.addView(label(getString(R.string.battery_title), 20, true))
        batteryStatusText = label("", 14).apply { id = R.id.battery_status }
        notificationStatusText = label("", 14).apply {
            id = R.id.notification_status
            setTextColor(WARNING_COLOR)
        }
        content.addView(batteryStatusText)
        content.addView(notificationStatusText)
        content.addView(button(R.string.app_settings) { openAppSettings() }.apply { id = R.id.app_settings })
        val help = label(getString(R.string.battery_help_detail), 14).apply {
            id = R.id.battery_help_detail
            visibility = View.GONE
        }
        content.addView(button(R.string.battery_help) {}.apply {
            id = R.id.battery_help
            setOnClickListener {
                val expanding = help.visibility != View.VISIBLE
                help.visibility = if (expanding) View.VISIBLE else View.GONE
                setText(if (expanding) R.string.battery_help_hide else R.string.battery_help)
            }
        })
        content.addView(help)
    }

    private fun addTroubleshooting(content: LinearLayout) {
        val details = LinearLayout(this).apply {
            id = R.id.troubleshooting_detail
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(button(R.string.troubleshooting) {}.apply {
            id = R.id.troubleshooting
            setOnClickListener {
                val expanding = details.visibility != View.VISIBLE
                details.visibility = if (expanding) View.VISIBLE else View.GONE
                setText(if (expanding) R.string.troubleshooting_hide else R.string.troubleshooting)
            }
        })
        content.addView(details)
        details.addView(label(getString(R.string.reading_diagnostics_help), 14))
        val preferences = getPreferences(MODE_PRIVATE)
        balancedDiscoveryBox = CheckBox(this).apply {
            id = R.id.pump_balanced_discovery
            text = getString(R.string.pump_balanced_discovery)
            isChecked = if (PumpState.current.running) PumpState.current.balancedDiscovery else
                preferences.getBoolean("balancedDiscovery", false)
            setOnCheckedChangeListener { view, checked ->
                if (view.isEnabled) preferences.edit().putBoolean("balancedDiscovery", checked).apply()
            }
        }
        details.addView(balancedDiscoveryBox)
        details.addView(label(getString(R.string.pump_balanced_discovery_detail), 14))
        forgetPumpButton = button(R.string.pump_forget) {
            AlertDialog.Builder(this)
                .setTitle(R.string.pump_forget)
                .setMessage(R.string.pump_forget_confirmation)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.pump_forget) { _, _ ->
                    if (PumpState.current.running || PumpState.current.stopping) {
                        feedbackText.setText(R.string.pump_stop_before_forget)
                    } else {
                        try {
                            pairingStore.clear()
                            val cleared = failureStore.clear()
                            PumpState.update { it.copy(issue = if (cleared) null else
                                getString(R.string.pump_diagnostic_clear_failed)) }
                            feedbackText.text = PumpState.current.issue.orEmpty()
                            render()
                        } catch (error: PumpPairingException) {
                            feedbackText.text = error.message
                        }
                    }
                }
                .show()
        }.apply { id = R.id.pump_forget }
        details.addView(forgetPumpButton)
    }

    private fun requestStart(action: String) {
        pendingAction = action
        feedbackText.text = ""
        val notificationPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        } else emptyArray()
        val missing = (PumpService.REQUIRED_PERMISSIONS + notificationPermissions)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
        else startPumpAndWatch()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        renderDeviceGuidance()
        if (PumpService.REQUIRED_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            feedbackText.setText(R.string.permission_needed)
            return
        }
        startPumpAndWatch()
    }

    @SuppressLint("MissingPermission")
    private fun startPumpAndWatch() {
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null) {
                feedbackText.setText(R.string.bluetooth_missing)
            } else if (!adapter.isEnabled) {
                feedbackText.setText(R.string.bluetooth_needed)
            } else {
                startForegroundService(Intent(this, PumpService::class.java).setAction(pendingAction)
                    .putExtra(PumpService.EXTRA_BALANCED_DISCOVERY, balancedDiscoveryBox.isChecked))
            }
        } catch (_: SecurityException) {
            feedbackText.setText(R.string.permission_needed)
        } catch (_: IllegalStateException) {
            feedbackText.setText(R.string.reader_start_failed)
        }
    }

    private fun render() {
        val pump = PumpState.current
        val secureFlag = WindowManager.LayoutParams.FLAG_SECURE
        val isSecure = window.attributes.flags and secureFlag != 0
        if (pump.running != isSecure) {
            if (pump.running) window.addFlags(secureFlag) else window.clearFlags(secureFlag)
        }
        val reading = pump.reading
        val hasValue = pump.running && !pump.stopping &&
            reading?.status == SensorStatus.OK && reading.glucoseMgDl != null
        val divisor = if (unit == GlucoseUnit.MG_DL) 1.0 else 18.0
        val glucose = reading?.glucoseMgDl?.takeIf { hasValue }?.let {
            String.format(Locale.US, if (unit == GlucoseUnit.MG_DL) "%.0f" else "%.1f", it / divisor)
        } ?: getString(R.string.no_value)
        pumpValueText.setTextIfChanged(getString(R.string.reading_format, glucose, unit.label))
        pumpStatusText.setTextIfChanged(if (hasValue) getString(R.string.pump_status_ok) else {
            val fallback = when {
                pump.stopping -> R.string.pump_stopping
                !pump.running -> R.string.pump_stopped
                reading?.status == SensorStatus.WARMUP -> R.string.status_warmup
                reading?.status == SensorStatus.SENSOR_ERROR -> R.string.status_error
                reading?.status == SensorStatus.TIME_UNKNOWN -> R.string.status_unknown
                reading?.status == SensorStatus.STALE -> R.string.status_stale
                pump.recordsReceived == 0L -> R.string.pump_waiting_for_read
                else -> R.string.status_unavailable
            }
            reading?.reason?.takeIf { it.isNotBlank() }
                ?: pump.issue?.takeIf { it.isNotBlank() }
                ?: getString(fallback)
        })
        val trend = reading?.trendMgDlPerMinute?.takeIf { hasValue }?.let {
            getString(R.string.trend_format, String.format(Locale.US, "%+.2f", it / divisor), unit.label)
        } ?: getString(R.string.unknown)
        val sampleTime = reading?.measuredAtEpochMillis?.let {
            DateFormat.getDateTimeInstance().format(Date(it))
        } ?: getString(R.string.unknown)
        pumpDetailText.setTextIfChanged(getString(R.string.pump_reading_detail, sampleTime,
            reading?.ageSeconds?.let { getString(R.string.seconds, it) } ?: getString(R.string.unknown),
            trend))
        val elapsed = SystemClock.elapsedRealtime()
        val readAge = pump.lastRecordAtElapsedMillis?.takeIf { it in 0..elapsed }?.let { elapsed - it }
        val lastRead = readAge?.let {
            getString(R.string.pump_last_read,
                DateFormat.getTimeInstance().format(Date(System.currentTimeMillis() - it)),
                getString(R.string.seconds, it / 1000))
        } ?: getString(R.string.pump_no_completed_read)
        pumpReadText.setTextIfChanged(getString(R.string.pump_read_diagnostics, lastRead, pump.recordsReceived))
        val paired = pairingStore.hasPairing()
        pumpTransportText.setTextIfChanged(getString(R.string.pump_transport_detail, pump.message,
            getString(if (paired) R.string.yes else R.string.no)) +
            (pump.issue?.let { "\n" + getString(R.string.pump_issue, it) } ?: ""))
        val idle = !pump.running && !pump.stopping
        pairPumpButton.visibility = if (paired) View.GONE else View.VISIBLE
        pairPumpButton.isEnabled = idle
        connectPumpButton.visibility = if (paired) View.VISIBLE else View.GONE
        connectPumpButton.isEnabled = idle
        stopPumpButton.isEnabled = pump.running && !pump.stopping
        forgetPumpButton.isEnabled = idle && paired
        balancedDiscoveryBox.isEnabled = idle
        if (pump.running) balancedDiscoveryBox.isChecked = pump.balancedDiscovery
        else if (idle) balancedDiscoveryBox.isChecked = getPreferences(MODE_PRIVATE).getBoolean("balancedDiscovery", false)
        val live = LiveBridgeState.current
        liveWatchText.setTextIfChanged(getString(R.string.live_bridge_detail, live.transport, live.clients,
            live.subscribers) +
            (live.issue?.let { "\n" + getString(R.string.live_bridge_issue, it) } ?: ""))
        val packetStatus = when (live.lastPacketStatus) {
            SensorStatus.OK -> R.string.watch_packet_ok
            SensorStatus.UNAVAILABLE -> R.string.watch_packet_unavailable
            SensorStatus.WARMUP -> R.string.watch_packet_warmup
            SensorStatus.SENSOR_ERROR -> R.string.watch_packet_error
            SensorStatus.TIME_UNKNOWN -> R.string.watch_packet_time_unknown
            SensorStatus.STALE -> R.string.watch_packet_stale
            null -> R.string.watch_packet_none
        }
        val lastNotification = live.lastNotificationAtElapsedMillis?.takeIf { it in 0..elapsed }?.let {
            getString(R.string.watch_send_age, getString(R.string.seconds, (elapsed - it) / 1000))
        } ?: getString(R.string.watch_send_none)
        liveWatchDiagnosticsText.setTextIfChanged(getString(R.string.live_bridge_diagnostics,
            getString(packetStatus), live.notificationsSent, lastNotification))
        feedbackText.visibility = if (feedbackText.text.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun renderDeviceGuidance() {
        val power = getSystemService(PowerManager::class.java)
        val activity = getSystemService(ActivityManager::class.java)
        val warnings = mutableListOf<String>()
        if (power?.isPowerSaveMode == true) warnings.add(getString(R.string.battery_saver_warning))
        if (activity?.isBackgroundRestricted == true) warnings.add(getString(R.string.battery_restricted_warning))
        if (power?.isIgnoringBatteryOptimizations(packageName) == false) {
            warnings.add(getString(R.string.battery_optimized_warning))
        }
        batteryStatusText.setTextIfChanged(when {
            warnings.isNotEmpty() -> warnings.joinToString("\n")
            power == null || activity == null -> getString(R.string.battery_status_unknown)
            else -> getString(R.string.battery_status_ok)
        })
        batteryStatusText.setTextColor(if (warnings.isNotEmpty()) WARNING_COLOR else labelColor())
        val notificationsAllowed = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() != false &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        notificationStatusText.visibility = if (notificationsAllowed) View.GONE else View.VISIBLE
        notificationStatusText.setTextIfChanged(getString(R.string.notification_denied))
    }

    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            feedbackText.setText(R.string.app_settings_unavailable)
        } catch (_: SecurityException) {
            feedbackText.setText(R.string.app_settings_unavailable)
        }
    }

    private fun showLicenses() {
        try {
            val text = getString(R.string.pump_license) + "\n\n" +
                assets.open("GPL-3.0.txt").bufferedReader().use { it.readText() } + "\n\n" +
                assets.open("BouncyCastle-LICENSE.txt").bufferedReader().use { it.readText() }
            val scroll = ScrollView(this).apply {
                addView(label(text, 13).apply { setPadding(dp(20), dp(12), dp(20), dp(12)) })
            }
            AlertDialog.Builder(this).setTitle(R.string.open_source_notices)
                .setView(scroll).setPositiveButton(android.R.string.ok, null).show()
        } catch (_: IOException) {
            feedbackText.setText(R.string.license_unavailable)
        }
    }

    private fun button(label: Int, action: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun label(value: String, size: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setPadding(0, dp(6), 0, dp(6))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun TextView.setTextIfChanged(value: CharSequence) {
        if (!TextUtils.equals(text, value)) text = value
    }

    private fun labelColor() = TextView(this).currentTextColor

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_PERMISSIONS = 10
        private val WARNING_COLOR = Color.rgb(255, 201, 99)
    }
}
