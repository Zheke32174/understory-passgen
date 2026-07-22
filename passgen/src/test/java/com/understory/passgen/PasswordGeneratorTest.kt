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
 *     each enabled pool
 *   - invalid options reject up front
 *   - distinct outputs across calls
 *   - wipe overwrites every mutable output position with the NUL character
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
        val zero = PasswordGenerator.Options(0, true, true, true, true)
        assertFalse(zero.isValid())
        try {
            PasswordGenerator.generate(zero)
            fail("length 0 must be rejected")
        } catch (_: IllegalArgumentException) {}

        val huge = PasswordGenerator.Options(1001, true, true, true, true)
        assertFalse(huge.isValid())
        try {
            PasswordGenerator.generate(huge)
            fail("length 1001 must be rejected")
        } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun rejectsNoPoolsEnabled() {
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
        for (c in out) assertTrue("char $c not in lowers pool", c in LOWERS)
    }

    @Test
    fun usesOnlyEnabledPools_digitsOnly() {
        val opts = PasswordGenerator.Options(50, lowers = false, uppers = false, digits = true, symbols = false)
        val out = PasswordGenerator.generate(opts)
        for (c in out) assertTrue("char $c not in digits pool", c in DIGITS)
    }

    @Test
    fun usesOnlyEnabledPools_symbolsOnly() {
        val opts = PasswordGenerator.Options(50, lowers = false, uppers = false, digits = false, symbols = true)
        val out = PasswordGenerator.generate(opts)
        for (c in out) assertTrue("char $c not in symbols pool", c in SYMBOLS)
    }

    @Test
    fun guaranteesAtLeastOneCharFromEachEnabledPool() {
        val opts = PasswordGenerator.Options(16, true, true, true, true)
        repeat(200) {
            val out = PasswordGenerator.generate(opts)
            assertTrue("missing lower in ${String(out)}", out.any { it in LOWERS })
            assertTrue("missing upper in ${String(out)}", out.any { it in UPPERS })
            assertTrue("missing digit in ${String(out)}", out.any { it in DIGITS })
            assertTrue("missing symbol in ${String(out)}", out.any { it in SYMBOLS })
        }
    }

    @Test
    fun guaranteeFiresOnlyWhenLengthPermits() {
        val opts = PasswordGenerator.Options(2, true, true, true, true)
        val out = PasswordGenerator.generate(opts)
        assertEquals(2, out.size)
        val union = LOWERS + UPPERS + DIGITS + SYMBOLS
        for (c in out) assertTrue("char $c outside union alphabet", c in union)
    }

    @Test
    fun distinctOutputsAcrossCalls() {
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
            assertEquals('\u0000', c)
        }
    }

    @Test
    fun lengthEqualToPoolCount_stillSatisfiesGuarantee() {
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
