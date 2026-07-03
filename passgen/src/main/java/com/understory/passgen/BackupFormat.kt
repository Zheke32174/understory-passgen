package com.understory.passgen

import com.understory.security.Crypto

import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable passphrase-encrypted export of vault ENTRIES (v2).
 *
 * The vault on disk is encrypted with a Keystore-bound AES key that cannot
 * leave the device — right for daily use, fatal for recovery (lost device →
 * vault gone). This format produces a self-contained `.ukbackup` you can carry
 * to a new device (USB, cloud, paper QR for tiny vaults), encrypted with a
 * passphrase you choose at export time.
 *
 * V2 changed the payload from a device-KEK escrow to a plain entry export:
 *   - dropped `vault_master_kek_b64` and `totp_secret_b64` — the old design
 *     required a TOTP secret stored as a nonexistent `entry[1]`, a dead
 *     prerequisite (audit A27). A backup is a passphrase-encrypted copy of the
 *     user's CREDENTIALS; restoring reconstructs them onto a fresh vault (new
 *     device, new KEK), never transplanting a raw hardware KEK.
 *   - bumped file+payload VERSION to 2. A v2 reader refuses v1 cleanly (v1 files
 *     only ever existed in tests).
 *
 * The Argon2id + AES-GCM framing, trailing-byte rejection and length caps are
 * unchanged — they were correct and unit-tested.
 *
 * On-disk layout:
 *   [ 1 byte    ] version (= 2)
 *   [ 32 bytes  ] Argon2id salt
 *   [ 4 byte BE ] Argon2id m_cost (KiB)
 *   [ 4 byte BE ] Argon2id t_cost (iterations)
 *   [ 4 byte BE ] Argon2id p_cost (parallelism)
 *   [ 4 byte BE ] payload-cipher length
 *   [ N bytes   ] AES-256-GCM(payload_json, KEK)
 *
 * KEK derivation: passphrase → Argon2id(stored params + salt) → 32-byte KEK.
 *
 * Payload JSON shape:
 *   { "version": 2, "exported_at": <unix_ms>, "entries": [ <VaultEntry json>, ... ] }
 */
object BackupFormat {
    private const val VERSION: Byte = 2
    private const val ARGON_M_DEFAULT = Crypto.ARGON2_MEMORY_KIB
    private const val ARGON_T_DEFAULT = Crypto.ARGON2_ITERATIONS
    private const val ARGON_P_DEFAULT = Crypto.ARGON2_PARALLELISM

    data class Payload(
        val exportedAtMs: Long,
        val entries: List<VaultEntry>,
    )

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
     * Decrypt the [blob] using [passphrase] and return the parsed payload.
     * Throws on wrong passphrase (GCM tag mismatch) or malformed data.
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
        // Bound ctLen positively and require exact equality so trailing bytes
        // after the ciphertext are rejected, not silently dropped (they are NOT
        // in the GCM AAD, so acceptance would let a file-writer append payload).
        require(ctLen in 1..MAX_BACKUP_CT_LEN) { "backup ct length out of range: $ctLen" }
        require(p + ctLen == blob.size) { "trailing bytes after backup ciphertext" }
        val ct = blob.copyOfRange(p, p + ctLen)
        return Parsed(salt, argonM, argonT, argonP, ct)
    }

    /**
     * 64 MiB cap on the backup ciphertext length field. Even a vault with
     * thousands of entries fits in well under 1 MB; the cap leaves comfortable
     * headroom while keeping a hostile blob from claiming absurd allocations.
     */
    private const val MAX_BACKUP_CT_LEN = 64 * 1024 * 1024

    private fun payloadToJson(p: Payload): JSONObject {
        val arr = JSONArray()
        for (e in p.entries) arr.put(e.toJson())
        return JSONObject().apply {
            put("version", 2)
            put("exported_at", p.exportedAtMs)
            put("entries", arr)
        }
    }

    private fun jsonToPayload(o: JSONObject): Payload {
        require(o.optInt("version") == 2) { "unsupported payload version" }
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<VaultEntry>()
        for (i in 0 until arr.length()) entries.add(VaultEntry.fromJson(arr.getJSONObject(i)))
        return Payload(
            exportedAtMs = o.optLong("exported_at"),
            entries = entries,
        )
    }

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
