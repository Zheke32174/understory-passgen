package com.understory.keyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * User settings. Plain SharedPreferences: tiny, local-only, excluded from
 * every backup/transfer surface by the manifest's data-extraction rules.
 */
class KeyboardSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Word suggestions above the keyboard. */
    var suggestions: Boolean
        get() = prefs.getBoolean(KEY_SUGGESTIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_SUGGESTIONS, value).apply()

    /** Learn newly typed words into the local dictionary. */
    var learning: Boolean
        get() = prefs.getBoolean(KEY_LEARNING, true)
        set(value) = prefs.edit().putBoolean(KEY_LEARNING, value).apply()

    /** Auto-capitalize at sentence starts (when the field asks for it). */
    var autoCap: Boolean
        get() = prefs.getBoolean(KEY_AUTOCAP, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOCAP, value).apply()

    /** Haptic tick on key press. */
    var haptics: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** Master switch for the whole plugin subsystem. */
    var pluginsEnabled: Boolean
        get() = prefs.getBoolean(KEY_PLUGINS, true)
        set(value) = prefs.edit().putBoolean(KEY_PLUGINS, value).apply()

    /** Serialized [SuggestionEngine] learned-words state. */
    var learnedWords: String
        get() = prefs.getString(KEY_LEARNED, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LEARNED, value).apply()

    companion object {
        private const val FILE = "kotoba_settings"
        private const val KEY_SUGGESTIONS = "suggestions"
        private const val KEY_LEARNING = "learning"
        private const val KEY_AUTOCAP = "auto_cap"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_PLUGINS = "plugins_enabled"
        private const val KEY_LEARNED = "learned_words"
    }
}
