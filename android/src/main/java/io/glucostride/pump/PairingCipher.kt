package io.glucostride.pump

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal object PairingCipher {
    private val context = "GlucoStride/pump-pairing/v1".toByteArray(Charsets.US_ASCII)

    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(context)
        val iv = cipher.iv
        check(iv.size == 12) { "Unexpected pairing cipher nonce length." }
        return byteArrayOf(1) + iv + cipher.doFinal(plaintext)
    }

    fun decrypt(encoded: ByteArray, key: SecretKey): ByteArray {
        if (encoded.size < 30 || encoded[0] != 1.toByte()) {
            throw GeneralSecurityException("Unsupported pairing record.")
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encoded.copyOfRange(1, 13)))
        cipher.updateAAD(context)
        return cipher.doFinal(encoded.copyOfRange(13, encoded.size))
    }
}
