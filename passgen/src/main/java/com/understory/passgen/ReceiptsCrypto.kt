package com.understory.passgen

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore wrapping for the receipt store ([Receipts]).
 *
 * Design §1.2: the receipts file uses its OWN Keystore alias with an
 * auth-NOT-required spec, because the autofill/IME generate paths run headless
 * (an invisible activity / an IME service) and cannot show a BiometricPrompt.
 * The file is still device-bound (non-exportable Keystore key, StrongBox where
 * available); read-back is gated at the UI layer by a prior vault unlock in the
 * same session (§5.4).
 *
 * Because this key is NOT auth-bound it does NOT set
 * setInvalidatedByBiometricEnrollment(true) — so receipts survive the exact
 * biometric-re-enrollment event that permanently bricks the vault key. That
 * survival is the whole point: a value-less receipt may be the user's only
 * record of what they need to recover after the vault is lost.
 *
 * This lives in the passgen package (not common-security) because the alias and
 * its non-auth spec are passgen-specific; the shared Crypto helpers are all
 * auth-bound vault keys. It deliberately mirrors Crypto's GCM parameters so the
 * on-disk framing matches vault.bin's shape.
 */
internal object ReceiptsCrypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val RECEIPTS_KEY_ALIAS = "passgen_receipts_device_auth_v1"
    private const val GCM_TAG_BITS = 128

    /** Encrypt-mode cipher for the receipts content key. No prompt required. */
    fun cipherForEncrypt(): Cipher {
        val key = ensureKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    /** Decrypt-mode cipher for the stored IV. No prompt required. */
    fun cipherForDecrypt(iv: ByteArray): Cipher {
        val key = readKey() ?: error("receipts key missing")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher
    }

    fun keyExists(): Boolean = readKey() != null

    fun deleteKey() {
        runCatching {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (ks.containsAlias(RECEIPTS_KEY_ALIAS)) ks.deleteEntry(RECEIPTS_KEY_ALIAS)
        }
    }

    private fun readKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (!ks.containsAlias(RECEIPTS_KEY_ALIAS)) return null
        return (ks.getEntry(RECEIPTS_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    private fun ensureKey(): SecretKey {
        readKey()?.let { return it }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        // Same builder as Crypto.ensureDeviceAuthKey() but WITHOUT
        // setUserAuthenticationRequired / setUserAuthenticationParameters /
        // setInvalidatedByBiometricEnrollment. Not auth-bound: writable from the
        // headless generate paths, and survives biometric re-enrollment.
        val spec = KeyGenParameterSpec.Builder(
            RECEIPTS_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .apply { runCatching { setIsStrongBoxBacked(true) } }
            .build()
        kg.init(spec)
        return kg.generateKey()
    }
}
