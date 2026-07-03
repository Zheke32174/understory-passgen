package com.understory.passgen

import com.understory.security.Crypto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Local password vault.
 *
 * On-disk format ([Context.getFilesDir]/vault.bin):
 *
 *   [ 1 byte  ] version (currently 1)
 *   [ 32 byte ] argon2id salt
 *   [ 4 byte  ] argon2 m_cost (KiB, big-endian)
 *   [ 4 byte  ] argon2 t_cost (big-endian)
 *   [ 4 byte  ] argon2 p_cost (big-endian)
 *   [ 4 byte  ] keystore-wrapped-aux length (big-endian)
 *   [ N bytes ] keystore-wrapped auxiliary key (random 32 bytes wrapped by
 *               the Android Keystore wrap key — provides device binding)
 *   [ 4 byte  ] iv+ciphertext length (big-endian)
 *   [ M bytes ] AES-256-GCM(plaintext_json, KEK)
 *
 * The KEK is derived as:  HKDF-light = SHA-256(argon2id_output XOR keystore_aux)
 * (Functionally: combining two independent 32-byte secrets via XOR through a
 * SHA-256 mixer. Both are required to decrypt — losing either is unrecoverable.)
 *
 * The plaintext JSON has:
 *   {
 *     "entries": [ { id, title, username, password, url, notes, created, updated, source } ],
 *   }
 */
data class VaultEntry(
    val id: String,
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String,
    val created: Long,
    val updated: Long,
    // Provenance of the entry: "" (manual), "import:bitwarden", "import:google",
    // "receipt", … Backwards-compatible: optional in fromJson, defaulted.
    val source: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("username", username)
        put("password", password)
        put("url", url)
        put("notes", notes)
        put("created", created)
        put("updated", updated)
        if (source.isNotEmpty()) put("source", source)
    }

    companion object {
        fun fromJson(o: JSONObject): VaultEntry = VaultEntry(
            id = o.optString("id", UUID.randomUUID().toString()),
            title = o.optString("title", ""),
            username = o.optString("username", ""),
            password = o.optString("password", ""),
            url = o.optString("url", ""),
            notes = o.optString("notes", ""),
            created = o.optLong("created", System.currentTimeMillis()),
            updated = o.optLong("updated", System.currentTimeMillis()),
            source = o.optString("source", ""),
        )
    }
}

data class VaultContents(
    val entries: List<VaultEntry>,
)

object Vault {

    private const val FILE = "vault.bin"
    // Format versions:
    //   v1: Argon2id(typed master) + Keystore aux. Master typed by user at
    //       unlock. Wiped on first launch of a v2-aware build (this branch).
    //   v2: master KEK is 32 random bytes wrapped under the device-credential-
    //       bound Keystore key. No typed master; unlock = BiometricPrompt
    //       (biometric or device PIN). The "master password" in the user's
    //       sense is the wrapped KEK — never displayed.
    private const val VERSION_V2: Byte = 2

    /** Default master KEK size. 32 bytes = AES-256. */
    const val MASTER_KEK_BYTES = 32

    /**
     * Title of the snake-eats-tail master-KEK entry created at vault
     * setup. Stable string used by both vault creation and the backup
     * adapter so the adapter can filter the master out of exports
     * (it's recovery metadata for *this* vault, not user data worth
     * carrying to a restored vault on a new device).
     */
    const val MASTER_ENTRY_TITLE = "passgen vault master"

    /**
     * Atomic file-replace. On Android filesystems (ext4 / f2fs) the
     * NIO Files.move with ATOMIC_MOVE is genuinely atomic; the previous
     * delete-then-rename sequence had a process-death window where the
     * vault file would be lost. Sole replace path for the vault — both
     * [writeFileV2] and [UnlockedVault.save] must go through here.
     */
    internal fun atomicReplace(src: java.io.File, dst: java.io.File) {
        try {
            java.nio.file.Files.move(
                src.toPath(),
                dst.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
            return
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // Fall through to non-atomic path.
        } catch (_: UnsupportedOperationException) {
            // Older platforms.
        } catch (_: java.io.IOException) {
            // Fall through.
        }
        // Best-effort fallback. Still safer than delete-then-rename: at least
        // the target is replaced in a single move when possible.
        if (!src.renameTo(dst)) {
            dst.delete()
            check(src.renameTo(dst)) { "vault save failed" }
        }
    }

    fun exists(ctx: Context): Boolean = File(ctx.filesDir, FILE).exists()

    /**
     * Sweep an orphan `*.tmp` left by a process kill mid-write. Called
     * from vault entry points; the real vault file is untouched until
     * the atomic rename, so the tmp is pure garbage. Repeated interrupted
     * writes would otherwise accumulate orphan tmps in filesDir.
     */
    private fun sweepTmp(ctx: Context) {
        runCatching { File(ctx.filesDir, "$FILE.tmp").delete() }
    }

    fun delete(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
        // Also drop the device-auth Keystore key on full reset.
        Crypto.deleteDeviceAuthKey()
    }

    /**
     * Read just the version byte. Returns 0 if vault doesn't exist or is
     * unreadable. Callers use this to decide whether to wipe a stale v1 vault
     * before invoking the v2 setup wizard.
     */
    fun versionOf(ctx: Context): Int {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return 0
        return runCatching { f.inputStream().use { it.read() } }.getOrDefault(-1)
    }

    fun isCurrentVersion(ctx: Context): Boolean = versionOf(ctx) == VERSION_V2.toInt()

    /**
     * v2 create. Caller has authenticated via BiometricPrompt and supplies an
     * encrypt-mode Cipher initialized with the device-auth Keystore key.
     *
     *  master_kek = 32 random bytes generated by [PasswordGenerator]'s RNG —
     *               the same pipeline the passgen IME uses to commit
     *               passwords into other apps. The "keyboard" IS the
     *               generator; setup is just another consumer.
     *  wrapped    = device_auth_cipher.doFinal(master_kek)  (auth-required)
     *
     * The master is then SELF-SEALED as the first vault entry. Snake eating
     * its tail — the vault contains the key to itself. The two layers serve
     * different purposes:
     *
     *   - Keystore-wrapped copy:  used for daily unlock; never leaves Keystore
     *                             except as a transient unwrapped buffer
     *   - In-vault entry:         visible after unlock via the same biometric-
     *                             gated Show pattern as any other entry; usable
     *                             for paper transcription, autofill into
     *                             backup-decryption flows, or external recovery
     *
     * Both copies are the same 32 bytes. Lose the device → Keystore copy gone
     * → can't unlock → can't reach the in-vault copy. The vault is not a
     * recovery substitute; the recovery path is the (forthcoming) HOTP-gated
     * backup file.
     */
    fun createV2(
        ctx: Context,
        deviceAuthEncryptCipher: javax.crypto.Cipher,
    ): UnlockedVault {
        sweepTmp(ctx)
        // 32 random bytes via the same SecureRandom path the IME uses.
        val masterKek = Crypto.randomBytes(MASTER_KEK_BYTES)
        try {
            val wrappedKekCt = deviceAuthEncryptCipher.doFinal(masterKek)
            val wrappedKekIv = deviceAuthEncryptCipher.iv

            // Seal the master into the vault as entry[0]. base64-no-padding
            // gives a 43-character string from 32 bytes. The String reference
            // here lives only as long as the entries list; vault.lock()
            // replaces the list, dropping the reference for GC.
            val masterB64 = android.util.Base64.encodeToString(
                masterKek,
                android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING,
            )
            val now = System.currentTimeMillis()
            val masterEntry = VaultEntry(
                id = java.util.UUID.randomUUID().toString(),
                title = MASTER_ENTRY_TITLE,
                username = "[generated by passgen keyboard]",
                password = masterB64,
                url = "",
                notes = "Recovery key for this vault. Generated by passgen's IME pipeline at vault creation, " +
                    "self-encrypted under a device-credential-bound Keystore key, and self-sealed here as entry[0]. " +
                    "It was never typed and never displayed during setup. " +
                    "Treat as ultimate secret. Required to decrypt offline backups (Stage 2C).",
                created = now,
                updated = now,
            )

            val contents = VaultContents(entries = listOf(masterEntry))
            val plaintext = serialize(contents).toByteArray(Charsets.UTF_8)
            val contentCt = Crypto.aesGcmEncrypt(masterKek, plaintext)
            Crypto.wipe(plaintext)

            writeFileV2(ctx, wrappedKekIv, wrappedKekCt, contentCt)

            // Build the in-memory unlocked vault directly so the user lands
            // in the vault list without re-authenticating.
            return UnlockedVault(ctx, headerV2 = HeaderV2(wrappedKekIv, wrappedKekCt), kek = masterKek.copyOf(), contents = contents)
        } finally {
            Crypto.wipe(masterKek)
        }
    }

    /**
     * v2 unlock. Caller has authenticated via BiometricPrompt and supplies a
     * decrypt-mode Cipher initialized with the stored IV.
     */
    fun unlockV2(
        ctx: Context,
        deviceAuthDecryptCipher: javax.crypto.Cipher,
    ): UnlockedVault {
        sweepTmp(ctx)
        val (header, contentCt) = readFileV2(ctx)
        val masterKek = deviceAuthDecryptCipher.doFinal(header.wrappedKekCt)
        try {
            val pt = Crypto.aesGcmDecrypt(masterKek, contentCt)
            val text = String(pt, Charsets.UTF_8)
            Crypto.wipe(pt)
            val contents = parse(text)
            return UnlockedVault(ctx, headerV2 = header, kek = masterKek.copyOf(), contents = contents)
        } finally {
            Crypto.wipe(masterKek)
        }
    }

    fun ivForUnlock(ctx: Context): ByteArray = readFileV2(ctx).first.wrappedKekIv

    private fun base64(b: ByteArray): String =
        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)

    fun serialize(c: VaultContents): String {
        val arr = JSONArray()
        for (e in c.entries) arr.put(e.toJson())
        return JSONObject().apply {
            put("entries", arr)
        }.toString()
    }

    fun parse(text: String): VaultContents {
        val o = JSONObject(text)
        val arr = o.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<VaultEntry>()
        for (i in 0 until arr.length()) entries.add(VaultEntry.fromJson(arr.getJSONObject(i)))
        return VaultContents(entries = entries)
    }

    data class HeaderV2(
        val wrappedKekIv: ByteArray,
        val wrappedKekCt: ByteArray,
    )

    private fun writeFileV2(
        ctx: Context,
        wrappedKekIv: ByteArray,
        wrappedKekCt: ByteArray,
        contentCt: ByteArray,
    ) {
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.outputStream().use { out ->
            out.write(byteArrayOf(VERSION_V2))
            out.write(intBE(wrappedKekIv.size))
            out.write(wrappedKekIv)
            out.write(intBE(wrappedKekCt.size))
            out.write(wrappedKekCt)
            out.write(intBE(contentCt.size))
            out.write(contentCt)
        }
        atomicReplace(tmp, File(ctx.filesDir, FILE))
    }

    private fun readFileV2(ctx: Context): Pair<HeaderV2, ByteArray> {
        val f = File(ctx.filesDir, FILE)
        require(f.exists()) { "vault not initialised" }
        f.inputStream().use { input ->
            val v = input.read()
            require(v == VERSION_V2.toInt()) { "expected v2 vault, got version $v" }
            // Sanity caps on each length read from the file. Without these,
            // a corrupt or hostile vault with 0xFFFFFFFF crashes ByteArray(-1)
            // and 0x7FFFFFFF allocates ~2 GB. Realistic upper bounds: GCM
            // IV is ~16 B, wrapped 32-byte KEK + GCM tag fits in <100 B,
            // and the entries blob's ceiling is a few MB even for vaults
            // with hundreds of long passwords.
            val ivLen = readIntBE(input)
            require(ivLen in 1..MAX_IV_LEN) { "vault iv length out of range: $ivLen" }
            val iv = ByteArray(ivLen); input.readFully(iv)
            val ctLen = readIntBE(input)
            require(ctLen in 1..MAX_WRAPPED_KEK_LEN) { "vault ct length out of range: $ctLen" }
            val ct = ByteArray(ctLen); input.readFully(ct)
            val contentLen = readIntBE(input)
            require(contentLen in 1..MAX_CONTENT_LEN) { "vault content length out of range: $contentLen" }
            val content = ByteArray(contentLen); input.readFully(content)
            // Reject trailing bytes — the framing bytes are NOT in the
            // GCM AAD, so silent acceptance would let an attacker who
            // can write the vault file append payload the loader skips.
            require(input.read() == -1) { "trailing bytes after vault content" }
            return HeaderV2(iv, ct) to content
        }
    }

    private const val MAX_IV_LEN = 256
    private const val MAX_WRAPPED_KEK_LEN = 256
    private const val MAX_CONTENT_LEN = 8 * 1024 * 1024  // 8 MiB

    private fun intBE(n: Int): ByteArray = byteArrayOf(
        ((n ushr 24) and 0xFF).toByte(),
        ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte(),
    )

    private fun readIntBE(input: java.io.InputStream): Int {
        val b = ByteArray(4); input.readFully(b)
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    private fun java.io.InputStream.readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = read(buf, off, buf.size - off)
            require(n >= 0) { "short read" }
            off += n
        }
    }
}

/**
 * In-memory unlocked vault. Holds the KEK so we can re-encrypt on save without
 * asking the user for the master password again. [lock] wipes the KEK and any
 * other sensitive material; the caller MUST do this when the activity goes to
 * background or the vault relock-timer fires.
 */
class UnlockedVault internal constructor(
    private val ctx: Context,
    private val headerV2: Vault.HeaderV2,
    private val kek: ByteArray,
    var contents: VaultContents,
) {
    fun save() {
        val plaintext = Vault.serialize(contents).toByteArray(Charsets.UTF_8)
        val ct = Crypto.aesGcmEncrypt(kek, plaintext)
        Crypto.wipe(plaintext)
        val tmp = java.io.File(ctx.filesDir, "vault.bin.tmp")
        val intBE: (Int) -> ByteArray = { n -> byteArrayOf(
            ((n ushr 24) and 0xFF).toByte(),
            ((n ushr 16) and 0xFF).toByte(),
            ((n ushr 8) and 0xFF).toByte(),
            (n and 0xFF).toByte(),
        ) }
        tmp.outputStream().use { out ->
            out.write(byteArrayOf(2))
            out.write(intBE(headerV2.wrappedKekIv.size))
            out.write(headerV2.wrappedKekIv)
            out.write(intBE(headerV2.wrappedKekCt.size))
            out.write(headerV2.wrappedKekCt)
            out.write(intBE(ct.size))
            out.write(ct)
        }
        val target = java.io.File(ctx.filesDir, "vault.bin")
        Vault.atomicReplace(tmp, target)
    }

    fun lock() {
        Crypto.wipe(kek)
        // Drop references to in-memory entries. Strings are immutable on the
        // JVM so we cannot wipe their backing chars, but replacing the list
        // makes the previous Strings eligible for garbage collection — which
        // is the strongest in-process erasure available without a JNI / Unsafe
        // hack. Combined with vault relock on background and process death
        // on user-leave (finishAndRemoveTask), exposure is bounded.
        contents = VaultContents(emptyList())
    }
}
