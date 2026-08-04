package com.understory.passgen

/**
 * The EFF large wordlist (Bonneau, 2016): 7776 words chosen for memorability,
 * unambiguous spelling, and a minimum edit distance of 3 between entries.
 * log2(7776) ≈ 12.925 bits per word. Embedded as a java resource so the same
 * loader serves the app (packaged into the APK) and pure-JVM tests — no
 * network fetch, ever, per the suite's zero-network constraint.
 *
 * Deviation from the published list: the four hyphenated entries (drop-down,
 * felt-tip, t-shirt, yo-yo) are normalized to unhyphenated forms (dropdown,
 * felttip, tshirt, yoyos — "yoyo" already exists at another index, hence the
 * plural) so that no word can ever contain a separator character. Keeps the
 * list at exactly 7776 unique lowercase a–z words; entropy is unchanged.
 */
object EffWordlist {

    const val SIZE = 7776

    val words: List<String> by lazy { load() }

    private fun load(): List<String> {
        val stream = requireNotNull(
            EffWordlist::class.java.getResourceAsStream("/wordlists/eff_large.txt"),
        ) { "eff_large.txt missing from packaged resources" }
        val out = stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }
        // A truncated or corrupted list silently weakens every passphrase —
        // refuse to run rather than degrade.
        require(out.size == SIZE) { "expected $SIZE words, got ${out.size}" }
        return out
    }
}
