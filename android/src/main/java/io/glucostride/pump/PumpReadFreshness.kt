package io.glucostride.pump

internal object PumpReadFreshness {
    private const val SILENCE_MILLIS = 90_000

    fun isRecent(receivedAtElapsedMillis: Long, nowElapsedMillis: Long): Boolean =
        receivedAtElapsedMillis >= 0 &&
            nowElapsedMillis >= receivedAtElapsedMillis &&
            nowElapsedMillis - receivedAtElapsedMillis < SILENCE_MILLIS
}
