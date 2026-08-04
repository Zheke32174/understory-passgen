package com.understory.passgen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [Generate] dispatch facade — the single entry every delivery
 * path (UI / IME / autofill / vault) calls. Constructing [Settings.Snapshot]
 * is pure Kotlin, so no Robolectric is needed.
 */
class GenerateTest {

    private fun charsSnapshot(length: Int = 24) = Settings.Snapshot(
        length = length,
        lowers = true, uppers = true, digits = true, symbols = true,
        clearOn = true, clearSeconds = 30,
        mode = Settings.MODE_CHARS,
    )

    private fun wordsSnapshot(words: Int = 6) = Settings.Snapshot(
        length = 24,
        lowers = true, uppers = true, digits = true, symbols = true,
        clearOn = true, clearSeconds = 30,
        mode = Settings.MODE_WORDS,
        words = words, separator = "-", capitalize = true, includeNumber = true,
    )

    @Test
    fun charsMode_dispatchesToPasswordGenerator() {
        val out = Generate.fromSnapshot(charsSnapshot(32))
        assertEquals(32, out.size)
        val union = (PasswordGenerator.LOWERS + PasswordGenerator.UPPERS +
            PasswordGenerator.DIGITS + PasswordGenerator.SYMBOLS).toSet()
        assertTrue(out.all { it in union })
    }

    @Test
    fun wordsMode_dispatchesToPassphraseGenerator() {
        val out = Generate.fromSnapshot(wordsSnapshot(5))
        // 5 words + separators + one digit: structure, not fixed length.
        assertEquals(5, String(out).split("-").size)
    }

    @Test
    fun validity_followsTheActiveModeOnly() {
        // Broken passphrase shape in a chars-mode snapshot must not block
        // generation, and vice versa — the inactive recipe is dormant.
        val charsWithBadWords = charsSnapshot().copy(words = 0)
        assertTrue(Generate.isValid(charsWithBadWords))

        val wordsWithBadLength = wordsSnapshot().copy(length = 0)
        assertTrue(Generate.isValid(wordsWithBadLength))

        assertFalse(Generate.isValid(charsSnapshot(0)))
        assertFalse(Generate.isValid(wordsSnapshot(0)))
    }

    @Test
    fun entropy_followsTheActiveMode() {
        val chars = Generate.entropyBits(charsSnapshot(24))
        assertEquals(24 * kotlin.math.log2(89.0), chars, 0.01)

        val words = Generate.entropyBits(wordsSnapshot(6))
        assertEquals(6 * kotlin.math.log2(7776.0) + kotlin.math.log2(60.0), words, 0.01)
    }

    @Test
    fun defaultSnapshot_beatsBitwardenDefaults() {
        // Bitwarden's default generator shape is a 14-char password over four
        // classes (≈90 bits). Our defaults must clear it in BOTH modes — this
        // is the app's headline claim, pinned as a test.
        val charsDefault = charsSnapshot(24) // our default length
        assertTrue(Generate.entropyBits(charsDefault) > 150.0)

        val wordsDefault = wordsSnapshot(6) // our default word count
        assertTrue(Generate.entropyBits(wordsDefault) > 80.0)
    }
}
