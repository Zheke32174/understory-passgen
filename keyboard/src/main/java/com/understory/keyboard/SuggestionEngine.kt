package com.understory.keyboard

/**
 * Local-only word suggestion engine: a frequency map seeded with a small
 * built-in English list, optionally growing from words the user commits.
 * Pure Kotlin, no Android dependencies, no I/O — persistence is the
 * caller's job via [export] / [import]. Nothing here ever leaves the
 * device: the keyboard process holds no network permission at all.
 */
class SuggestionEngine(seed: Collection<String> = DEFAULT_WORDS) {

    private val freq = HashMap<String, Int>()

    /** Set when [learn] or [import] changed state since the last [export]. */
    var dirty: Boolean = false
        private set

    init {
        for (w in seed) freq[w] = 1
    }

    val size: Int get() = freq.size

    /** Record a committed word. Junk (too short/long, non-word chars) is ignored. */
    fun learn(word: String) {
        val w = word.trim()
        if (w.length < MIN_LEN || w.length > MAX_LEN) return
        if (!w.all { it.isLetter() || it == '\'' }) return
        freq.merge(w.lowercase(), LEARN_BOOST) { a, b -> a + b }
        dirty = true
        if (freq.size > MAX_WORDS) evict()
    }

    /**
     * Words starting with [prefix] (case-insensitive), most frequent
     * first. The prefix itself is never suggested back. When the typed
     * prefix is capitalized the suggestions are capitalized to match.
     */
    fun suggest(prefix: String, max: Int = 3): List<String> {
        if (prefix.isEmpty() || max <= 0) return emptyList()
        val p = prefix.lowercase()
        val hits = freq.entries
            .asSequence()
            .filter { it.key.length > p.length && it.key.startsWith(p) }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(max)
            .map { it.key }
            .toList()
        return if (prefix.first().isUpperCase()) {
            hits.map { it.replaceFirstChar(Char::uppercase) }
        } else {
            hits
        }
    }

    /** Serialize learned state as "word count" lines. */
    fun export(): String {
        dirty = false
        return buildString {
            for ((w, c) in freq) {
                append(w).append(' ').append(c).append('\n')
            }
        }
    }

    /** Merge previously [export]ed state back in. Malformed lines are skipped. */
    fun import(serialized: String) {
        for (line in serialized.lineSequence()) {
            val sep = line.lastIndexOf(' ')
            if (sep <= 0) continue
            val word = line.substring(0, sep)
            val count = line.substring(sep + 1).toIntOrNull() ?: continue
            if (count <= 0 || word.length > MAX_LEN) continue
            if (!word.all { it.isLetter() || it == '\'' }) continue
            freq.merge(word.lowercase(), count) { a, b -> maxOf(a, b) }
        }
        if (freq.size > MAX_WORDS) evict()
    }

    private fun evict() {
        // Drop the least-frequent half, never evicting below the floor of 1
        // so seed words survive a purge.
        val keep = freq.entries
            .sortedByDescending { it.value }
            .take(MAX_WORDS / 2)
        val kept = HashMap<String, Int>(keep.size * 2)
        for (e in keep) kept[e.key] = e.value
        freq.clear()
        freq.putAll(kept)
        dirty = true
    }

    companion object {
        const val MIN_LEN = 2
        const val MAX_LEN = 32
        const val MAX_WORDS = 4000
        const val LEARN_BOOST = 2

        val DEFAULT_WORDS: List<String> = listOf(
            "about", "after", "again", "all", "also", "always", "and", "another",
            "answer", "any", "anything", "are", "around", "ask", "back", "because",
            "been", "before", "being", "best", "better", "between", "both", "but",
            "call", "can", "change", "check", "come", "could", "day", "did",
            "different", "does", "done", "down", "each", "email", "even", "every",
            "everything", "feel", "find", "first", "for", "from", "get", "give",
            "going", "good", "got", "great", "had", "has", "have", "hello",
            "help", "here", "home", "how", "idea", "important", "into", "just",
            "keep", "know", "last", "later", "let", "like", "little", "long",
            "look", "made", "make", "many", "maybe", "meeting", "message", "might",
            "more", "morning", "most", "much", "need", "never", "new", "next",
            "nice", "night", "not", "nothing", "now", "number", "off", "okay",
            "one", "only", "other", "our", "out", "over", "people", "phone",
            "place", "please", "point", "probably", "put", "really", "right",
            "said", "same", "say", "see", "send", "she", "should", "since",
            "some", "something", "soon", "sorry", "still", "sure", "take", "talk",
            "tell", "than", "thanks", "that", "the", "their", "them", "then",
            "there", "these", "they", "thing", "think", "this", "though", "time",
            "today", "tomorrow", "tonight", "too", "try", "two", "under", "until",
            "use", "very", "want", "was", "way", "week", "well", "were", "what",
            "when", "where", "which", "while", "who", "why", "will", "with",
            "work", "would", "yeah", "year", "yes", "yesterday", "you", "your",
        )
    }
}
