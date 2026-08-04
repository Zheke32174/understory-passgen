package com.understory.keyboard.plugin.api

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A plugin's self-description, returned by [IKotobaPlugin.describe] after
 * the user has enabled the plugin. Everything the keyboard shows about a
 * plugin pre-enable comes from PackageManager instead, so a plugin cannot
 * run code just by being installed.
 */
@Parcelize
data class PluginManifest(
    /** [PluginContract.API_VERSION] the plugin was built against. */
    val apiVersion: Int,
    /** Stable identifier, unique within the plugin package. */
    val pluginId: String,
    /** Human-readable name shown in the plugin manager. */
    val label: String,
    /** One-or-two-sentence description shown in the plugin manager. */
    val description: String,
    /** Subset of PluginContract.CAP_* the plugin implements. */
    val capabilities: List<String>,
    /** Quick actions surfaced as chips on the keyboard's strip. */
    val actions: List<QuickAction>,
) : Parcelable

/**
 * One chip on the keyboard's plugin strip. [kind] is
 * [PluginContract.KIND_INSERT] or [PluginContract.KIND_TRANSFORM].
 */
@Parcelize
data class QuickAction(
    val id: String,
    val label: String,
    val kind: String,
) : Parcelable

/**
 * The minimal context a plugin call carries. Deliberately narrow: no text
 * around the cursor beyond what the specific call already passes, and the
 * keyboard never invokes a plugin on a password field at all.
 */
@Parcelize
data class FieldContext(
    /** Package name of the keyboard doing the calling. */
    val hostPackage: String,
    /** Package name of the app that owns the focused field. */
    val targetPackage: String,
    /** android.text.InputType TYPE_MASK_CLASS bits of the field. */
    val inputTypeClass: Int,
    /** Whether the field accepts multi-line input. */
    val multiline: Boolean,
) : Parcelable
