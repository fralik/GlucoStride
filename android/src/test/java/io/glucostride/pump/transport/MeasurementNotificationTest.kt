package io.glucostride.pump.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class MeasurementNotificationTest {
    @Test
    fun notificationOutsideReportIsAuthenticatedButNotCollected() {
        var decrypted = 0
        val ciphertext = byteArrayOf(1, 2, 3, 4)
        val decode: (ByteArray) -> ByteArray = {
            decrypted++
            byteArrayOf(6, 0, 126, 0, 0, 0)
        }
        assertNull(decryptMeasurementNotification(ciphertext, false, decode))
        assertArrayEquals(byteArrayOf(6, 0, 126, 0, 0, 0),
            decryptMeasurementNotification(ciphertext, true, decode))
        assertNull(decryptMeasurementNotification(ciphertext, false, decode))
        assertEquals(3, decrypted)
    }

    @Test
    fun unrequestedNotificationsStillRejectBadAuthenticationOrTruncatedCiphertext() {
        assertThrows(IllegalArgumentException::class.java) {
            decryptMeasurementNotification(ByteArray(4), false) { throw IllegalArgumentException("Invalid frame") }
        }
        assertThrows(TransportProtocolException::class.java) {
            decryptMeasurementNotification(ByteArray(3), false) { error("Must reject before decrypting") }
        }
    }
}
