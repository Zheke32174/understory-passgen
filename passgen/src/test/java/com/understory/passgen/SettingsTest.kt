package com.understory.passgen

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [Settings] — the persisted generation preferences. Uses
 * Robolectric for SharedPreferences. Each test wipes the prefs in
 * @After so test order doesn't leak state.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun clearPrefs() {
        ctx.getSharedPreferences("passgen_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun defaults_areReasonable() {
        // First-launch defaults: 24-char password with all four pools
        // enabled, clipboard auto-clear at 30s. These shape the
        // first-impression quality of the app — pin them.
        val s = Settings.load(ctx)
        assertEquals(24, s.length)
        assertTrue(s.lowers)
        assertTrue(s.uppers)
        assertTrue(s.digits)
        assertTrue(s.symbols)
        assertTrue(s.clearOn)
        assertEquals(30, s.clearSeconds)
    }

    @Test
    fun saveLoad_roundTripsAllFields() {
        val original = Settings.Snapshot(
            length = 12,
            lowers = true,
            uppers = false,
            digits = true,
            symbols = false,
            clearOn = false,
            clearSeconds = 60,
        )
        Settings.save(ctx, original)
        val restored = Settings.load(ctx)
        assertEquals(original, restored)
    }

    @Test
    fun load_coercesLengthIntoValidRange() {
        // A SharedPreferences corruption / older-version legacy value
        // could persist a wild number. Settings.load coerces into
        // [1, 1000]. Pin both bounds.
        ctx.getSharedPreferences("passgen_settings", android.content.Context.MODE_PRIVATE)
            .edit().putInt("length", 99999).apply()
        assertEquals(1000, Settings.load(ctx).length)

        ctx.getSharedPreferences("passgen_settings", android.content.Context.MODE_PRIVATE)
            .edit().putInt("length", -5).apply()
        assertEquals(1, Settings.load(ctx).length)
    }

    @Test
    fun load_coercesClearSecondsToAtLeastOne() {
        // 0 seconds means "clear immediately on copy" — meaningless;
        // load coerces to ≥1 so the timer always has work to do.
        ctx.getSharedPreferences("passgen_settings", android.content.Context.MODE_PRIVATE)
            .edit().putInt("clear_secs", 0).apply()
        assertTrue(Settings.load(ctx).clearSeconds >= 1)
    }

    @Test
    fun toGeneratorOptions_translatesShape() {
        // The Settings → PasswordGenerator.Options path is what every
        // "Generate" tap invokes. A regression here could silently
        // disable a pool the user thought was enabled.
        val s = Settings.Snapshot(
            length = 18,
            lowers = true, uppers = false, digits = true, symbols = false,
            clearOn = false, clearSeconds = 30,
        )
        val opts = Settings.toGeneratorOptions(s)
        assertEquals(18, opts.length)
        assertTrue(opts.lowers)
        assertFalse(opts.uppers)
        assertTrue(opts.digits)
        assertFalse(opts.symbols)
    }

    @Test
    fun savedSnapshot_isVisibleToFreshLoad() {
        // Persistence sanity: write, simulate process death by
        // re-reading the same context's prefs, get the saved values
        // back. Robolectric's SharedPreferences is a real shared store
        // so this is a meaningful round-trip.
        val s = Settings.Snapshot(
            length = 7,
            lowers = false, uppers = true, digits = false, symbols = true,
            clearOn = true, clearSeconds = 5,
        )
        Settings.save(ctx, s)
        val reloaded = Settings.load(ctx)
        assertEquals(s.length, reloaded.length)
        assertEquals(s.lowers, reloaded.lowers)
        assertEquals(s.uppers, reloaded.uppers)
        assertEquals(s.digits, reloaded.digits)
        assertEquals(s.symbols, reloaded.symbols)
        assertEquals(s.clearOn, reloaded.clearOn)
        assertEquals(s.clearSeconds, reloaded.clearSeconds)
    }
}
