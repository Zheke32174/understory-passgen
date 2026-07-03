package com.understory.passgen

import org.json.JSONArray
import org.json.JSONObject

/**
 * Plaintext exporters for the ledger hand-off lane (design §4.2). These write
 * the user's credentials UNENCRYPTED so they can be imported into Bitwarden
 * (the incumbent that holds the autofill slot) and then deleted — the thing
 * that makes the ledger a buffer, not a roach motel (CD-3).
 *
 * Pure functions, no UI/Compose imports. The master-KEK entry is filtered from
 * every export (title != [Vault.MASTER_ENTRY_TITLE]): it is recovery metadata
 * for THIS vault on THIS device, not a credential to carry elsewhere.
 *
 * The encrypted lane is [BackupFormat]; this file is only the dangerous
 * plaintext path, gated behind an explicit warning in the Export screen (§4.3).
 */
object ExportFormats {

    private fun exportable(entries: List<VaultEntry>): List<VaultEntry> =
        entries.filter { it.title != Vault.MASTER_ENTRY_TITLE }

    /**
     * Bitwarden CSV. Header + type=login rows, RFC-4180 quoted. Matches the
     * columns Bitwarden's own importer reads back.
     */
    fun toBitwardenCsv(entries: List<VaultEntry>): String {
        val sb = StringBuilder()
        sb.append("folder,favorite,type,name,notes,fields,reprompt,")
        sb.append("login_uri,login_username,login_password,login_totp\r\n")
        for (e in exportable(entries)) {
            sb.append(q("")).append(',')          // folder
            sb.append(q("")).append(',')          // favorite
            sb.append(q("login")).append(',')     // type
            sb.append(q(e.title)).append(',')     // name
            sb.append(q(e.notes)).append(',')     // notes
            sb.append(q("")).append(',')          // fields
            sb.append(q("")).append(',')          // reprompt
            sb.append(q(e.url)).append(',')        // login_uri
            sb.append(q(e.username)).append(',')   // login_username
            sb.append(q(e.password)).append(',')   // login_password
            sb.append(q("")).append("\r\n")        // login_totp
        }
        return sb.toString()
    }

    /**
     * Bitwarden JSON ("unencrypted") export: an `items[]` array of type=1 login
     * objects. Shape mirrors what [ImportFormats.parseBitwardenJson] reads, so a
     * passgen export round-trips through passgen import and imports into
     * Bitwarden proper.
     */
    fun toBitwardenJson(entries: List<VaultEntry>): String {
        val items = JSONArray()
        for (e in exportable(entries)) {
            val uris = JSONArray()
            if (e.url.isNotEmpty()) {
                uris.put(JSONObject().apply {
                    put("match", JSONObject.NULL)
                    put("uri", e.url)
                })
            }
            val login = JSONObject().apply {
                put("username", e.username)
                put("password", e.password)
                put("uris", uris)
            }
            items.put(JSONObject().apply {
                put("type", 1)
                put("name", e.title)
                if (e.notes.isNotEmpty()) put("notes", e.notes) else put("notes", JSONObject.NULL)
                put("login", login)
            })
        }
        return JSONObject().apply {
            put("encrypted", false)
            put("items", items)
        }.toString(2)
    }

    /**
     * Generic CSV: name,url,username,password,note — round-trips with the Google
     * Password Manager format so the same file re-imports here and imports into
     * Google/Chrome.
     */
    fun toGenericCsv(entries: List<VaultEntry>): String {
        val sb = StringBuilder()
        sb.append("name,url,username,password,note\r\n")
        for (e in exportable(entries)) {
            sb.append(q(e.title)).append(',')
            sb.append(q(e.url)).append(',')
            sb.append(q(e.username)).append(',')
            sb.append(q(e.password)).append(',')
            sb.append(q(e.notes)).append("\r\n")
        }
        return sb.toString()
    }

    /** RFC-4180 field quote: wrap in quotes, double any embedded quote. */
    private fun q(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}
