package com.understory.passgen

import android.content.Context

/**
 * Persists generation preferences (mode, length, character classes, per-class
 * minimums, passphrase shape, auto-clear seconds). These are NOT secrets —
 * they describe the shape of generated passwords, not any password itself.
 * Stored in plain SharedPreferences.
 *
 * No password value is ever written here.
 */
object Settings {

    const val MODE_CHARS = "chars"
    const val MODE_WORDS = "words"

    private const val PREF = "passgen_settings"
    private const val K_MODE = "mode"
    private const val K_LEN = "length"
    private const val K_LOWERS = "lowers"
    private const val K_UPPERS = "uppers"
    private const val K_DIGITS = "digits"
    private const val K_SYMBOLS = "symbols"
    private const val K_MIN_LOWERS = "min_lowers"
    private const val K_MIN_UPPERS = "min_uppers"
    private const val K_MIN_DIGITS = "min_digits"
    private const val K_MIN_SYMBOLS = "min_symbols"
    private const val K_AVOID_AMBIGUOUS = "avoid_ambiguous"
    private const val K_EXCLUDE = "exclude_chars"
    private const val K_WORDS = "pp_words"
    private const val K_SEPARATOR = "pp_separator"
    private const val K_CAPITALIZE = "pp_capitalize"
    private const val K_INCLUDE_NUMBER = "pp_include_number"
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
        /** [MODE_CHARS] (random characters) or [MODE_WORDS] (EFF passphrase). */
        val mode: String = MODE_CHARS,
        val minLowers: Int = 0,
        val minUppers: Int = 0,
        val minDigits: Int = 0,
        val minSymbols: Int = 0,
        val avoidAmbiguous: Boolean = false,
        val excludeChars: String = "",
        val words: Int = 6,
        val separator: String = "-",
        val capitalize: Boolean = true,
        val includeNumber: Boolean = true,
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
            mode = p.getString(K_MODE, MODE_CHARS).let {
                if (it == MODE_WORDS) MODE_WORDS else MODE_CHARS
            },
            minLowers = p.getInt(K_MIN_LOWERS, 0).coerceIn(0, 9),
            minUppers = p.getInt(K_MIN_UPPERS, 0).coerceIn(0, 9),
            minDigits = p.getInt(K_MIN_DIGITS, 0).coerceIn(0, 9),
            minSymbols = p.getInt(K_MIN_SYMBOLS, 0).coerceIn(0, 9),
            avoidAmbiguous = p.getBoolean(K_AVOID_AMBIGUOUS, false),
            excludeChars = (p.getString(K_EXCLUDE, "") ?: "").take(64),
            words = p.getInt(K_WORDS, 6).coerceIn(2, 40),
            separator = (p.getString(K_SEPARATOR, "-") ?: "-").take(3),
            capitalize = p.getBoolean(K_CAPITALIZE, true),
            includeNumber = p.getBoolean(K_INCLUDE_NUMBER, true),
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
            .putString(K_MODE, s.mode)
            .putInt(K_MIN_LOWERS, s.minLowers)
            .putInt(K_MIN_UPPERS, s.minUppers)
            .putInt(K_MIN_DIGITS, s.minDigits)
            .putInt(K_MIN_SYMBOLS, s.minSymbols)
            .putBoolean(K_AVOID_AMBIGUOUS, s.avoidAmbiguous)
            .putString(K_EXCLUDE, s.excludeChars)
            .putInt(K_WORDS, s.words)
            .putString(K_SEPARATOR, s.separator)
            .putBoolean(K_CAPITALIZE, s.capitalize)
            .putBoolean(K_INCLUDE_NUMBER, s.includeNumber)
            .apply()
    }

    fun toGeneratorOptions(s: Snapshot): PasswordGenerator.Options =
        PasswordGenerator.Options(
            length = s.length,
            lowers = s.lowers,
            uppers = s.uppers,
            digits = s.digits,
            symbols = s.symbols,
            minLowers = s.minLowers,
            minUppers = s.minUppers,
            minDigits = s.minDigits,
            minSymbols = s.minSymbols,
            avoidAmbiguous = s.avoidAmbiguous,
            exclude = s.excludeChars,
        )

    fun toPassphraseOptions(s: Snapshot): PassphraseGenerator.Options =
        PassphraseGenerator.Options(
            words = s.words,
            separator = s.separator,
            capitalize = s.capitalize,
            includeNumber = s.includeNumber,
        )
}
