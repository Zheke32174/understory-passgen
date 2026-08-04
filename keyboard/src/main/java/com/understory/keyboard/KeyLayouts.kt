package com.understory.keyboard

/**
 * Static key-layout data. Pure Kotlin (no Android types) so layout
 * invariants are unit-testable on the JVM.
 */
enum class KeyCode { CHAR, SHIFT, DELETE, SPACE, ENTER, PAGE_LETTERS, PAGE_SYMBOLS, PAGE_SYMBOLS2 }

enum class PageId { LETTERS, SYMBOLS, SYMBOLS2 }

data class Key(
    val code: KeyCode,
    /** Text committed when tapped (CHAR keys only, lowercase form). */
    val output: String = "",
    /** Drawn label; defaults to [output]. Special keys set their own. */
    val label: String = output,
    /** Relative width within the row. */
    val weight: Float = 1f,
)

data class KeyRow(
    val keys: List<Key>,
    /** Dead space on each side of the row, in key-weight units. */
    val edgePadding: Float = 0f,
)

data class KeyPage(val id: PageId, val rows: List<KeyRow>)

object KeyLayouts {

    private fun chars(s: String): List<Key> =
        s.map { Key(KeyCode.CHAR, output = it.toString()) }

    private val shift = Key(KeyCode.SHIFT, label = "⇧", weight = 1.5f)
    private val delete = Key(KeyCode.DELETE, label = "⌫", weight = 1.5f)
    private val space = Key(KeyCode.SPACE, label = "", weight = 4f)
    private val enter = Key(KeyCode.ENTER, label = "↵", weight = 1.5f)
    private val toSymbols = Key(KeyCode.PAGE_SYMBOLS, label = "?123", weight = 1.5f)
    private val toLetters = Key(KeyCode.PAGE_LETTERS, label = "ABC", weight = 1.5f)
    private val toSymbols2 = Key(KeyCode.PAGE_SYMBOLS2, label = "=\\<", weight = 1.5f)

    val letters = KeyPage(
        PageId.LETTERS,
        listOf(
            KeyRow(chars("qwertyuiop")),
            KeyRow(chars("asdfghjkl"), edgePadding = 0.5f),
            KeyRow(listOf(shift) + chars("zxcvbnm") + listOf(delete)),
            KeyRow(
                listOf(
                    toSymbols,
                    Key(KeyCode.CHAR, output = ","),
                    space,
                    Key(KeyCode.CHAR, output = "."),
                    enter,
                ),
            ),
        ),
    )

    val symbols = KeyPage(
        PageId.SYMBOLS,
        listOf(
            KeyRow(chars("1234567890")),
            KeyRow(chars("@#$%&-+()/")),
            KeyRow(listOf(toSymbols2) + chars("*\"':;!?") + listOf(delete)),
            KeyRow(
                listOf(
                    toLetters,
                    Key(KeyCode.CHAR, output = ","),
                    space,
                    Key(KeyCode.CHAR, output = "."),
                    enter,
                ),
            ),
        ),
    )

    val symbols2 = KeyPage(
        PageId.SYMBOLS2,
        listOf(
            KeyRow(chars("~`|•√π÷×¶∆")),
            KeyRow(chars("£¢€¥^°={}\\")),
            KeyRow(
                listOf(Key(KeyCode.PAGE_SYMBOLS, label = "?123", weight = 1.5f)) +
                    chars("%©®™✓[]") +
                    listOf(delete),
            ),
            KeyRow(
                listOf(
                    toLetters,
                    Key(KeyCode.CHAR, output = "<"),
                    space,
                    Key(KeyCode.CHAR, output = ">"),
                    enter,
                ),
            ),
        ),
    )

    fun page(id: PageId): KeyPage = when (id) {
        PageId.LETTERS -> letters
        PageId.SYMBOLS -> symbols
        PageId.SYMBOLS2 -> symbols2
    }
}
