package com.understory.keyboard.plugin

/**
 * Trust-on-first-use pin bookkeeping for plugins, kept free of Android
 * types so the decision logic is unit-testable on the JVM.
 *
 * Enabling a plugin records its signing-certificate SHA-256. From then on
 * the plugin is only callable while its current certificate matches the
 * recorded pin; an update signed by a different key flips the plugin to
 * [Trust.MISMATCH] — visibly disabled until the user explicitly re-trusts.
 */
object PluginPins {

    enum class Trust { NOT_ENABLED, TRUSTED, MISMATCH }

    /** Parse the serialized pin store ("key|digest" lines). */
    fun parse(serialized: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in serialized.lineSequence()) {
            val sep = line.indexOf('|')
            if (sep <= 0 || sep == line.length - 1) continue
            out[line.substring(0, sep)] = line.substring(sep + 1)
        }
        return out
    }

    fun serialize(pins: Map<String, String>): String = buildString {
        for ((k, v) in pins) {
            if (k.isEmpty() || v.isEmpty() || '|' in k || '\n' in k || '\n' in v) continue
            append(k).append('|').append(v).append('\n')
        }
    }

    /**
     * Trust decision for one plugin. [currentDigest] is the plugin
     * package's present signing digest, or null when it couldn't be read —
     * an unreadable digest is never trusted.
     */
    fun evaluate(pins: Map<String, String>, key: String, currentDigest: String?): Trust {
        val pinned = pins[key] ?: return Trust.NOT_ENABLED
        if (currentDigest == null) return Trust.MISMATCH
        return if (pinned.equals(currentDigest, ignoreCase = true)) {
            Trust.TRUSTED
        } else {
            Trust.MISMATCH
        }
    }
}
