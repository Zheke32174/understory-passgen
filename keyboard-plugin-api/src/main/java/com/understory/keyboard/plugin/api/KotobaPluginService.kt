package com.understory.keyboard.plugin.api

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Convenience base class for Kotoba keyboard plugins. Extend it, override
 * [describe] (and whichever of [onGetSuggestions] / [onPerformAction] your
 * capabilities need), and declare the service in your manifest:
 *
 * ```xml
 * <service
 *     android:name=".MyPluginService"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="com.understory.keyboard.plugin.SERVICE" />
 *     </intent-filter>
 *     <meta-data
 *         android:name="com.understory.keyboard.plugin.API_VERSION"
 *         android:value="1" />
 * </service>
 * ```
 *
 * Plugin callbacks run on a binder thread, must return quickly (the
 * keyboard applies a short timeout budget per call), and must never
 * throw — this base class swallows exceptions into empty results so a
 * buggy plugin degrades instead of crashing the IPC.
 */
abstract class KotobaPluginService : Service() {

    private val binder = object : IKotobaPlugin.Stub() {
        override fun describe(): PluginManifest =
            this@KotobaPluginService.describe()

        override fun getSuggestions(
            composing: String?,
            beforeCursor: String?,
            ctx: FieldContext?,
        ): MutableList<String> = runCatching {
            onGetSuggestions(composing.orEmpty(), beforeCursor.orEmpty(), ctx)
        }.getOrDefault(emptyList()).toMutableList()

        override fun performAction(
            actionId: String?,
            selectedText: String?,
            ctx: FieldContext?,
        ): String? = runCatching {
            onPerformAction(actionId.orEmpty(), selectedText.orEmpty(), ctx)
        }.getOrNull()
    }

    final override fun onBind(intent: Intent?): IBinder = binder

    /** Static self-description; called after the user enables the plugin. */
    abstract fun describe(): PluginManifest

    /** Suggestion candidates for the word being composed. */
    open fun onGetSuggestions(
        composing: String,
        beforeCursor: String,
        ctx: FieldContext?,
    ): List<String> = emptyList()

    /** Handle a quick action; see [IKotobaPlugin.performAction]. */
    open fun onPerformAction(
        actionId: String,
        selectedText: String,
        ctx: FieldContext?,
    ): String? = null
}
