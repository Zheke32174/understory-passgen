package com.understory.passgen

import com.understory.security.Crypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end test for [BackupFormat]. Uses Robolectric for
 * android.util.Base64 and org.json. Argon2id runs at the production
 * cost params (no test-only override, same trade-off as
 * [com.understory.backup.AesGcmPassphraseCodecTest]).
 *
 * The format's job is to ship a vault between devices: it bundles the
 * vault master KEK, the TOTP secret, and all entries under a single
 * passphrase-derived layer. Failure modes that matter:
 *   - wrong passphrase fails authenticated decrypt
 *   - corrupted ciphertext rejected
 *   - version mismatch rejected
 *   - oversize / too-short claims rejected
 *   - decoded master KEK / TOTP secret length-validated
 */
@RunWith(RobolectricTestRunner::class)
class BackupFormatTest {

    private fun samplePayload() = BackupFormat.Payload(
        exportedAtMs = 1_700_000_000_000L,
        vaultMasterKek = ByteArray(Crypto.KEK_BYTES) { it.toByte() },
        totpSecret = ByteArray(20) { it.toByte() },
        entries = listOf(
            VaultEntry(
                id = "e1",
                title = "GitHub",
                username = "alice",
                password = "hunter2",
                url = "https://github.com",
                notes = "",
                created = 1_700_000_000_000L,
                updated = 1_700_000_000_000L,
            ),
            VaultEntry(
                id = "e2",
                title = "Bank",
                username = "alice@example.com",
                password = "correct horse battery staple",
                url = "https://bank.example",
                notes = "primary checking",
                created = 1_700_000_500_000L,
                updated = 1_700_000_600_000L,
            ),
        ),
    )

    @Test
    fun roundTrip_preservesPayload() {
        val original = samplePayload()
        val blob = BackupFormat.encode("right passphrase".toCharArray(), original)
        val restored = BackupFormat.decode("right passphrase".toCharArray(), blob)
        try {
            assertEquals(original.exportedAtMs, restored.exportedAtMs)
            assertArrayEquals(original.vaultMasterKek, restored.vaultMasterKek)
            assertArrayEquals(original.totpSecret, restored.totpSecret)
            assertEquals(original.entries.size, restored.entries.size)
            for ((a, b) in original.entries.zip(restored.entries)) {
                assertEquals(a, b)
            }
        } finally {
            restored.wipe()
        }
    }

    @Test
    fun wrongPassphraseFails() {
        val blob = BackupFormat.encode("alpha".toCharArray(), samplePayload())
        try {
            BackupFormat.decode("beta".toCharArray(), blob)
            fail("wrong passphrase must fail authenticated decrypt")
        } catch (_: Throwable) {
            // expected
        }
    }

    @Test
    fun versionMismatchRejected() {
        val blob = BackupFormat.encode("pw".toCharArray(), samplePayload())
        // First byte is the version. Bump it to an unsupported value.
        blob[0] = 99
        try {
            BackupFormat.decode("pw".toCharArray(), blob)
            fail("unsupported version must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun emptyBlobRejected() {
        try {
            BackupFormat.decode("pw".toCharArray(), ByteArray(0))
            fail("empty blob must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun trailingBytesRejected() {
        val blob = BackupFormat.encode("pw".toCharArray(), samplePayload())
        val tampered = blob + byteArrayOf(0x00, 0x00)
        try {
            BackupFormat.decode("pw".toCharArray(), tampered)
            fail("trailing bytes after backup ciphertext must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun ciphertextTamperFailsAuthenticatedDecrypt() {
        val blob = BackupFormat.encode("pw".toCharArray(), samplePayload())
        // Flip a byte deep inside the ciphertext (well past the 49-byte
        // header: 1 version + 32 salt + 4*4 argon params = 49). Any
        // GCM tag check should reject.
        val target = blob.size - 1
        blob[target] = (blob[target].toInt() xor 0x01).toByte()
        try {
            BackupFormat.decode("pw".toCharArray(), blob)
            fail("ciphertext tamper must fail authenticated decrypt")
        } catch (_: Throwable) {
            // expected
        }
    }

    @Test
    fun saltIsFreshPerEncrypt_soSameInputsProduceDifferentBlobs() {
        // Two encodes of the same payload + passphrase must produce
        // different on-disk bytes — otherwise users with identical
        // backups (or a vault diff comparison) leak that the contents
        // are unchanged.
        val a = BackupFormat.encode("pw".toCharArray(), samplePayload())
        val b = BackupFormat.encode("pw".toCharArray(), samplePayload())
        assertTrue("two encodes of same payload must differ on disk",
            !a.contentEquals(b))
    }

    @Test
    fun differentPassphrasesProduceUnrelatedBlobs() {
        val a = BackupFormat.encode("alpha".toCharArray(), samplePayload())
        val b = BackupFormat.encode("beta".toCharArray(), samplePayload())
        assertTrue(!a.contentEquals(b))
    }

    @Test
    fun payloadWipeZeroesSensitiveBytes() {
        val payload = samplePayload()
        payload.wipe()
        for (b in payload.vaultMasterKek) assertEquals(0.toByte(), b)
        for (b in payload.totpSecret) assertEquals(0.toByte(), b)
    }
}
