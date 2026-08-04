package com.understory.keyboard.plugin.sample

/**
 * Pure text logic for the sample plugin, split from the service so it is
 * unit-testable on the JVM.
 */
object SampleTransforms {

    const val SHRUG = "¯\\_(ツ)_/¯"

    fun upper(s: String): String = s.uppercase()

    fun lower(s: String): String = s.lowercase()

    /** Capitalize the first letter of every word, lowercasing the rest. */
    fun title(s: String): String = s
        .split(' ')
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { c -> c.uppercase() }
        }

    /** Demo completions for a tiny fixed vocabulary. */
    fun suggest(prefix: String): List<String> {
        if (prefix.length < 2) return emptyList()
        val p = prefix.lowercase()
        return VOCABULARY.filter { it.length > p.length && it.startsWith(p) }.take(3)
    }

    private val VOCABULARY = listOf(
        "keyboard", "kotoba", "plugin", "sample", "suggestion",
        "transform", "understory",
    )
}
