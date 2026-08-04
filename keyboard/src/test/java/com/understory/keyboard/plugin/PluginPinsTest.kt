package com.understory.keyboard.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PluginPinsTest {

    private val digestA = "aa".repeat(32)
    private val digestB = "bb".repeat(32)

    @Test
    fun `serialize parse round trip`() {
        val pins = mapOf(
            "com.example/.PluginService" to digestA,
            "org.other/.Svc" to digestB,
        )
        assertEquals(pins, PluginPins.parse(PluginPins.serialize(pins)))
    }

    @Test
    fun `parse tolerates junk lines`() {
        val parsed = PluginPins.parse("good|$digestA\n\nnodigest|\n|nokey\nnosep\n")
        assertEquals(mapOf("good" to digestA), parsed)
    }

    @Test
    fun `unknown component is not enabled`() {
        assertEquals(
            PluginPins.Trust.NOT_ENABLED,
            PluginPins.evaluate(emptyMap(), "com.example/.Svc", digestA),
        )
    }

    @Test
    fun `matching pin is trusted, case-insensitively`() {
        val pins = mapOf("k" to digestA)
        assertEquals(PluginPins.Trust.TRUSTED, PluginPins.evaluate(pins, "k", digestA))
        assertEquals(
            PluginPins.Trust.TRUSTED,
            PluginPins.evaluate(pins, "k", digestA.uppercase()),
        )
    }

    @Test
    fun `changed signature is a mismatch`() {
        assertEquals(
            PluginPins.Trust.MISMATCH,
            PluginPins.evaluate(mapOf("k" to digestA), "k", digestB),
        )
    }

    @Test
    fun `unreadable current digest is never trusted`() {
        assertEquals(
            PluginPins.Trust.MISMATCH,
            PluginPins.evaluate(mapOf("k" to digestA), "k", null),
        )
    }
}
