package com.understory.keyboard.plugin.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleTransformsTest {

    @Test
    fun `case transforms`() {
        assertEquals("HELLO WORLD", SampleTransforms.upper("hello World"))
        assertEquals("hello world", SampleTransforms.lower("Hello WORLD"))
        assertEquals("Hello World", SampleTransforms.title("hELLO wORLD"))
    }

    @Test
    fun `title keeps single spaces`() {
        assertEquals("A B C", SampleTransforms.title("a b c"))
    }

    @Test
    fun `suggest completes known words only`() {
        assertEquals(listOf("kotoba"), SampleTransforms.suggest("kot"))
        assertTrue(SampleTransforms.suggest("zz").isEmpty())
    }

    @Test
    fun `suggest requires two characters`() {
        assertTrue(SampleTransforms.suggest("k").isEmpty())
    }
}
