package com.understory.passgen

import org.json.JSONObject
import java.io.Reader

/**
 * File-based password vault importers.
 *
 * Three formats are supported, all parsed in pure-Kotlin with no third-party
 * deps so the import path is auditable and free of unexpected surfaces:
 *
 *   1. Google Password Manager CSV (Chrome/Android Settings → Passwords →
 *      Export). Columns: name,url,username,password,note. RFC 4180 quoting.
 *
 *   2. Proton Pass JSON (Pass desktop/web → Settings → Export →
 *      "JSON, unencrypted"). Schema:
 *        { "encrypted": false,
 *          "vaults": { "<id>": { "name": "...", "items": [
 *            { "data": { "type": "login",
 *                        "metadata": { "name": "...", "note": "..." },
 *                        "content": { "itemEmail": "...", "itemUsername": "...",
 *                                     "password": "...", "urls": [...] } } },
 *            ...
 *          ] } } }
 *      Only items whose data.type == "login" become passwords. TOTP secrets
 *      embedded as data.content.totpUri are intentionally NOT imported here —
 *      passgen is a password vault, not an authenticator. Aegis handles those.
 *
 *   3. Proton Pass CSV (Pass desktop/web → Settings → Export → "CSV").
 *      Columns: type,name,url,username,password,note,totp,createTime,
 *      modifyTime,vault. Only rows where type == "login" are imported.
 *
 * Output is a [ImportedPassword] which the calling screen materialises into
 * [VaultEntry] (assigning fresh ids + timestamps) and dedupes against the
 * existing vault. Password values never appear in any logged or surfaced
 * string in this file — passgen invariant.
 */
object ImportFormats {

    data class ImportedPassword(
        val title: String,
        val username: String,
        val password: String,
        val url: String,
        val notes: String,
    )

    /** Detect the file format from contents. Header sniffing only — never
     *  reads beyond the first few KB. */
    enum class Format { GOOGLE_CSV, PROTON_PASS_JSON, PROTON_PASS_CSV, UNKNOWN }

    fun detect(sample: String): Format {
        val trimmed = sample.trimStart()
        if (trimmed.startsWith("{")) {
            // JSON path. Match Proton Pass shape via a couple of unique tokens
            // that don't appear in unrelated JSON exports.
            if (trimmed.contains("\"vaults\"") &&
                (trimmed.contains("\"itemUsername\"") ||
                    trimmed.contains("\"itemEmail\"") ||
                    trimmed.contains("\"protonPass\""))) {
                return Format.PROTON_PASS_JSON
            }
            return Format.UNKNOWN
        }
        // CSV path: peek at the header row.
        val firstLine = trimmed.lineSequence().firstOrNull()?.trim()?.lowercase() ?: ""
        return when {
            firstLine.startsWith("name,url,username,password,note") -> Format.GOOGLE_CSV
            firstLine.startsWith("type,name,url,username,password,note,totp") -> Format.PROTON_PASS_CSV
            else -> Format.UNKNOWN
        }
    }

    /** Parse a Google Password Manager CSV export. */
    fun parseGooglePasswordsCsv(text: String): List<ImportedPassword> {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        val ix = headerIndex(header, listOf("name", "url", "username", "password", "note"))
        val out = mutableListOf<ImportedPassword>()
        for (row in rows.drop(1)) {
            if (row.all { it.isEmpty() }) continue
            out += ImportedPassword(
                title = row.getOr(ix["name"]),
                url = row.getOr(ix["url"]),
                username = row.getOr(ix["username"]),
                password = row.getOr(ix["password"]),
                notes = row.getOr(ix["note"]),
            )
        }
        return out
    }

    /** Parse a Proton Pass CSV export. Only `type=login` rows are imported. */
    fun parseProtonPassCsv(text: String): List<ImportedPassword> {
        val rows = parseCsv(text)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().map { it.trim().lowercase() }
        val ix = headerIndex(header, listOf("type", "name", "url", "username", "password", "note"))
        val out = mutableListOf<ImportedPassword>()
        for (row in rows.drop(1)) {
            if (row.all { it.isEmpty() }) continue
            val rowType = row.getOr(ix["type"]).trim().lowercase()
            if (rowType.isNotEmpty() && rowType != "login") continue
            out += ImportedPassword(
                title = row.getOr(ix["name"]),
                url = row.getOr(ix["url"]),
                username = row.getOr(ix["username"]),
                password = row.getOr(ix["password"]),
                notes = row.getOr(ix["note"]),
            )
        }
        return out
    }

    /**
     * Parse a Proton Pass unencrypted JSON export. Only entries whose
     * `data.type == "login"` produce passwords; everything else (notes,
     * cards, aliases) is ignored.
     */
    fun parseProtonPassJson(text: String): List<ImportedPassword> {
        val root = JSONObject(text)
        if (root.optBoolean("encrypted", false)) {
            throw IllegalArgumentException(
                "Proton Pass export is encrypted; export with 'JSON, unencrypted' instead."
            )
        }
        val out = mutableListOf<ImportedPassword>()
        val vaults = root.optJSONObject("vaults") ?: return emptyList()
        val keys = vaults.keys()
        while (keys.hasNext()) {
            val vault = vaults.optJSONObject(keys.next()) ?: continue
            val items = vault.optJSONArray("items") ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val data = item.optJSONObject("data") ?: continue
                if (!data.optString("type").equals("login", ignoreCase = true)) continue
                val metadata = data.optJSONObject("metadata") ?: JSONObject()
                val content = data.optJSONObject("content") ?: JSONObject()
                val urls = content.optJSONArray("urls")
                val firstUrl = if (urls != null && urls.length() > 0) urls.optString(0) else ""
                val username = content.optString("itemUsername").ifEmpty {
                    content.optString("itemEmail")
                }
                out += ImportedPassword(
                    title = metadata.optString("name"),
                    username = username,
                    password = content.optString("password"),
                    url = firstUrl,
                    notes = metadata.optString("note"),
                )
            }
        }
        return out
    }

    /**
     * Convenience entry point: detect format from the first chunk and dispatch.
     * Throws IllegalArgumentException with a human-readable reason if the
     * format isn't recognised.
     */
    fun parseAuto(reader: Reader): List<ImportedPassword> {
        val text = reader.readText()
        return when (detect(text.take(2048))) {
            Format.GOOGLE_CSV -> parseGooglePasswordsCsv(text)
            Format.PROTON_PASS_CSV -> parseProtonPassCsv(text)
            Format.PROTON_PASS_JSON -> parseProtonPassJson(text)
            Format.UNKNOWN -> throw IllegalArgumentException(
                "Unrecognised file. Expected Google Password Manager CSV " +
                    "(name,url,username,password,note), Proton Pass CSV " +
                    "(type,name,url,username,password,note,totp,...), " +
                    "or Proton Pass unencrypted JSON."
            )
        }
    }

    // ---------- private helpers ----------

    private fun headerIndex(header: List<String>, expected: List<String>): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (name in expected) {
            val idx = header.indexOf(name)
            if (idx >= 0) out[name] = idx
        }
        return out
    }

    private fun List<String>.getOr(idx: Int?): String =
        if (idx == null || idx < 0 || idx >= size) "" else this[idx]

    /**
     * Minimal RFC 4180 CSV parser. Supports:
     *   - Comma-separated fields.
     *   - Double-quoted fields containing commas, embedded newlines, and
     *     escaped quotes (`""`).
     *   - CRLF, LF, or CR line endings (CR alone is rare but legal).
     *   - Trailing empty rows ignored.
     */
    internal fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                when (ch) {
                    '"' -> {
                        if (i + 1 < text.length && text[i + 1] == '"') {
                            field.append('"'); i += 2; continue
                        }
                        inQuotes = false
                    }
                    else -> field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> if (field.isEmpty()) inQuotes = true else field.append(ch)
                    ',' -> { current.add(field.toString()); field.setLength(0) }
                    '\r' -> {
                        current.add(field.toString()); field.setLength(0)
                        rows.add(current.toList()); current.clear()
                        if (i + 1 < text.length && text[i + 1] == '\n') i++
                    }
                    '\n' -> {
                        current.add(field.toString()); field.setLength(0)
                        rows.add(current.toList()); current.clear()
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (field.isNotEmpty() || current.isNotEmpty()) {
            current.add(field.toString())
            rows.add(current.toList())
        }
        return rows.filter { it.any { f -> f.isNotEmpty() } }
    }
}
