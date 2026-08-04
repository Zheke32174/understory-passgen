package com.understory.keyboard.plugin.api;

import com.understory.keyboard.plugin.api.PluginManifest;
import com.understory.keyboard.plugin.api.FieldContext;

/**
 * The Kotoba keyboard plugin contract, v1.
 *
 * Implemented by a Service in the PLUGIN app (usually by extending
 * KotobaPluginService) and called by the KEYBOARD app. The keyboard binds
 * only to plugins the user has explicitly enabled in the plugin manager,
 * and never calls a plugin while a password field is focused.
 */
interface IKotobaPlugin {
    /** Static self-description: id, label, capabilities, quick actions. */
    PluginManifest describe();

    /**
     * Suggestion candidates for the word currently being composed.
     * Only called when the manifest declares the "suggestions" capability.
     * Return an empty list when there is nothing to offer.
     */
    List<String> getSuggestions(String composing, String beforeCursor, in FieldContext ctx);

    /**
     * Run one of the quick actions declared in the manifest. For a
     * "transform" action, selectedText carries the text to transform and
     * the return value replaces it; for an "insert" action, selectedText
     * is empty and the return value is committed at the cursor. Return
     * null to do nothing.
     */
    String performAction(String actionId, String selectedText, in FieldContext ctx);
}
