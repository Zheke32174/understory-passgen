package com.understory.keyboard.plugin

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.understory.keyboard.plugin.api.PluginContract
import java.security.MessageDigest

/**
 * Discovers installed Kotoba plugins and owns their enable/trust state.
 *
 * Discovery is PackageManager-only: listing a plugin never executes its
 * code. Package visibility comes from the manifest `<queries>` intent
 * (QUERY_ALL_PACKAGES is deliberately stripped), so the keyboard can see
 * exactly the packages that export a plugin service and nothing else.
 */
class PluginRegistry(private val context: Context) {

    data class PluginEntry(
        val component: ComponentName,
        val packageName: String,
        val label: String,
        /** Signing-cert SHA-256 of the plugin package, null if unreadable. */
        val certSha256: String?,
        /** Value of the API_VERSION meta-data, 0 when absent. */
        val apiVersion: Int,
        /** Whether the plugin app requests android.permission.INTERNET. */
        val hasInternet: Boolean,
        val trust: PluginPins.Trust,
    ) {
        /** Enabled, pin still matches, and speaks our contract version. */
        val usable: Boolean
            get() = trust == PluginPins.Trust.TRUSTED &&
                apiVersion == PluginContract.API_VERSION &&
                certSha256 != null
    }

    private val prefs =
        context.getSharedPreferences("kotoba_plugins", Context.MODE_PRIVATE)

    /** Every installed plugin service, discovered fresh from PackageManager. */
    fun discover(): List<PluginEntry> {
        val pm = context.packageManager
        val intent = Intent(PluginContract.ACTION_PLUGIN_SERVICE)
        val resolved = pm.queryIntentServices(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )
        val pins = pins()
        return resolved.mapNotNull { ri ->
            val si = ri.serviceInfo ?: return@mapNotNull null
            if (!si.exported) return@mapNotNull null
            val component = ComponentName(si.packageName, si.name)
            val digest = signingSha256(si.packageName)
            PluginEntry(
                component = component,
                packageName = si.packageName,
                label = runCatching { ri.loadLabel(pm).toString() }
                    .getOrDefault(si.packageName),
                certSha256 = digest,
                apiVersion = si.metaData?.getInt(PluginContract.META_API_VERSION, 0) ?: 0,
                hasInternet = requestsInternet(si.packageName),
                trust = PluginPins.evaluate(pins, component.flattenToString(), digest),
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Plugins the keyboard may actually bind and call. */
    fun usablePlugins(): List<PluginEntry> = discover().filter { it.usable }

    /**
     * Enable (pinning the CURRENT signing digest — trust-on-first-use, and
     * also how a user re-trusts after a signature change) or disable
     * (dropping the pin entirely).
     */
    fun setEnabled(entry: PluginEntry, enabled: Boolean) {
        val pins = pins().toMutableMap()
        val key = entry.component.flattenToString()
        if (enabled) {
            val digest = signingSha256(entry.packageName) ?: return
            pins[key] = digest
        } else {
            pins.remove(key)
        }
        prefs.edit().putString(KEY_PINS, PluginPins.serialize(pins)).apply()
    }

    fun pins(): Map<String, String> =
        PluginPins.parse(prefs.getString(KEY_PINS, "") ?: "")

    /** SHA-256 hex of the package's current signing certificate. */
    fun signingSha256(packageName: String): String? = runCatching {
        val pi = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(
                PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
            ),
        )
        val signer = pi.signingInfo?.apkContentsSigners?.firstOrNull() ?: return null
        MessageDigest.getInstance("SHA-256")
            .digest(signer.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private fun requestsInternet(packageName: String): Boolean = runCatching {
        val pi = context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(
                PackageManager.GET_PERMISSIONS.toLong(),
            ),
        )
        pi.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true
    }.getOrDefault(false)

    companion object {
        private const val KEY_PINS = "pins"
    }
}
