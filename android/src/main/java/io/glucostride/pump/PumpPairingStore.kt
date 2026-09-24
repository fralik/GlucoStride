package io.glucostride.pump

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class PumpPairingException(message: String) : Exception(message)

class PumpPairingStore(context: Context) {
    private val preferences = context.getSharedPreferences("pump_pairing", Context.MODE_PRIVATE)

    fun hasPairing(): Boolean = preferences.contains("sealed_address")

    fun load(): String? {
        val encoded = preferences.getString("sealed_address", null) ?: return null
        return protectedOperation {
            val key = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
                ?: throw PumpPairingException("Pairing key is unavailable. Forget local pairing and pair again.")
            val address = PairingCipher.decrypt(Base64.decode(encoded, Base64.NO_WRAP), key)
                .toString(Charsets.US_ASCII)
            if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                throw PumpPairingException("Stored pairing is invalid. Forget local pairing and pair again.")
            }
            address
        }
    }

    fun save(address: String) = protectedOperation {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            throw PumpPairingException("The pump returned an invalid pairing identity.")
        }
        val store = keyStore()
        val key = store.getKey(KEY_ALIAS, null) as? SecretKey ?: run {
            if (hasPairing()) {
                throw PumpPairingException("Pairing key is unavailable. Forget local pairing and pair again.")
            }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build())
            }.generateKey()
        }
        val encoded = Base64.encodeToString(
            PairingCipher.encrypt(address.toByteArray(Charsets.US_ASCII), key), Base64.NO_WRAP,
        )
        if (!preferences.edit().putString("sealed_address", encoded).commit()) {
            throw PumpPairingException("Could not save local pairing.")
        }
    }

    fun clear() = protectedOperation {
        if (!preferences.edit().remove("sealed_address").commit()) {
            throw PumpPairingException("Could not remove local pairing.")
        }
        keyStore().deleteEntry(KEY_ALIAS)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun <T> protectedOperation(action: () -> T): T = try {
        action()
    } catch (_: GeneralSecurityException) {
        throw PumpPairingException("Android could not unlock pairing storage. Forget local pairing and pair again.")
    } catch (_: IOException) {
        throw PumpPairingException("Pairing storage could not be read or written.")
    } catch (_: IllegalArgumentException) {
        throw PumpPairingException("Stored pairing is damaged. Forget local pairing and pair again.")
    }

    companion object {
        private const val KEY_ALIAS = "io.glucostride.pump.pairing.v1"
    }
}
