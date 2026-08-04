package com.understory.passgen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Tests for [PasswordGenerator]. Pure JVM — no Android machinery.
 *
 * The generator's contracts:
 *   - output length = options.length
 *   - output uses ONLY characters from enabled pools
 *   - when length permits, output contains AT LEAST ONE character from
 *     each enabled pool (so a "must contain a digit" site never gets
 *     a digitless password)
 *   - invalid options (length 0, no pools enabled, length too large)
 *     reject up front rather than producing a degenerate password
 *   - distinct outputs across calls (RNG sanity)
 */
class PasswordGeneratorTest {

    private val LOWERS = "abcdefghijklmnopqrstuvwxyz".toSet()
    private val UPPERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toSet()
    private val DIGITS = "0123456789".toSet()
    private val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>/?~".toSet()

    @Test
    fun outputLengthMatchesRequested() {
        for (n in intArrayOf(1, 8, 16, 32, 64, 128, 256, 1000)) {
            val opts = PasswordGenerator.Options(
                length = n, lowers = true, uppers = true, digits = true, symbols = true,
            )
            assertEquals(n, PasswordGenerator.generate(opts).size)
        }
    }

    @Test
    fun rejectsLengthOutOfRange() {
        // length 0 means an empty password — degenerate, refuse.
        val zero = PasswordGenerator.Options(0, true, true, true, true)
        assertFalse(zero.isValid())
        try {
            PasswordGenerator.generate(zero)
            fail("length 0 must be rejected")
        } catch (_: IllegalArgumentException) {}

        // length 1001 means "user typed something silly into the field"
        // — the upper bound is generous, but exists so a UI bug can't
        // ask for a million chars and OOM the device.
        val huge = PasswordGenerator.Options(1001, true, true, true, true)
        assertFalse(huge.isValid())
        try {
            PasswordGenerator.generate(huge)
            fail("length 1001 must be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun rejectsNoPoolsEnabled() {
        // Every checkbox off — nothing to generate from.
        val none = PasswordGenerator.Options(8, false, false, false, false)
        assertFalse(none.isValid())
        try {
            PasswordGenerator.generate(none)
            fail("no enabled pools must be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun usesOnlyEnabledPools_lowersOnly() {
        val opts = PasswordGenerator.Options(50, lowers = true, uppers = false, digits = false, symbols = false)
        val out = PasswordGenerator.generate(opts)
        for (c in out) {
            assertTrue("char $c not in lowers pool", c in LOWERS)
        }
    }

    @Test
    fun usesOnlyEnabledPools_digitsOnly() {
        val opts = PasswordGenerator.Options(50, lowers = false, uppers = false, digits = true, symbols = false)
        val out = PasswordGenerator.generate(opts)
        for (c in out) {
            assertTrue("char $c not in digits pool", c in DIGITS)
        }
    }

    @Test
    fun usesOnlyEnabledPools_symbolsOnly() {
        val opts = PasswordGenerator.Options(50, lowers = false, uppers = false, digits = false, symbols = true)
        val out = PasswordGenerator.generate(opts)
        for (c in out) {
            assertTrue("char $c not in symbols pool", c in SYMBOLS)
        }
    }

    @Test
    fun guaranteesAtLeastOneCharFromEachEnabledPool() {
        // Generate 200 16-char passwords and verify every single one
        // contains at least one of every requested class. Single
        // counter-example here would mean a "site requires digit"
        // failure mode — small repeat lets us catch a flaky guarantee.
        val opts = PasswordGenerator.Options(16, true, true, true, true)
        repeat(200) {
            val out = PasswordGenerator.generate(opts)
            assertTrue("missing lower in ${String(out)}",
                out.any { it in LOWERS })
            assertTrue("missing upper in ${String(out)}",
                out.any { it in UPPERS })
            assertTrue("missing digit in ${String(out)}",
                out.any { it in DIGITS })
            assertTrue("missing symbol in ${String(out)}",
                out.any { it in SYMBOLS })
        }
    }

    @Test
    fun guaranteeFiresOnlyWhenLengthPermits() {
        // length=2 with 4 enabled pools — can't possibly contain one
        // of each. The class doc says "when length permits"; a length
        // shorter than the pool count must still produce a valid
        // password rather than throw.
        val opts = PasswordGenerator.Options(2, true, true, true, true)
        val out = PasswordGenerator.generate(opts)
        assertEquals(2, out.size)
        // All chars come from the union alphabet — nothing in the
        // outputs should be outside {lowers∪uppers∪digits∪symbols}.
        val union = LOWERS + UPPERS + DIGITS + SYMBOLS
        for (c in out) {
            assertTrue("char $c outside union alphabet", c in union)
        }
    }

    @Test
    fun distinctOutputsAcrossCalls() {
        // Two 32-char passwords from a 70-char alphabet have a 70^32
        // possible space — collision is vanishingly unlikely. If this
        // trips, the RNG isn't seeded.
        val opts = PasswordGenerator.Options(32, true, true, true, true)
        val a = String(PasswordGenerator.generate(opts))
        val b = String(PasswordGenerator.generate(opts))
        assertNotEquals(a, b)
    }

    @Test
    fun wipeZeroesOutput() {
        val opts = PasswordGenerator.Options(16, true, true, true, true)
        val out = PasswordGenerator.generate(opts)
        PasswordGenerator.wipe(out)
        for (c in out) {
            assertEquals(' ', c)
        }
    }

    @Test
    fun minimumCounts_areHonored() {
        // A site policy like "at least 3 digits and 2 symbols" must hold in
        // every single output, not just on average.
        val opts = PasswordGenerator.Options(
            length = 12, lowers = true, uppers = true, digits = true, symbols = true,
            minDigits = 3, minSymbols = 2,
        )
        repeat(200) {
            val out = PasswordGenerator.generate(opts)
            assertTrue("need ≥3 digits in ${String(out)}", out.count { it in DIGITS } >= 3)
            assertTrue("need ≥2 symbols in ${String(out)}", out.count { it in SYMBOLS } >= 2)
        }
    }

    @Test
    fun minimumCounts_exceedingLength_areRejected() {
        val opts = PasswordGenerator.Options(
            length = 4, lowers = true, uppers = false, digits = true, symbols = false,
            minLowers = 3, minDigits = 3,
        )
        assertFalse(opts.isValid())
        try {
            PasswordGenerator.generate(opts)
            fail("mins exceeding length must be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun minimumCounts_onDisabledClass_areIgnored() {
        // minDigits set but digits disabled: the class is off, the minimum is
        // moot — must not invalidate the recipe or smuggle digits in.
        val opts = PasswordGenerator.Options(
            length = 10, lowers = true, uppers = false, digits = false, symbols = false,
            minDigits = 5,
        )
        assertTrue(opts.isValid())
        val out = PasswordGenerator.generate(opts)
        assertTrue(out.none { it in DIGITS })
    }

    @Test
    fun avoidAmbiguous_dropsAmbiguousCharacters() {
        val opts = PasswordGenerator.Options(
            length = 200, lowers = true, uppers = true, digits = true, symbols = true,
            avoidAmbiguous = true,
        )
        repeat(20) {
            val out = PasswordGenerator.generate(opts)
            for (c in out) {
                assertFalse("ambiguous char $c present", c in PasswordGenerator.AMBIGUOUS.toSet())
            }
        }
    }

    @Test
    fun excludeList_dropsRequestedCharacters() {
        // A site that rejects certain characters gets a compliant password.
        val banned = "aeiouAEIOU$@"
        val opts = PasswordGenerator.Options(
            length = 200, lowers = true, uppers = true, digits = true, symbols = true,
            exclude = banned,
        )
        val out = PasswordGenerator.generate(opts)
        for (c in out) {
            assertFalse("excluded char $c present", c in banned.toSet())
        }
    }

    @Test
    fun excludeList_thatEmptiesAPool_isRejected() {
        // "include digits" + "exclude all ten digits" is contradictory intent.
        val opts = PasswordGenerator.Options(
            length = 10, lowers = true, uppers = false, digits = true, symbols = false,
            exclude = "0123456789",
        )
        assertFalse(opts.isValid())
    }

    @Test
    fun requiredCharacters_areNotBiasedToTheFront() {
        // Quota-placed characters must be shuffled through the whole output.
        // 10 required digits in a 20-char password: if the shuffle were
        // missing they'd always occupy the first half.
        val opts = PasswordGenerator.Options(
            length = 20, lowers = true, uppers = false, digits = true, symbols = false,
            minDigits = 10,
        )
        var digitSeenInSecondHalf = false
        repeat(50) {
            val out = PasswordGenerator.generate(opts)
            if (out.drop(10).any { it in DIGITS }) digitSeenInSecondHalf = true
        }
        assertTrue("digits never left the front half — shuffle broken", digitSeenInSecondHalf)
    }

    @Test
    fun entropyBits_matchesAlphabetMath() {
        // 24 chars over all four pools (26+26+10+27 = 89 chars):
        // 24 × log2(89) ≈ 155.4 bits.
        val all = PasswordGenerator.Options(24, true, true, true, true)
        assertEquals(24 * kotlin.math.log2(89.0), PasswordGenerator.entropyBits(all), 0.01)

        // Digits-only PIN of 6: 6 × log2(10) ≈ 19.9 bits.
        val pin = PasswordGenerator.Options(6, false, false, true, false)
        assertEquals(6 * kotlin.math.log2(10.0), PasswordGenerator.entropyBits(pin), 0.01)

        // Invalid shape scores zero, never a flattering number.
        val invalid = PasswordGenerator.Options(0, true, true, true, true)
        assertEquals(0.0, PasswordGenerator.entropyBits(invalid), 0.0)
    }

    @Test
    fun lengthEqualToPoolCount_stillSatisfiesGuarantee() {
        // Edge: length exactly equals the number of enabled pools (4).
        // The guarantee path ("place one from each pool first") fills
        // every slot. There's nothing left for the random-fill phase.
        val opts = PasswordGenerator.Options(4, true, true, true, true)
        repeat(50) {
            val out = PasswordGenerator.generate(opts)
            assertEquals(4, out.size)
            assertTrue(out.any { it in LOWERS })
            assertTrue(out.any { it in UPPERS })
            assertTrue(out.any { it in DIGITS })
            assertTrue(out.any { it in SYMBOLS })
        }
    }
}
