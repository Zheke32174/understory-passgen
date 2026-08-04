package com.understory.keyboard

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.understory.keyboard.plugin.PluginPins
import com.understory.keyboard.plugin.PluginRegistry
import com.understory.security.ui.components.SuiteScaffold
import com.understory.security.ui.theme.UnderstoryAccent
import com.understory.security.ui.theme.UnderstoryTheme

/**
 * Setup, typing preferences, and the plugin manager — the single place
 * where plugins get enabled (trust-on-first-use pin), disabled, or
 * re-trusted after a signature change.
 */
class MainActivity : ComponentActivity() {

    private lateinit var registry: PluginRegistry
    private lateinit var settings: KeyboardSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = PluginRegistry(this)
        settings = KeyboardSettings(this)
        setContent {
            UnderstoryTheme(accent = UnderstoryAccent.BROWSER) {
                KotobaHome(registry, settings)
            }
        }
    }
}

@Composable
private fun KotobaHome(registry: PluginRegistry, settings: KeyboardSettings) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }

    // Re-check IME status and plugin list whenever the screen comes back.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val plugins = remember(refreshTick) { registry.discover() }
    val imeId = "${context.packageName}/.KeyboardService"
    val imeEnabled = remember(refreshTick) {
        Settings.Secure.getString(context.contentResolver, "enabled_input_methods")
            ?.split(':')?.any { it == imeId || it.startsWith("${context.packageName}/") } == true
    }
    val imeSelected = remember(refreshTick) {
        Settings.Secure.getString(context.contentResolver, "default_input_method")
            ?.startsWith("${context.packageName}/") == true
    }

    SuiteScaffold(title = "Kotoba Keyboard", showSuiteFooter = false) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SetupCard(
                    imeEnabled = imeEnabled,
                    imeSelected = imeSelected,
                    onOpenSettings = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    onPickKeyboard = {
                        runCatching {
                            (context.getSystemService(InputMethodManager::class.java))
                                .showInputMethodPicker()
                        }
                    },
                )
            }
            item { TypingCard(settings) }
            item {
                Text("Plugins", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Plugins are separate apps that add suggestions and quick actions " +
                        "to the keyboard. A plugin runs nothing until you enable it here. " +
                        "Enabling pins the plugin's signing certificate; if the plugin is " +
                        "ever updated with a different signature it is disabled until you " +
                        "re-trust it. Plugins are never invoked on password fields.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (plugins.isEmpty()) {
                item {
                    Card {
                        Text(
                            "No plugins installed. Install an app that provides a " +
                                "Kotoba plugin service and it will appear here.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(plugins.size) { i ->
                    PluginCard(
                        entry = plugins[i],
                        onSetEnabled = { enabled ->
                            registry.setEnabled(plugins[i], enabled)
                            refreshTick++
                        },
                    )
                }
            }
            item { AboutCard() }
        }
    }
}

@Composable
private fun SetupCard(
    imeEnabled: Boolean,
    imeSelected: Boolean,
    onOpenSettings: () -> Unit,
    onPickKeyboard: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            Text(
                if (imeEnabled) "✓ Kotoba is enabled as an input method" else "✗ Kotoba is not enabled yet",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                if (imeSelected) "✓ Kotoba is the current keyboard" else "✗ Another keyboard is currently selected",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenSettings) { Text("Enable in settings") }
                Button(onClick = onPickKeyboard) { Text("Switch keyboard") }
            }
        }
    }
}

@Composable
private fun TypingCard(settings: KeyboardSettings) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Typing", style = MaterialTheme.typography.titleMedium)
            SettingSwitch("Word suggestions", settings.suggestions) { settings.suggestions = it }
            SettingSwitch("Learn new words (stored on-device only)", settings.learning) {
                settings.learning = it
            }
            SettingSwitch("Auto-capitalize sentences", settings.autoCap) { settings.autoCap = it }
            SettingSwitch("Haptic feedback", settings.haptics) { settings.haptics = it }
            SettingSwitch("Enable plugin subsystem", settings.pluginsEnabled) {
                settings.pluginsEnabled = it
            }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = {
            checked = it
            onChange(it)
        })
    }
}

@Composable
private fun PluginCard(
    entry: PluginRegistry.PluginEntry,
    onSetEnabled: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.label, style = MaterialTheme.typography.titleSmall)
                    Text(entry.packageName, style = MaterialTheme.typography.bodySmall)
                }
                if (entry.trust != PluginPins.Trust.MISMATCH) {
                    Switch(
                        checked = entry.trust == PluginPins.Trust.TRUSTED,
                        onCheckedChange = onSetEnabled,
                        enabled = entry.apiVersion == 1 && entry.certSha256 != null,
                    )
                }
            }
            entry.certSha256?.let {
                Text(
                    "Signature: ${it.take(16)}…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (entry.apiVersion != 1) {
                Text(
                    "Incompatible plugin API version (${entry.apiVersion}) — needs 1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (entry.hasInternet) {
                Text(
                    "⚠ This plugin app holds the INTERNET permission. Text you share " +
                        "with it through actions or suggestions could leave the device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (entry.trust == PluginPins.Trust.MISMATCH) {
                Text(
                    "Signature changed since you enabled this plugin. It has been " +
                        "disabled to protect you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onSetEnabled(false) }) { Text("Keep disabled") }
                    Button(onClick = { onSetEnabled(true) }) { Text("Trust new signature") }
                }
            }
        }
    }
}

@Composable
private fun AboutCard() {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Privacy model", style = MaterialTheme.typography.titleMedium)
            Text(
                "• The keyboard process holds no network permission of any kind — " +
                    "keystrokes cannot leave the device from this app.\n" +
                    "• Password fields get a locked-down mode: no suggestions, no " +
                    "learning, no plugins, and screen capture is blocked.\n" +
                    "• The learned-words dictionary lives only in this app's private " +
                    "storage and is excluded from every backup and device transfer.\n" +
                    "• Plugins run in their own apps with their own permissions and " +
                    "only ever see the current word, a selection you transform, or " +
                    "an action you tap.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
