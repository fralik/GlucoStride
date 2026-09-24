package io.glucostride.core

import java.util.Locale

enum class SensorStatus(val wire: Int) {
    OK(0), UNAVAILABLE(1), WARMUP(2), SENSOR_ERROR(3), TIME_UNKNOWN(4), STALE(5)
}

enum class GlucoseUnit(val label: String) {
    MMOL_L("mmol/L"), MG_DL("mg/dL");

    fun value(mgDl: Int): String = when (this) {
        MMOL_L -> String.format(Locale.US, "%.1f", mgDl / 18.0)
        MG_DL -> mgDl.toString()
    }

    fun rate(hundredthsMgDlPerMinute: Int): String {
        val divisor = if (this == MMOL_L) 1800.0 else 100.0
        return String.format(Locale.US, "%+.2f", hundredthsMgDlPerMinute / divisor)
    }
}
