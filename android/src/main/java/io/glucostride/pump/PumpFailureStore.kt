package io.glucostride.pump

import android.content.Context

/** Only sanitized stop reasons, never readings, Bluetooth identities or packet data. */
internal class PumpFailureStore(context: Context) {
    private val preferences = context.getSharedPreferences("pump_diagnostics", Context.MODE_PRIVATE)

    fun load(): String? = preferences.getString("last_failure", null)

    fun save(message: String): Boolean =
        preferences.edit().putString("last_failure", message).commit()

    fun clear(): Boolean = preferences.edit().remove("last_failure").commit()
}
