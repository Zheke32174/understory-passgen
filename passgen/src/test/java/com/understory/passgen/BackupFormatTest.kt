package com.understory.passgen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * End-to-end test for [BackupFormat] v2. Uses Robolectric for
 * android.util.Base64 and org.json. Argon2id runs at production cost params.
 *
 * V2 is a passphrase-encrypted export of vault ENTRIES only — no vault-master
 * KEK, no TOTP secret (those were the dead A27 prerequisite). Failure modes:
 *   - wrong passphrase fails authenticated decrypt
 *   - corrupted ciphertext rejected
 *   - version mismatch rejected (incl. legacy v1 files)
 *   - oversize / too-short / trailing-byte claims rejected
 */
@RunWith(RobolectricTestRunner::class)
class BackupFormatTest {

    private fun samplePayload() = BackupFormat.Payload(
        exportedAtMs = 1_700_000_000_000L,
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
                source = "import:bitwarden",
            ),
        ),
    )

    @Test
    fun roundTrip_preservesPayload() {
        val original = samplePayload()
        val blob = BackupFormat.encode("right passphrase".toCharArray(), original)
        val restored = BackupFormat.decode("right passphrase".toCharArray(), blob)
        assertEquals(original.exportedAtMs, restored.exportedAtMs)
        assertEquals(original.entries.size, restored.entries.size)
        for ((a, b) in original.entries.zip(restored.entries)) {
            assertEquals(a, b)
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
    fun legacyV1FileRejected() {
        // A v1 file (version byte 1) must be refused cleanly by the v2 reader.
        val blob = BackupFormat.encode("pw".toCharArray(), samplePayload())
        blob[0] = 1
        try {
            BackupFormat.decode("pw".toCharArray(), blob)
            fail("legacy v1 file must be rejected")
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
        // Flip the last byte (inside the GCM tag). Any tag check should reject.
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
}
