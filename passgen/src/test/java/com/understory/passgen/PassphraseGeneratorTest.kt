package com.understory.passgen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [PassphraseGenerator] + [EffWordlist]. Pure JVM — the wordlist is
 * a packaged java resource, so no Android machinery is needed.
 *
 * Contracts:
 *   - exactly `words` words, all drawn from the EFF list
 *   - joined with the configured separator (empty allowed)
 *   - capitalize upcases exactly the first letter of each word
 *   - includeNumber appends exactly one digit to exactly one word
 *   - invalid options reject up front
 *   - honest entropy math
 */
class PassphraseGeneratorTest {

    private val dict by lazy { EffWordlist.words.toHashSet() }

    /** Split a generated phrase back into words for structural assertions. */
    private fun split(chars: CharArray, separator: String): List<String> =
        if (separator.isEmpty()) listOf(String(chars)) else String(chars).split(separator)

    @Test
    fun wordlist_loadsCompletely() {
        assertEquals(EffWordlist.SIZE, EffWordlist.words.size)
        assertEquals("wordlist contains duplicates", EffWordlist.SIZE, dict.size)
        // EFF large list is lowercase a–z only — anything else would break the
        // capitalize contract and the "no ambiguity" property of the list.
        for (w in EffWordlist.words) {
            assertTrue("unexpected word format: $w", w.isNotEmpty() && w.all { it in 'a'..'z' })
        }
    }

    @Test
    fun producesRequestedWordCount_allFromList() {
        val opts = PassphraseGenerator.Options(words = 6, separator = "-", capitalize = false, includeNumber = false)
        repeat(50) {
            val out = PassphraseGenerator.generate(opts)
            val parts = split(out, "-")
            assertEquals(6, parts.size)
            for (p in parts) {
                assertTrue("$p not in EFF list", p in dict)
            }
        }
    }

    @Test
    fun emptySeparator_isAllowed() {
        val opts = PassphraseGenerator.Options(words = 4, separator = "", capitalize = true, includeNumber = false)
        val out = PassphraseGenerator.generate(opts)
        // With capitalize on, word boundaries are recoverable from the caps.
        assertEquals(4, out.count { it.isUpperCase() })
    }

    @Test
    fun multiCharSeparator_isUsedVerbatim() {
        val opts = PassphraseGenerator.Options(words = 3, separator = ". ", capitalize = false, includeNumber = false)
        val out = PassphraseGenerator.generate(opts)
        assertEquals(3, split(out, ". ").size)
    }

    @Test
    fun capitalize_upcasesFirstLetterOfEveryWord() {
        val opts = PassphraseGenerator.Options(words = 5, separator = "-", capitalize = true, includeNumber = false)
        repeat(20) {
            val out = PassphraseGenerator.generate(opts)
            for (p in split(out, "-")) {
                assertTrue("word not capitalized: $p", p[0].isUpperCase())
                assertTrue("rest of word altered: $p", p.drop(1).all { it in 'a'..'z' })
                assertTrue("word not from list: $p", p[0].lowercaseChar() + p.drop(1) in dict)
            }
        }
    }

    @Test
    fun includeNumber_appendsExactlyOneDigitToOneWord() {
        val opts = PassphraseGenerator.Options(words = 5, separator = "-", capitalize = false, includeNumber = true)
        repeat(50) {
            val out = PassphraseGenerator.generate(opts)
            val parts = split(out, "-")
            assertEquals(5, parts.size)
            assertEquals("exactly one digit total", 1, out.count { it.isDigit() })
            val withDigit = parts.filter { p -> p.any { it.isDigit() } }
            assertEquals(1, withDigit.size)
            val p = withDigit[0]
            // The digit is appended — last char, and the stem is a real word.
            assertTrue("digit not at word end: $p", p.last().isDigit())
            assertTrue("stem not from list: $p", p.dropLast(1) in dict)
        }
    }

    @Test
    fun numberOff_meansNoDigits() {
        val opts = PassphraseGenerator.Options(words = 6, separator = "-", capitalize = false, includeNumber = false)
        val out = PassphraseGenerator.generate(opts)
        assertTrue(out.none { it.isDigit() })
    }

    @Test
    fun rejectsInvalidOptions() {
        // Too few words to be a passphrase at all.
        assertFalse(PassphraseGenerator.Options(words = 1).isValid())
        // Beyond the 40-word cap (UI bug guard, same spirit as length 1000).
        assertFalse(PassphraseGenerator.Options(words = 41).isValid())
        // Separator longer than 3 chars.
        assertFalse(PassphraseGenerator.Options(words = 4, separator = "----").isValid())
        // Control characters in the separator (would corrupt the typed field).
        assertFalse(PassphraseGenerator.Options(words = 4, separator = "\n").isValid())

        try {
            PassphraseGenerator.generate(PassphraseGenerator.Options(words = 1))
            fail("invalid options must be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun entropyBits_isHonest() {
        // 6 words: 6 × log2(7776) ≈ 77.55 bits, no digit.
        val plain = PassphraseGenerator.Options(words = 6, includeNumber = false)
        assertEquals(6 * kotlin.math.log2(7776.0), PassphraseGenerator.entropyBits(plain), 0.01)

        // Digit adds log2(6 positions × 10 values) ≈ 5.91 bits — capitalize and
        // separator are deterministic and must add nothing.
        val withNum = PassphraseGenerator.Options(words = 6, capitalize = true, includeNumber = true)
        assertEquals(
            6 * kotlin.math.log2(7776.0) + kotlin.math.log2(60.0),
            PassphraseGenerator.entropyBits(withNum),
            0.01,
        )

        assertEquals(0.0, PassphraseGenerator.entropyBits(PassphraseGenerator.Options(words = 1)), 0.0)
    }

    @Test
    fun distinctOutputsAcrossCalls() {
        val opts = PassphraseGenerator.Options(words = 6)
        assertNotEquals(
            String(PassphraseGenerator.generate(opts)),
            String(PassphraseGenerator.generate(opts)),
        )
    }

    @Test
    fun wipeZeroesOutput() {
        val out = PassphraseGenerator.generate(PassphraseGenerator.Options(words = 4))
        PasswordGenerator.wipe(out)
        for (c in out) assertEquals(' ', c)
    }
}
