package com.understory.passgen

import android.content.Context
import com.understory.security.Crypto
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Generated-password receipt store (design §1.1 / §2.1). Resolves the
 * account-lockout trap (A7/A11): every generate-and-deliver path
 * (autofill / IME / clipboard) writes a receipt so a signup done through
 * passgen can never silently lock the user out.
 *
 * A **second** device-encrypted file, independent of the vault KEK, because
 * receipts are written from the autofill/IME generate paths which do NOT run
 * the vault-unlock biometric. It reuses the vault.bin v2 framing verbatim
 * (version byte, wrapped-content-key iv+ct, content iv+ct) but wraps its
 * content key with [ReceiptsCrypto]'s auth-NOT-required key so writes need no
 * prompt. Read-back for the Receipts screen is gated by a prior vault unlock in
 * the same session (§5.4).
 *
 * On-disk `receipts.bin` (in [Context.getFilesDir]):
 *
 *   [ 1 byte  ] version (= 1)
 *   [ 4 BE    ] wrapped-content-key IV length ; [N] IV
 *   [ 4 BE    ] wrapped-content-key CT length ; [N] CT  (32-byte content key,
 *               AES-wrapped by the receipts Keystore key)
 *   [ 4 BE    ] receipts-blob length ; [M] AES-256-GCM(receipts_json, content_key)
 *
 * A vault reset does NOT delete receipts (different key) — see [deleteAll],
 * called only by a full app reset, never by vault reset.
 */
object Receipts {

    private const val FILE = "receipts.bin"
    private const val VERSION: Byte = 1

    /** Non-secret plaintext counter so the ledger button can badge without a decrypt. */
    private const val PREF = "passgen_receipts"
    private const val K_UNCLAIMED = "receipts_unclaimed_count"

    /** Cap the receipt list; evict oldest claimed first, then oldest overall. */
    private const val MAX_RECEIPTS = 500

    private const val MAX_IV_LEN = 256
    private const val MAX_WRAPPED_KEY_LEN = 256
    private const val MAX_CONTENT_LEN = 4 * 1024 * 1024 // 4 MiB

    data class Receipt(
        val id: String,
        val source: String,       // "autofill" | "ime" | "clipboard"
        val target: String,       // package or web domain, "" if unknown
        val targetKind: String,   // "package" | "domain" | "unknown"
        val createdAt: Long,
        val length: Int,
        val lowers: Boolean,
        val uppers: Boolean,
        val digits: Boolean,
        val symbols: Boolean,
        val mode: String = Settings.MODE_CHARS, // "chars" | "words"
        val words: Int = 0,                     // word count when mode == "words"
        val savedValue: String?,  // null unless the user armed "keep generated value"
        val claimed: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("source", source)
            put("target", target)
            put("targetKind", targetKind)
            put("createdAt", createdAt)
            put("shape", JSONObject().apply {
                put("length", length)
                put("lowers", lowers)
                put("uppers", uppers)
                put("digits", digits)
                put("symbols", symbols)
                put("mode", mode)
                put("words", words)
            })
            if (savedValue != null) put("savedValue", savedValue)
            put("claimed", claimed)
        }

        companion object {
            fun fromJson(o: JSONObject): Receipt {
                val shape = o.optJSONObject("shape") ?: JSONObject()
                return Receipt(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    source = o.optString("source", "unknown"),
                    target = o.optString("target", ""),
                    targetKind = o.optString("targetKind", "unknown"),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    length = shape.optInt("length", 0),
                    lowers = shape.optBoolean("lowers", false),
                    uppers = shape.optBoolean("uppers", false),
                    digits = shape.optBoolean("digits", false),
                    symbols = shape.optBoolean("symbols", false),
                    mode = shape.optString("mode", Settings.MODE_CHARS),
                    words = shape.optInt("words", 0),
                    savedValue = if (o.has("savedValue")) o.optString("savedValue") else null,
                    claimed = o.optBoolean("claimed", false),
                )
            }
        }
    }

    fun exists(ctx: Context): Boolean = File(ctx.filesDir, FILE).exists()

    /**
     * Append a receipt for a just-delivered generated password. Runs the
     * read-modify-write synchronously; callers on a headless path (autofill
     * invisible activity, IME) invoke this on [com.understory.security.ui.Bg.io]
     * and MUST ensure durability before finishing (the process may die). The
     * whole call is best-effort — a receipt failure must never crash the caller.
     *
     * @param value copied into a String only when non-null (user armed "keep
     *   generated value"); the caller still owns and wipes the CharArray.
     */
    fun append(
        ctx: Context,
        source: String,
        target: String,
        targetKind: String,
        shape: Settings.Snapshot,
        value: CharArray?,
    ) {
        val existing = runCatching { load(ctx) }.getOrDefault(emptyList())
        val now = System.currentTimeMillis()
        val receipt = Receipt(
            id = UUID.randomUUID().toString(),
            source = source,
            target = target,
            targetKind = targetKind,
            createdAt = now,
            length = shape.length,
            lowers = shape.lowers,
            uppers = shape.uppers,
            digits = shape.digits,
            symbols = shape.symbols,
            mode = shape.mode,
            words = shape.words,
            savedValue = value?.let { String(it) },
            claimed = false,
        )
        val trimmed = evict(existing + receipt)
        writeAll(ctx, trimmed)
        updateUnclaimedCounter(ctx, trimmed)
    }

    /** Decrypt and return all receipts, oldest first. Caller = Receipts screen (post vault-unlock). */
    fun load(ctx: Context): List<Receipt> {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return emptyList()
        val (wrappedIv, wrappedCt, contentCt) = readFrames(f)
        val contentKey = ReceiptsCrypto.cipherForDecrypt(wrappedIv).doFinal(wrappedCt)
        try {
            val pt = Crypto.aesGcmDecrypt(contentKey, contentCt)
            val text = String(pt, Charsets.UTF_8)
            Crypto.wipe(pt)
            return parse(text)
        } finally {
            Crypto.wipe(contentKey)
        }
    }

    fun markClaimed(ctx: Context, id: String) {
        val updated = load(ctx).map { if (it.id == id) it.copy(claimed = true) else it }
        writeAll(ctx, updated)
        updateUnclaimedCounter(ctx, updated)
    }

    fun delete(ctx: Context, id: String) {
        val updated = load(ctx).filter { it.id != id }
        writeAll(ctx, updated)
        updateUnclaimedCounter(ctx, updated)
    }

    /** Full app reset only — NOT called on vault reset (receipts survive that). */
    fun deleteAll(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
        ReceiptsCrypto.deleteKey()
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(K_UNCLAIMED).apply()
    }

    /**
     * Cheap unclaimed count read from the plaintext SharedPreferences counter —
     * a count is not a secret. Lets [MainActivity]/the ledger badge without a
     * decrypt. Falls back to 0 when never written.
     */
    fun unclaimedCount(ctx: Context): Int =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(K_UNCLAIMED, 0)

    // -- internals -----------------------------------------------------------

    private fun evict(all: List<Receipt>): List<Receipt> {
        if (all.size <= MAX_RECEIPTS) return all
        // Drop oldest claimed first, then oldest overall, until at cap.
        val sortedClaimedOldest = all.sortedWith(
            compareByDescending<Receipt> { it.claimed }.thenBy { it.createdAt },
        )
        // sortedClaimedOldest lists claimed-oldest first; drop from the front.
        val overflow = all.size - MAX_RECEIPTS
        val toDrop = sortedClaimedOldest.take(overflow).map { it.id }.toHashSet()
        return all.filter { it.id !in toDrop }
    }

    private fun parse(text: String): List<Receipt> {
        val o = JSONObject(text)
        val arr = o.optJSONArray("receipts") ?: JSONArray()
        val out = mutableListOf<Receipt>()
        for (i in 0 until arr.length()) out.add(Receipt.fromJson(arr.getJSONObject(i)))
        return out
    }

    private fun serialize(receipts: List<Receipt>): String {
        val arr = JSONArray()
        for (r in receipts) arr.put(r.toJson())
        return JSONObject().apply {
            put("version", 1)
            put("receipts", arr)
        }.toString()
    }

    private fun updateUnclaimedCounter(ctx: Context, receipts: List<Receipt>) {
        val n = receipts.count { !it.claimed }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(K_UNCLAIMED, n).apply()
    }

    private fun writeAll(ctx: Context, receipts: List<Receipt>) {
        val contentKey = Crypto.randomBytes(Crypto.KEK_BYTES)
        try {
            val enc = ReceiptsCrypto.cipherForEncrypt()
            val wrappedCt = enc.doFinal(contentKey)
            val wrappedIv = enc.iv

            val plaintext = serialize(receipts).toByteArray(Charsets.UTF_8)
            val contentCt = Crypto.aesGcmEncrypt(contentKey, plaintext)
            Crypto.wipe(plaintext)

            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.outputStream().use { out ->
                out.write(byteArrayOf(VERSION))
                out.write(intBE(wrappedIv.size)); out.write(wrappedIv)
                out.write(intBE(wrappedCt.size)); out.write(wrappedCt)
                out.write(intBE(contentCt.size)); out.write(contentCt)
            }
            // Reuse the vault's atomic-replace so a process kill mid-write can't
            // corrupt an existing receipts file.
            Vault.atomicReplace(tmp, File(ctx.filesDir, FILE))
        } finally {
            Crypto.wipe(contentKey)
        }
    }

    private data class Frames(val wrappedIv: ByteArray, val wrappedCt: ByteArray, val contentCt: ByteArray)

    private fun readFrames(f: File): Frames {
        f.inputStream().use { input ->
            val v = input.read()
            require(v == VERSION.toInt()) { "expected receipts v1, got $v" }
            val ivLen = readIntBE(input)
            require(ivLen in 1..MAX_IV_LEN) { "receipts iv length out of range: $ivLen" }
            val iv = ByteArray(ivLen); readFully(input, iv)
            val wkLen = readIntBE(input)
            require(wkLen in 1..MAX_WRAPPED_KEY_LEN) { "receipts wrapped-key length out of range: $wkLen" }
            val wk = ByteArray(wkLen); readFully(input, wk)
            val cLen = readIntBE(input)
            require(cLen in 1..MAX_CONTENT_LEN) { "receipts content length out of range: $cLen" }
            val c = ByteArray(cLen); readFully(input, c)
            require(input.read() == -1) { "trailing bytes after receipts content" }
            return Frames(iv, wk, c)
        }
    }

    private fun intBE(n: Int): ByteArray = byteArrayOf(
        ((n ushr 24) and 0xFF).toByte(),
        ((n ushr 16) and 0xFF).toByte(),
        ((n ushr 8) and 0xFF).toByte(),
        (n and 0xFF).toByte(),
    )

    private fun readIntBE(input: java.io.InputStream): Int {
        val b = ByteArray(4); readFully(input, b)
        return ((b[0].toInt() and 0xFF) shl 24) or
            ((b[1].toInt() and 0xFF) shl 16) or
            ((b[2].toInt() and 0xFF) shl 8) or
            (b[3].toInt() and 0xFF)
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            require(n >= 0) { "short read" }
            off += n
        }
    }
}
