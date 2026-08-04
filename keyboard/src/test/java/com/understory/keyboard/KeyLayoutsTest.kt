package com.understory.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutsTest {

    private val allPages = listOf(KeyLayouts.letters, KeyLayouts.symbols, KeyLayouts.symbols2)

    @Test
    fun `every page has four rows of positively weighted keys`() {
        for (page in allPages) {
            assertEquals("page ${page.id}", 4, page.rows.size)
            for (row in page.rows) {
                assertTrue(row.keys.isNotEmpty())
                assertTrue(row.edgePadding >= 0f)
                for (key in row.keys) {
                    assertTrue("weight of ${key.code}/${key.label}", key.weight > 0f)
                }
            }
        }
    }

    @Test
    fun `letters page carries all 26 letters`() {
        val outputs = KeyLayouts.letters.rows
            .flatMap { it.keys }
            .filter { it.code == KeyCode.CHAR }
            .map { it.output }
        for (c in 'a'..'z') {
            assertTrue("missing $c", c.toString() in outputs)
        }
    }

    @Test
    fun `char keys always have output, special keys never do`() {
        for (page in allPages) {
            for (key in page.rows.flatMap { it.keys }) {
                if (key.code == KeyCode.CHAR) {
                    assertEquals(1, key.output.length)
                } else {
                    assertEquals("", key.output)
                }
            }
        }
    }

    @Test
    fun `every page can type, delete, space, enter, and leave itself`() {
        for (page in allPages) {
            val codes = page.rows.flatMap { it.keys }.map { it.code }.toSet()
            assertTrue(KeyCode.CHAR in codes)
            assertTrue(KeyCode.DELETE in codes)
            assertTrue(KeyCode.SPACE in codes)
            assertTrue(KeyCode.ENTER in codes)
            val escapeCodes = when (page.id) {
                PageId.LETTERS -> setOf(KeyCode.PAGE_SYMBOLS)
                else -> setOf(KeyCode.PAGE_LETTERS)
            }
            assertTrue("page ${page.id} has no way out", codes.intersect(escapeCodes).isNotEmpty())
        }
    }

    @Test
    fun `page lookup is total`() {
        for (id in PageId.entries) {
            assertEquals(id, KeyLayouts.page(id).id)
        }
    }
}
