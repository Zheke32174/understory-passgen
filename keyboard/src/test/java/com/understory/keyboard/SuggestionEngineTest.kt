package com.understory.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionEngineTest {

    @Test
    fun `suggests seeded words by prefix`() {
        val engine = SuggestionEngine(listOf("hello", "help", "helm", "world"))
        val hits = engine.suggest("hel", 3)
        assertEquals(3, hits.size)
        assertTrue(hits.all { it.startsWith("hel") })
    }

    @Test
    fun `learned words outrank seeds`() {
        val engine = SuggestionEngine(listOf("hello", "help"))
        repeat(3) { engine.learn("helm") }
        assertEquals("helm", engine.suggest("hel", 3).first())
    }

    @Test
    fun `the typed prefix itself is never suggested`() {
        val engine = SuggestionEngine(listOf("the", "then", "there"))
        val hits = engine.suggest("the", 5)
        assertFalse("the" in hits)
        assertTrue("then" in hits)
    }

    @Test
    fun `capitalized prefix capitalizes suggestions`() {
        val engine = SuggestionEngine(listOf("hello"))
        assertEquals(listOf("Hello"), engine.suggest("Hel", 1))
    }

    @Test
    fun `junk words are not learned`() {
        val engine = SuggestionEngine(emptyList())
        engine.learn("a")            // too short
        engine.learn("ab3!")         // non-letters
        engine.learn("x".repeat(40)) // too long
        assertEquals(0, engine.size)
        assertFalse(engine.dirty)
    }

    @Test
    fun `export import round trips learned words`() {
        val a = SuggestionEngine(emptyList())
        a.learn("resonance")
        a.learn("resonance")
        val b = SuggestionEngine(emptyList())
        b.import(a.export())
        assertEquals(listOf("resonance"), b.suggest("res", 3))
    }

    @Test
    fun `import skips malformed lines`() {
        val engine = SuggestionEngine(emptyList())
        engine.import("ok 3\nbroken\nbad -1\nweird!chars 2\n 5\n")
        assertEquals(1, engine.size)
        assertEquals(listOf("ok"), engine.suggest("o", 3))
    }

    @Test
    fun `eviction keeps the dictionary bounded and keeps frequent words`() {
        val engine = SuggestionEngine(emptyList())
        repeat(10) { engine.learn("keeper") }
        for (i in 0..SuggestionEngine.MAX_WORDS + 100) {
            engine.learn("filler" + wordSuffix(i))
        }
        assertTrue(engine.size <= SuggestionEngine.MAX_WORDS)
        assertEquals(listOf("keeper"), engine.suggest("keep", 1))
    }

    /** Digit-free unique suffix ('aaa', 'aab', …) since learn() rejects digits. */
    private fun wordSuffix(i: Int): String {
        var n = i
        val sb = StringBuilder()
        repeat(3) {
            sb.append(('a' + n % 26))
            n /= 26
        }
        return sb.toString()
    }
}
