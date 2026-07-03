package com.understory.passgen

import android.content.Context

/**
 * Persists generation preferences (length, character classes, auto-clear seconds).
 * These are NOT secrets — they describe the shape of generated passwords, not any
 * password itself. Stored in plain SharedPreferences.
 *
 * No password value is ever written here.
 */
object Settings {

    private const val PREF = "passgen_settings"
    private const val K_LEN = "length"
    private const val K_LOWERS = "lowers"
    private const val K_UPPERS = "uppers"
    private const val K_DIGITS = "digits"
    private const val K_SYMBOLS = "symbols"
    private const val K_CLEAR_ON = "clear_on"
    private const val K_CLEAR_SECS = "clear_secs"
    private const val K_KEEP_VALUE = "keep_generated_value"

    data class Snapshot(
        val length: Int,
        val lowers: Boolean,
        val uppers: Boolean,
        val digits: Boolean,
        val symbols: Boolean,
        val clearOn: Boolean,
        val clearSeconds: Int,
        // Drives §2 receipts. Default false (privacy-preserving): a receipt still
        // records when/where/shape, but stores no password value unless armed.
        // Must be armed BEFORE generating — the value is wiped after delivery.
        val keepGeneratedValue: Boolean = false,
    )

    fun load(ctx: Context): Snapshot {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Snapshot(
            length = p.getInt(K_LEN, 24).coerceIn(1, 1000),
            lowers = p.getBoolean(K_LOWERS, true),
            uppers = p.getBoolean(K_UPPERS, true),
            digits = p.getBoolean(K_DIGITS, true),
            symbols = p.getBoolean(K_SYMBOLS, true),
            clearOn = p.getBoolean(K_CLEAR_ON, true),
            clearSeconds = p.getInt(K_CLEAR_SECS, 30).coerceAtLeast(1),
            keepGeneratedValue = p.getBoolean(K_KEEP_VALUE, false),
        )
    }

    fun save(ctx: Context, s: Snapshot) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt(K_LEN, s.length)
            .putBoolean(K_LOWERS, s.lowers)
            .putBoolean(K_UPPERS, s.uppers)
            .putBoolean(K_DIGITS, s.digits)
            .putBoolean(K_SYMBOLS, s.symbols)
            .putBoolean(K_CLEAR_ON, s.clearOn)
            .putInt(K_CLEAR_SECS, s.clearSeconds)
            .putBoolean(K_KEEP_VALUE, s.keepGeneratedValue)
            .apply()
    }

    fun toGeneratorOptions(s: Snapshot): PasswordGenerator.Options =
        PasswordGenerator.Options(
            length = s.length,
            lowers = s.lowers,
            uppers = s.uppers,
            digits = s.digits,
            symbols = s.symbols,
        )
}
