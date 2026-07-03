package com.understory.passgen

import com.understory.security.Crypto

import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable encrypted backup file format (v1, AES-only — Stage 2C).
 *
 * The vault on disk is encrypted with a Keystore-bound AES key that
 * cannot leave the device. That's the right shape for daily local use,
 * but it makes recovery impossible: lost device → vault gone. The
 * backup format produces a self-contained file you can carry to a new
 * device (USB stick, cloud, paper QR for tiny vaults). It is encrypted
 * with a passphrase you choose at backup time — separate from anything
 * else, paired with the HOTP secret stored as entry[1] of the vault.
 *
 * On-disk layout:
 *
 *   [ 1 byte    ] version (= 1)
 *   [ 32 bytes  ] Argon2id salt
 *   [ 4 byte BE ] Argon2id m_cost (KiB)
 *   [ 4 byte BE ] Argon2id t_cost (iterations)
 *   [ 4 byte BE ] Argon2id p_cost (parallelism)
 *   [ 4 byte BE ] payload-cipher length
 *   [ N bytes   ] AES-256-GCM(payload_json, KEK)
 *
 * KEK derivation:
 *   passphrase → Argon2id(stored params + salt) → 32-byte KEK
 *
 * Payload JSON shape:
 *   {
 *     "version": 1,
 *     "exported_at": <unix_ms>,
 *     "vault_master_kek_b64": "<base64 of 32 bytes>",
 *     "totp_secret_b64": "<base64 of 20 bytes>",
 *     "entries": [ <VaultEntry json>, ... ]
 *   }
 *
 * Stage 2C-2 (next push after this) adds an ML-KEM-1024 hybrid wrap
 * layer for genuine PQC defense on backup files that travel off-device.
 * Format will become v2 when that lands; v1 readers will refuse v2
 * files cleanly.
 *
 * Restore-on-new-device flow (Stage 2C UI work):
 *   1. User picks the backup file via SAF
 *   2. User types the backup passphrase
 *   3. User types the current TOTP code from their authenticator
 *      (verifies the backup was produced by the same vault)
 *   4. Decode payload
 *   5. Re-wrap vault_master_kek_b64 under the NEW device's
 *      Keystore key (BiometricPrompt-gated)
 *   6. Restore entries
 */
object BackupFormat {
    private const val VERSION: Byte = 1
    private const val ARGON_M_DEFAULT = Crypto.ARGON2_MEMORY_KIB
    private const val ARGON_T_DEFAULT = Crypto.ARGON2_ITERATIONS
    private const val ARGON_P_DEFAULT = Crypto.ARGON2_PARALLELISM

    data class Payload(
        val exportedAtMs: Long,
        val vaultMasterKek: ByteArray,
        val totpSecret: ByteArray,
        val entries: List<VaultEntry>,
    ) {
        /** Caller MUST invoke this when the payload is no longer needed. */
        fun wipe() {
            Crypto.wipe(vaultMasterKek)
            Crypto.wipe(totpSecret)
        }
    }

    /**
     * Encrypt [payload] under [passphrase] and return the on-disk byte
     * sequence. Caller writes to a SAF URI of their choice.
     */
    fun encode(passphrase: CharArray, payload: Payload): ByteArray {
        val salt = Crypto.randomBytes(Crypto.SALT_BYTES)
        val kek = Crypto.argon2id(passphrase, salt, ARGON_M_DEFAULT, ARGON_T_DEFAULT, ARGON_P_DEFAULT)
        try {
            val json = payloadToJson(payload).toString().toByteArray(Charsets.UTF_8)
            val ct = Crypto.aesGcmEncrypt(kek, json)
            Crypto.wipe(json)
            return assemble(salt, ARGON_M_DEFAULT, ARGON_T_DEFAULT, ARGON_P_DEFAULT, ct)
        } finally {
            Crypto.wipe(kek)
        }
    }

    /**
     * Decrypt the [blob] using [passphrase] and return the parsed
     * payload. Throws on wrong passphrase (GCM tag mismatch) or
     * malformed data. Caller MUST invoke `payload.wipe()` after using
     * the returned data.
     */
    fun decode(passphrase: CharArray, blob: ByteArray): Payload {
        val parsed = parseHeader(blob)
        val kek = Crypto.argon2id(passphrase, parsed.salt, parsed.argonM, parsed.argonT, parsed.argonP)
        try {
            val pt = Crypto.aesGcmDecrypt(kek, parsed.ct)
            val text = String(pt, Charsets.UTF_8)
            Crypto.wipe(pt)
            return jsonToPayload(JSONObject(text))
        } finally {
            Crypto.wipe(kek)
        }
    }

    // -- helpers -----------------------------------------------------------

    private data class Parsed(
        val salt: ByteArray,
        val argonM: Int,
        val argonT: Int,
        val argonP: Int,
        val ct: ByteArray,
    )

    private fun assemble(
        salt: ByteArray,
        argonM: Int,
        argonT: Int,
        argonP: Int,
        ct: ByteArray,
    ): ByteArray {
        require(salt.size == Crypto.SALT_BYTES)
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(VERSION))
        out.write(salt)
        out.write(intBE(argonM))
        out.write(intBE(argonT))
        out.write(intBE(argonP))
        out.write(intBE(ct.size))
        out.write(ct)
        return out.toByteArray()
    }

    private fun parseHeader(blob: ByteArray): Parsed {
        require(blob.isNotEmpty()) { "empty blob" }
        require(blob[0] == VERSION) { "unsupported backup version: ${blob[0]}" }
        var p = 1
        val salt = blob.copyOfRange(p, p + Crypto.SALT_BYTES); p += Crypto.SALT_BYTES
        val argonM = readIntBE(blob, p); p += 4
        val argonT = readIntBE(blob, p); p += 4
        val argonP = readIntBE(blob, p); p += 4
        val ctLen = readIntBE(blob, p); p += 4
        // Bound ctLen positively (an attacker-supplied 0x7FFFFFFF would
        // pass `p + ctLen <= blob.size` only if blob is also ~2 GB, but
        // a hostile blob declaring ctLen ~= blob.size with the actual
        // ciphertext smaller than that is the realistic attack — refuse
        // the implausible upper bound) and require exact equality so
        // trailing bytes after the ciphertext are rejected, not silently
        // dropped.
        require(ctLen in 1..MAX_BACKUP_CT_LEN) { "backup ct length out of range: $ctLen" }
        require(p + ctLen == blob.size) { "trailing bytes after backup ciphertext" }
        val ct = blob.copyOfRange(p, p + ctLen)
        return Parsed(salt, argonM, argonT, argonP, ct)
    }

    /**
     * 64 MiB cap on the backup ciphertext length field. Even a vault
     * with thousands of entries fits in well under 1 MB; the cap leaves
     * comfortable headroom while keeping a hostile blob from claiming
     * absurd allocations.
     */
    private const val MAX_BACKUP_CT_LEN = 64 * 1024 * 1024

    private fun payloadToJson(p: Payload): JSONObject {
        val arr = JSONArray()
        for (e in p.entries) arr.put(e.toJson())
        return JSONObject().apply {
            put("version", 1)
            put("exported_at", p.exportedAtMs)
            put("vault_master_kek_b64", base64(p.vaultMasterKek))
            put("totp_secret_b64", base64(p.totpSecret))
            put("entries", arr)
        }
    }

    private fun jsonToPayload(o: JSONObject): Payload {
        require(o.optInt("version") == 1) { "unsupported payload version" }
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<VaultEntry>()
        for (i in 0 until arr.length()) entries.add(VaultEntry.fromJson(arr.getJSONObject(i)))
        val vaultMasterKek = decodeBase64(o.getString("vault_master_kek_b64"))
        val totpSecret = decodeBase64(o.getString("totp_secret_b64"))
        // Length-validate decoded keys before they go anywhere. A backup
        // claiming a 1-byte master_kek would crash much later inside
        // Crypto with a less diagnostic message; rejecting here pinpoints
        // the bad import.
        require(vaultMasterKek.size == Crypto.KEK_BYTES) {
            "vault_master_kek_b64 decodes to ${vaultMasterKek.size} bytes; expected ${Crypto.KEK_BYTES}"
        }
        require(totpSecret.size in TOTP_SECRET_MIN..TOTP_SECRET_MAX) {
            "totp_secret_b64 decodes to ${totpSecret.size} bytes; expected $TOTP_SECRET_MIN..$TOTP_SECRET_MAX"
        }
        return Payload(
            exportedAtMs = o.optLong("exported_at"),
            vaultMasterKek = vaultMasterKek,
            totpSecret = totpSecret,
            entries = entries,
        )
    }

    /** RFC 4226 §4 R6 recommends 20 bytes; we accept 10..64. */
    private const val TOTP_SECRET_MIN = 10
    private const val TOTP_SECRET_MAX = 64

    private fun base64(b: ByteArray): String =
        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)

    private fun decodeBase64(s: String): ByteArray =
        android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    private fun intBE(n: Int): ByteArray = byteArrayOf(
        ((n ushr 24) and 0xFF).toByte(),
        ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte(),
    )

    private fun readIntBE(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
        ((b[offset + 1].toInt() and 0xFF) shl 16) or
        ((b[offset + 2].toInt() and 0xFF) shl 8) or
        (b[offset + 3].toInt() and 0xFF)
}
