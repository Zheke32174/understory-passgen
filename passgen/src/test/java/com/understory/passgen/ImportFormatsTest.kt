package com.understory.passgen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImportFormatsTest {

    // ---------- detect ----------

    @Test
    fun detectsGoogleCsvHeader() {
        val sample = "name,url,username,password,note\nGmail,https://mail.google.com,me,p,\n"
        assertEquals(ImportFormats.Format.GOOGLE_CSV, ImportFormats.detect(sample))
    }

    @Test
    fun detectsProtonCsvHeader() {
        val sample = "type,name,url,username,password,note,totp,createTime,modifyTime,vault\n" +
            "login,Gmail,https://mail.google.com,me,p,,,,,Personal\n"
        assertEquals(ImportFormats.Format.PROTON_PASS_CSV, ImportFormats.detect(sample))
    }

    @Test
    fun detectsProtonJsonShape() {
        val sample = """{"encrypted":false,"vaults":{"v1":{"name":"P","items":[
            {"data":{"type":"login","metadata":{"name":"X"},"content":{"itemUsername":"u","password":"p","urls":["https://x"]}}}
        ]}}}"""
        assertEquals(ImportFormats.Format.PROTON_PASS_JSON, ImportFormats.detect(sample))
    }

    @Test
    fun rejectsUnknownFormat() {
        assertEquals(ImportFormats.Format.UNKNOWN, ImportFormats.detect("just some text"))
        assertEquals(ImportFormats.Format.UNKNOWN, ImportFormats.detect("{\"unrelated\":1}"))
    }

    // ---------- Google CSV ----------

    @Test
    fun googleCsvHappyPath() {
        val csv = """
name,url,username,password,note
Gmail,https://mail.google.com,me@gmail.com,hunter2,
GitHub,https://github.com,octocat,p@ss,personal account
        """.trimIndent()
        val out = ImportFormats.parseGooglePasswordsCsv(csv)
        assertEquals(2, out.size)
        assertEquals("Gmail", out[0].title)
        assertEquals("https://mail.google.com", out[0].url)
        assertEquals("me@gmail.com", out[0].username)
        assertEquals("hunter2", out[0].password)
        assertEquals("personal account", out[1].notes)
    }

    @Test
    fun googleCsvHandlesQuotedFieldsWithCommas() {
        val csv = """
name,url,username,password,note
"Acme, Inc.",https://acme.test,user,"p,assword","line1
line2"
        """.trimIndent()
        val out = ImportFormats.parseGooglePasswordsCsv(csv)
        assertEquals(1, out.size)
        assertEquals("Acme, Inc.", out[0].title)
        assertEquals("p,assword", out[0].password)
        assertTrue(out[0].notes.contains("line1"))
        assertTrue(out[0].notes.contains("line2"))
    }

    @Test
    fun googleCsvHandlesEscapedQuotes() {
        val csv = "name,url,username,password,note\n\"My \"\"app\"\"\",https://x,u,p,\n"
        val out = ImportFormats.parseGooglePasswordsCsv(csv)
        assertEquals(1, out.size)
        assertEquals("My \"app\"", out[0].title)
    }

    // ---------- Proton CSV ----------

    @Test
    fun protonCsvSkipsNonLoginRows() {
        val csv = """
type,name,url,username,password,note,totp,createTime,modifyTime,vault
login,Gmail,https://mail.google.com,me,p,,,,,Personal
note,Recipes,,,,my notes,,,,Personal
alias,Anon,,,,,,,,,Personal
login,GitHub,https://github.com,octo,p2,,,,,Personal
        """.trimIndent()
        val out = ImportFormats.parseProtonPassCsv(csv)
        assertEquals(2, out.size)
        assertEquals("Gmail", out[0].title)
        assertEquals("GitHub", out[1].title)
    }

    // ---------- Proton JSON ----------

    @Test
    fun protonJsonHappyPath() {
        val json = """
{
  "encrypted": false,
  "vaults": {
    "vault-id-1": {
      "name": "Personal",
      "items": [
        { "itemId": "i1",
          "data": {
            "type": "login",
            "metadata": { "name": "Gmail", "note": "primary" },
            "content": {
              "itemEmail": "me@gmail.com",
              "itemUsername": "",
              "password": "hunter2",
              "urls": ["https://mail.google.com"]
            }
          } },
        { "itemId": "i2",
          "data": {
            "type": "note",
            "metadata": { "name": "Recipes", "note": "stew" },
            "content": {}
          } }
      ]
    }
  }
}
        """.trimIndent()
        val out = ImportFormats.parseProtonPassJson(json)
        assertEquals(1, out.size)
        assertEquals("Gmail", out[0].title)
        assertEquals("me@gmail.com", out[0].username)
        assertEquals("hunter2", out[0].password)
        assertEquals("https://mail.google.com", out[0].url)
        assertEquals("primary", out[0].notes)
    }

    @Test
    fun protonJsonRejectsEncryptedExports() {
        val json = """{"encrypted": true, "vaults": {}}"""
        try {
            ImportFormats.parseProtonPassJson(json)
            fail("Expected encrypted-export rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("encrypted"))
        }
    }

    @Test
    fun protonJsonHandlesEmptyVaults() {
        val json = """{"encrypted": false, "vaults": {}}"""
        assertEquals(0, ImportFormats.parseProtonPassJson(json).size)
    }

    @Test
    fun protonJsonPrefersUsernameOverEmail() {
        val json = """
{"encrypted": false, "vaults": {"v":{"name":"P","items":[
  {"data":{"type":"login","metadata":{"name":"X"},
    "content":{"itemEmail":"ignored@example.com","itemUsername":"chosen","password":"p","urls":["https://x"]}}}
]}}}
        """.trimIndent()
        val out = ImportFormats.parseProtonPassJson(json)
        assertEquals("chosen", out[0].username)
    }

    // ---------- parseAuto ----------

    @Test
    fun parseAutoDispatchesByDetect() {
        val csv = "name,url,username,password,note\nA,https://a,b,c,\n"
        val out = ImportFormats.parseAuto(csv.reader())
        assertEquals(1, out.size)
        assertEquals("A", out[0].title)
    }

    @Test
    fun parseAutoThrowsOnUnknown() {
        try {
            ImportFormats.parseAuto("not a real export".reader())
            fail("Expected unknown-format rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Unrecognised"))
        }
    }

    // ---------- internal CSV ----------

    @Test
    fun csvHandlesCrlfLineEndings() {
        val csv = "a,b,c\r\n1,2,3\r\n4,5,6\r\n"
        val rows = ImportFormats.parseCsv(csv)
        assertEquals(3, rows.size)
        assertEquals(listOf("1", "2", "3"), rows[1])
    }

    @Test
    fun csvDropsEmptyTrailingRows() {
        val csv = "a,b\n1,2\n\n\n"
        val rows = ImportFormats.parseCsv(csv)
        assertEquals(2, rows.size)
    }

    @Test
    fun importedPasswordIsDistinctValueObject() {
        // Smoke: Tests that ImportedPassword equality is real (matters for
        // dedup logic in ImportScreen).
        val a = ImportFormats.ImportedPassword("t", "u", "p", "url", "n")
        val b = ImportFormats.ImportedPassword("t", "u", "p", "url", "n")
        val c = ImportFormats.ImportedPassword("t", "u", "different", "url", "n")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    // ---------- Bitwarden CSV ----------

    @Test
    fun detectsBitwardenCsvHeader() {
        val sample = "folder,favorite,type,name,notes,fields,reprompt," +
            "login_uri,login_username,login_password,login_totp\n" +
            "Personal,0,login,Gmail,,,0,https://mail.google.com,me,p,\n"
        assertEquals(ImportFormats.Format.BITWARDEN_CSV, ImportFormats.detect(sample))
    }

    @Test
    fun bitwardenCsvHappyPath_skipsNonLoginRow_handlesQuotedNotesAndMultiUri() {
        // Row 1: quoted note with an embedded comma. Row 2: a non-login (card)
        // row that must be skipped. Row 3: a login whose login_uri holds the
        // first of several URIs the exporter joined with a newline in-cell.
        val csv = "folder,favorite,type,name,notes,fields,reprompt," +
            "login_uri,login_username,login_password,login_totp\r\n" +
            "Personal,0,login,GitHub,\"personal, work\",,0,https://github.com,octocat,p@ss,\r\n" +
            "Cards,0,card,Visa,,,0,,,,\r\n" +
            "Work,1,login,Multi,,,0,\"https://a.example\nhttps://b.example\",bob,secret,\r\n"
        val out = ImportFormats.parseBitwardenCsv(csv)
        assertEquals(2, out.size)
        assertEquals("GitHub", out[0].title)
        assertEquals("personal, work", out[0].notes)
        assertEquals("octocat", out[0].username)
        assertEquals("p@ss", out[0].password)
        assertEquals("https://github.com", out[0].url)
        assertEquals("Multi", out[1].title)
        assertTrue(out[1].url.startsWith("https://a.example"))
    }

    // ---------- Bitwarden JSON ----------

    @Test
    fun detectsBitwardenJsonShape() {
        val sample = """{"encrypted":false,"items":[
            {"type":1,"name":"Gmail","login":{"username":"u","password":"p","uris":[{"uri":"https://x"}]}}
        ]}"""
        assertEquals(ImportFormats.Format.BITWARDEN_JSON, ImportFormats.detect(sample))
    }

    @Test
    fun bitwardenJsonHappyPath_keepsOnlyLoginType_readsFirstUri() {
        val json = """{"encrypted":false,"items":[
            {"type":1,"name":"GitHub","notes":"n","login":{"username":"octocat","password":"p@ss",
              "uris":[{"uri":"https://github.com"},{"uri":"https://gist.github.com"}]}},
            {"type":2,"name":"A secure note","notes":"not a login"},
            {"type":1,"name":"NoUri","login":{"username":"bob","password":"s"}}
        ]}"""
        val out = ImportFormats.parseBitwardenJson(json)
        assertEquals(2, out.size)
        assertEquals("GitHub", out[0].title)
        assertEquals("octocat", out[0].username)
        assertEquals("p@ss", out[0].password)
        assertEquals("https://github.com", out[0].url)
        assertEquals("n", out[0].notes)
        assertEquals("NoUri", out[1].title)
        assertEquals("", out[1].url)
    }

    @Test
    fun bitwardenJsonEncryptedRejected() {
        val json = """{"encrypted":true,"items":[]}"""
        try {
            ImportFormats.parseBitwardenJson(json)
            fail("encrypted Bitwarden export must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("encrypted"))
        }
    }
}
