package io.glucostride.pump

import java.security.GeneralSecurityException
import javax.crypto.KeyGenerator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test
    fun pairingRoundTripsWithDistinctRandomNonces() {
        val key = key()
        val data = "00:11:22:33:44:55".toByteArray()
        val first = PairingCipher.encrypt(data, key)
        val second = PairingCipher.encrypt(data, key)
        assertArrayEquals(data, PairingCipher.decrypt(first, key))
        assertFalse(first.contentEquals(second))
        assertFalse(String(first).contains(String(data)))
    }

    @Test
    fun corruptWrongKeyTruncatedAndUnsupportedRecordsAreRejected() {
        val key = key()
        val encrypted = PairingCipher.encrypt("00:11:22:33:44:55".toByteArray(), key)
        val tampered = encrypted.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        for (bytes in listOf(tampered, encrypted.copyOf(10), encrypted.copyOf().apply { this[0] = 2 })) {
            assertThrows(GeneralSecurityException::class.java) { PairingCipher.decrypt(bytes, key) }
        }
        assertThrows(GeneralSecurityException::class.java) { PairingCipher.decrypt(encrypted, key()) }
    }
}
