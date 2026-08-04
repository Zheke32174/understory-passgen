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
        // wipe() zeroes the buffer (NUL), it does not blank it with spaces.
        for (c in out) {
            assertEquals('\u0000', c)
        }
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
