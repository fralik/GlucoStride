package io.glucostride.pump.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openminimed.sake.DeviceType
import org.openminimed.sake.KeyDatabase
import org.openminimed.sake.MacFailureException
import org.openminimed.sake.SakeClient
import org.openminimed.sake.StaticKeys
import org.openminimed.sake.crypto.AesCmac
import org.openminimed.sake.crypto.AesEcb
import java.security.SecureRandom

class PumpSakeSessionTest {
    private fun authenticated(): Pair<PumpSakeSession, SakeClient> {
        // Synthetic peers need distinct permits for their roles. Never embed or dump
        // real pump keys, and do not reuse a phone's permit as a simulated pump permit.
        val random = SecureRandom()
        fun key() = ByteArray(16).also(random::nextBytes)
        val derivation = key()
        val authentication = key()
        val permitEncryption = key()
        val permitAuthentication = key()
        fun keys(type: DeviceType): StaticKeys {
            val body = ByteArray(16)
            body[1] = type.value().toByte()
            val mac = AesCmac(permitAuthentication, 4)
            mac.update(body.copyOf(12))
            mac.digest().copyInto(body, 12)
            return StaticKeys(derivation, authentication, permitEncryption, permitAuthentication,
                AesEcb.encryptBlock(permitEncryption, body))
        }
        val phoneDb = KeyDatabase(DeviceType.MOBILE_APPLICATION,
            mapOf(DeviceType.INSULIN_PUMP to keys(DeviceType.MOBILE_APPLICATION)), ByteArray(4))
        val pumpDb = KeyDatabase(DeviceType.INSULIN_PUMP,
            mapOf(DeviceType.MOBILE_APPLICATION to keys(DeviceType.INSULIN_PUMP)), ByteArray(4))
        val phone = PumpSakeSession(phoneDb)
        val pump = SakeClient(pumpDb, DeviceType.INSULIN_PUMP)
        var response = phone.handshake(ByteArray(20))
        repeat(3) {
            response = phone.handshake(pump.handshake(requireNotNull(response)))
        }
        assertNull(response)
        assertTrue(phone.complete)
        return phone to pump
    }

    @Test
    fun twoSidedAuthenticationKeepsSuccessivePumpPayloadsDecryptable() {
        val (phone, pump) = authenticated()
        repeat(5) { index ->
            val synthetic = ByteArray(11) { (it + index).toByte() }
            val encrypted = pump.session().clientCrypt().encrypt(synthetic)
            assertFalse(encrypted.contentEquals(synthetic))
            assertArrayEquals(synthetic, phone.decrypt(encrypted))
        }
    }

    @Test
    fun corruptedMacIsRejectedRatherThanTryingAnotherCipher() {
        val (phone, pump) = authenticated()
        val encrypted = pump.session().clientCrypt().encrypt(ByteArray(9) { it.toByte() })
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(MacFailureException::class.java) { phone.decrypt(encrypted) }
    }

    @Test
    fun noPayloadCanBeReadBeforeAuthentication() {
        val phone = PumpSakeSession()
        assertArrayEquals(ByteArray(20), phone.wakeUp())
        assertFalse(phone.complete)
        assertThrows(IllegalStateException::class.java) { phone.decrypt(ByteArray(12)) }
    }
}
