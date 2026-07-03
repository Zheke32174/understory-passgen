package com.understory.passgen

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [VaultEntry] JSON round-trip. Robolectric is required
 * because the production code uses org.json (not Gson / kotlinx-
 * serialization) and org.json's JVM stub on bare unit tests throws
 * "Stub!" — Robolectric ships a real implementation.
 *
 * The contract that matters: every field round-trips by name, and
 * missing fields fall back to safe defaults so an older-version
 * backup can be parsed by a newer client without exploding.
 */
@RunWith(RobolectricTestRunner::class)
class VaultEntryTest {

    @Test
    fun roundTrip_preservesAllFields() {
        val original = VaultEntry(
            id = "abc-123",
            title = "Example",
            username = "alice",
            password = "p@\"ss🔑",  // exercise quoting + non-ASCII
            url = "https://example.com",
            notes = "multi\nline\tnotes",
            created = 1_700_000_000_000L,
            updated = 1_700_000_001_000L,
        )
        val json = original.toJson()
        val restored = VaultEntry.fromJson(json)
        assertEquals(original, restored)
    }

    @Test
    fun fromJson_missingFieldsGetSafeDefaults() {
        // An older backup might not contain every field; the parser
        // must fall back rather than crash. Pin the defaults the
        // production code currently uses:
        //   - id     → fresh UUID (not "")
        //   - strings → ""
        //   - timestamps → System.currentTimeMillis() at parse
        val empty = JSONObject()
        val entry = VaultEntry.fromJson(empty)

        // id falls back to a UUID — must be non-empty and
        // syntactically a UUID (8-4-4-4-12).
        assertTrue("id should be a UUID, got '${entry.id}'",
            entry.id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertEquals("", entry.title)
        assertEquals("", entry.username)
        assertEquals("", entry.password)
        assertEquals("", entry.url)
        assertEquals("", entry.notes)
        // Timestamps from System.currentTimeMillis() — must be a
        // recent millis value, not 0.
        val now = System.currentTimeMillis()
        assertTrue("created should be recent, got ${entry.created}",
            entry.created in (now - 5_000)..now)
        assertTrue("updated should be recent, got ${entry.updated}",
            entry.updated in (now - 5_000)..now)
    }

    @Test
    fun fromJson_freshUuidOnEachMissingId() {
        // Two parses of empty JSON must produce different ids — the
        // UUID is generated fresh each time, not memoized.
        val a = VaultEntry.fromJson(JSONObject())
        val b = VaultEntry.fromJson(JSONObject())
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun toJson_emitsAllFieldsAsKeys() {
        val entry = VaultEntry(
            id = "x", title = "y", username = "u", password = "p",
            url = "url", notes = "n", created = 1L, updated = 2L,
        )
        val json = entry.toJson()
        for (key in listOf("id", "title", "username", "password", "url", "notes", "created", "updated")) {
            assertTrue("missing key $key in JSON output", json.has(key))
            assertNotNull(json.get(key))
        }
    }
}
