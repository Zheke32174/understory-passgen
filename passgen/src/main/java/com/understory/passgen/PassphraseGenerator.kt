package com.understory.passgen

import java.security.SecureRandom
import kotlin.math.log2

/**
 * Diceware-style passphrase generator over [EffWordlist]. Returns a CharArray
 * (assembled without ever constructing a String of the full phrase) so the
 * caller can wipe it after use, matching [PasswordGenerator]'s contract.
 *
 * Contracts:
 *   - exactly `words` words drawn uniformly (SecureRandom) from the wordlist
 *   - words joined by `separator` (may be empty)
 *   - `capitalize` upcases the first letter of every word
 *   - `includeNumber` appends one digit 0–9 to one uniformly-chosen word
 *   - invalid options reject up front
 */
object PassphraseGenerator {

    private val rng = SecureRandom()

    data class Options(
        val words: Int,
        val separator: String = "-",
        val capitalize: Boolean = true,
        val includeNumber: Boolean = true,
    ) {
        fun isValid(): Boolean =
            words in 2..40 &&
                separator.length <= 3 &&
                separator.none { it.isISOControl() }
    }

    fun generate(opts: Options, wordlist: List<String> = EffWordlist.words): CharArray {
        require(opts.isValid()) { "invalid options" }
        require(wordlist.size >= 2) { "wordlist too small" }

        val picked = Array(opts.words) { wordlist[rng.nextInt(wordlist.size)] }
        val numberAt = if (opts.includeNumber) rng.nextInt(opts.words) else -1
        val digit = if (opts.includeNumber) '0' + rng.nextInt(10) else ' '

        var size = picked.sumOf { it.length } + opts.separator.length * (opts.words - 1)
        if (opts.includeNumber) size += 1

        val out = CharArray(size)
        var idx = 0
        for (w in picked.indices) {
            val word = picked[w]
            for (c in word.indices) {
                val ch = word[c]
                out[idx++] = if (opts.capitalize && c == 0) ch.uppercaseChar() else ch
            }
            if (w == numberAt) out[idx++] = digit
            if (w != picked.lastIndex) {
                for (s in opts.separator) out[idx++] = s
            }
        }
        return out
    }

    /**
     * Exact entropy of the configured shape: words × log2(wordlist size), plus
     * log2(words × 10) when a digit is injected (uniform choice of position and
     * value). Capitalization and separator are fixed by the options, so they
     * contribute no entropy — the meter never flatters the user.
     */
    fun entropyBits(opts: Options, wordlistSize: Int = EffWordlist.SIZE): Double {
        if (!opts.isValid()) return 0.0
        var bits = opts.words * log2(wordlistSize.toDouble())
        if (opts.includeNumber) bits += log2(opts.words * 10.0)
        return bits
    }
}
