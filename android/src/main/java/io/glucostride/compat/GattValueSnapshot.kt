package io.glucostride.compat

internal object GattValueSnapshot {
    private const val MAX_INBOUND_BYTES = 515
    private const val VALUE_CALLBACK_API = 33

    // Copy at callback ingress, before Android can reuse its mutable characteristic value.
    // Missing/oversized input stays empty so the existing protocol validators reject it.
    fun copy(value: ByteArray?): ByteArray =
        if (value != null && value.size <= MAX_INBOUND_BYTES) value.copyOf() else byteArrayOf()

    fun legacy(sdk: Int, readValue: () -> ByteArray?): ByteArray? =
        if (sdk < VALUE_CALLBACK_API) copy(readValue()) else null
}
