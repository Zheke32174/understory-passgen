package com.understory.passgen

/**
 * Single dispatch point between the two generation modes. Every delivery path
 * (main UI, IME keyboard, autofill fill-activity, vault add/regenerate) calls
 * through here so a settings snapshot produces the same secret shape
 * everywhere — a passphrase user gets passphrases from the keyboard too, not
 * just from the app.
 *
 * Same contract as the underlying generators: the returned CharArray is owned
 * by the caller, who must wipe it after use.
 */
object Generate {

    fun isValid(s: Settings.Snapshot): Boolean = when (s.mode) {
        Settings.MODE_WORDS -> Settings.toPassphraseOptions(s).isValid()
        else -> Settings.toGeneratorOptions(s).isValid()
    }

    fun fromSnapshot(s: Settings.Snapshot): CharArray = when (s.mode) {
        Settings.MODE_WORDS -> PassphraseGenerator.generate(Settings.toPassphraseOptions(s))
        else -> PasswordGenerator.generate(Settings.toGeneratorOptions(s))
    }

    /** Strength of the configured shape in bits; 0.0 when the shape is invalid. */
    fun entropyBits(s: Settings.Snapshot): Double = when (s.mode) {
        Settings.MODE_WORDS -> PassphraseGenerator.entropyBits(Settings.toPassphraseOptions(s))
        else -> PasswordGenerator.entropyBits(Settings.toGeneratorOptions(s))
    }

    fun wipe(chars: CharArray) = PasswordGenerator.wipe(chars)
}
