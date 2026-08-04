package com.understory.keyboard.plugin.sample

import com.understory.keyboard.plugin.api.FieldContext
import com.understory.keyboard.plugin.api.KotobaPluginService
import com.understory.keyboard.plugin.api.PluginContract
import com.understory.keyboard.plugin.api.PluginManifest
import com.understory.keyboard.plugin.api.QuickAction

/**
 * Reference implementation of a Kotoba keyboard plugin: three case
 * transforms, a shrug insert, and demo word completions. Use it as the
 * template for real plugins — the whole contract is [describe] plus the
 * two callbacks.
 */
class SamplePluginService : KotobaPluginService() {

    override fun describe(): PluginManifest = PluginManifest(
        apiVersion = PluginContract.API_VERSION,
        pluginId = "sample-tools",
        label = "Sample Tools",
        description = "Case transforms, a shrug, and demo completions.",
        capabilities = listOf(
            PluginContract.CAP_SUGGESTIONS,
            PluginContract.CAP_ACTIONS,
        ),
        actions = listOf(
            QuickAction("shrug", SampleTransforms.SHRUG, PluginContract.KIND_INSERT),
            QuickAction("upper", "AA", PluginContract.KIND_TRANSFORM),
            QuickAction("lower", "aa", PluginContract.KIND_TRANSFORM),
            QuickAction("title", "Aa", PluginContract.KIND_TRANSFORM),
        ),
    )

    override fun onGetSuggestions(
        composing: String,
        beforeCursor: String,
        ctx: FieldContext?,
    ): List<String> = SampleTransforms.suggest(composing)

    override fun onPerformAction(
        actionId: String,
        selectedText: String,
        ctx: FieldContext?,
    ): String? = when (actionId) {
        "shrug" -> SampleTransforms.SHRUG
        "upper" -> SampleTransforms.upper(selectedText)
        "lower" -> SampleTransforms.lower(selectedText)
        "title" -> SampleTransforms.title(selectedText)
        else -> null
    }
}
