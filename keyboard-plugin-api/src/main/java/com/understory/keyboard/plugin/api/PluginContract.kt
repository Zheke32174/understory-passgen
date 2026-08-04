package com.understory.keyboard.plugin.api

/**
 * Constants shared between the Kotoba keyboard and its plugins.
 *
 * A plugin is an ordinary APK that exports a Service handling
 * [ACTION_PLUGIN_SERVICE] and speaking [IKotobaPlugin] (easiest: extend
 * [KotobaPluginService]). The keyboard discovers plugins with
 * PackageManager, but a plugin runs NOTHING until the user enables it in
 * the keyboard's plugin manager — enabling records the plugin's signing
 * certificate digest (trust-on-first-use), and a later signature change
 * auto-disables the plugin until the user re-trusts it.
 */
object PluginContract {

    /** Contract version spoken by this library. */
    const val API_VERSION = 1

    /** Intent action the plugin's exported service must handle. */
    const val ACTION_PLUGIN_SERVICE = "com.understory.keyboard.plugin.SERVICE"

    /**
     * Required `<meta-data>` int on the plugin service declaring which
     * [API_VERSION] the plugin was built against. The keyboard skips
     * plugins with a missing or incompatible version.
     */
    const val META_API_VERSION = "com.understory.keyboard.plugin.API_VERSION"

    /** Capability: plugin contributes word suggestions while composing. */
    const val CAP_SUGGESTIONS = "suggestions"

    /** Capability: plugin contributes quick-action chips to the strip. */
    const val CAP_ACTIONS = "actions"

    /** [QuickAction.kind]: result text is committed at the cursor. */
    const val KIND_INSERT = "insert"

    /** [QuickAction.kind]: action rewrites the selection / current word. */
    const val KIND_TRANSFORM = "transform"
}
