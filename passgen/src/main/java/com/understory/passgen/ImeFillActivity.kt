package com.understory.passgen

import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.understory.security.Crypto
import com.understory.security.Diagnostics
import com.understory.security.SecureButton
import com.understory.security.Tamper
import com.understory.security.secureClickable
import com.understory.security.ui.theme.UnderstoryAccent
import com.understory.security.ui.theme.UnderstoryTheme

/**
 * Transparent trampoline that lets the IME type a SAVED entry (design §6.1).
 *
 * An IME service cannot host a [BiometricPrompt]/[FragmentActivity], so the
 * "Type a saved entry" button launches this translucent, FLAG_SECURE,
 * excluded-from-recents activity. It:
 *   1. runs the vault unlock ([Crypto.deviceAuthCipherForDecrypt] + [Vault.unlockV2]),
 *   2. shows a picker (titles + usernames ONLY, never the password), filtered by
 *      the requesting app package passed in as an extra,
 *   3. on pick, stashes the chosen password in
 *      [PassgenInputMethodService.pendingFill] and finishes — the IME reads and
 *      commits it on its next view attach. Never the clipboard, never a lingering
 *      Intent extra.
 *
 * The master-KEK entry is filtered out of the pickable list (§10.1) so it can't
 * be typed into a field.
 */
class ImeFillActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            onCreateImpl()
        } catch (t: Throwable) {
            Diagnostics.error("passgen.ImeFill", "onCreate threw: ${t.javaClass.simpleName}: ${t.message}")
            finish()
        }
    }

    private fun onCreateImpl() {
        // Same hard refusals as the other autofill entry points.
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) { finish(); return }
        if (packageName != "com.understory.passgen") { finish(); return }
        if (Tamper.check(applicationContext).hardFail) { finish(); return }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        if (!Vault.exists(applicationContext)) {
            // No ledger yet — nothing to type. Bail cleanly.
            finish(); return
        }

        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE).orEmpty()

        setContent {
            UnderstoryTheme(accent = UnderstoryAccent.PASSGEN) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ImeFillRoot(
                        targetPackage = targetPackage,
                        onPicked = { entry ->
                            // Hand the value back to the IME via the short-lived
                            // static slot, then finish. The IME commits + wipes.
                            PassgenInputMethodService.pendingFill = entry.password
                            finish()
                        },
                        onCancelled = { finish() },
                    )
                }
            }
        }
    }

    @Composable
    private fun ImeFillRoot(
        targetPackage: String,
        onPicked: (VaultEntry) -> Unit,
        onCancelled: () -> Unit,
    ) {
        var state by remember { mutableStateOf<Gate>(Gate.Authenticating) }
        when (val s = state) {
            Gate.Authenticating -> AuthScreen(
                onUnlocked = { state = Gate.Picking(it) },
                onError = { state = Gate.Error(it) },
                onCancel = onCancelled,
            )
            is Gate.Picking -> PickerScreen(
                vault = s.vault,
                targetPackage = targetPackage,
                onPick = { entry ->
                    // Capture the value for the IME, then lock the vault KEK.
                    onPicked(entry)
                    s.vault.lock()
                },
                onCancel = { s.vault.lock(); onCancelled() },
            )
            is Gate.Error -> ErrorScreen(
                message = s.message,
                onRetry = { state = Gate.Authenticating },
                onCancel = onCancelled,
            )
        }
    }

    private sealed class Gate {
        data object Authenticating : Gate()
        data class Picking(val vault: UnlockedVault) : Gate()
        data class Error(val message: String) : Gate()
    }

    @Composable
    private fun AuthScreen(
        onUnlocked: (UnlockedVault) -> Unit,
        onError: (String) -> Unit,
        onCancel: () -> Unit,
    ) {
        var triggered by remember { mutableStateOf(false) }
        val activity = this

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("passgen — type a saved entry", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
            Text(
                "Authenticate to unlock your ledger and pick which saved entry to " +
                    "type. The password itself never reaches this screen — it goes " +
                    "straight into the field via the keyboard.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }

        if (!triggered) {
            triggered = true
            runCatching {
                val iv = Vault.ivForUnlock(activity)
                val cipher = Crypto.deviceAuthCipherForDecrypt(iv)
                val executor = ContextCompat.getMainExecutor(activity)
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val c = result.cryptoObject?.cipher
                        if (c == null) {
                            onError("Cipher missing in BiometricPrompt result.")
                            return
                        }
                        runCatching { Vault.unlockV2(activity, c) }
                            .onSuccess(onUnlocked)
                            .onFailure { onError("Vault decrypt failed: ${it.message}") }
                    }
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                            errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                            errorCode == BiometricPrompt.ERROR_CANCELED
                        ) {
                            onCancel()
                        } else {
                            onError(errString.toString())
                        }
                    }
                }
                val prompt = BiometricPrompt(activity, executor, callback)
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock ledger to type")
                    .setSubtitle("passgen — saved entry")
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                    .build()
                prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
            }.onFailure {
                onError("Crypto init failed: ${it.message}")
            }
        }
    }

    @Composable
    private fun ErrorScreen(message: String, onRetry: () -> Unit, onCancel: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("passgen — type a saved entry", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    @Composable
    private fun PickerScreen(
        vault: UnlockedVault,
        targetPackage: String,
        onPick: (VaultEntry) -> Unit,
        onCancel: () -> Unit,
    ) {
        // Master entry is never pickable (§10.1).
        val all = remember(vault.contents.entries.size) {
            vault.contents.entries.filter { it.title != Vault.MASTER_ENTRY_TITLE }
        }
        val matched = remember(targetPackage, all.size) {
            if (targetPackage.isEmpty()) emptyList()
            else all.filter { it.url.lowercase().contains(targetPackage.lowercase()) }
        }
        val rest = remember(matched, all.size) {
            (all - matched.toSet()).sortedBy { it.title.lowercase() }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("passgen — type a saved entry", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
            if (targetPackage.isNotEmpty()) {
                Text("App: $targetPackage", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }

            if (all.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(20.dp),
                ) {
                    Text(
                        "Your ledger is empty. Use Generate & insert instead, or add entries in the app.",
                        color = UnderstoryTheme.semantic.dim, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    if (matched.isNotEmpty()) {
                        item(key = "h-matched") { Text("Matches", color = UnderstoryTheme.semantic.success, style = MaterialTheme.typography.bodyMedium) }
                        items(matched, key = { "m-${it.id}" }) { PickerRow(it, onPick) }
                    }
                    if (rest.isNotEmpty()) {
                        item(key = "h-rest") {
                            Spacer(Modifier.height(8.dp))
                            Text("All entries", color = UnderstoryTheme.semantic.dim, style = MaterialTheme.typography.bodyMedium)
                        }
                        items(rest, key = { "r-${it.id}" }) { PickerRow(it, onPick) }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SecureButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }

    @Composable
    private fun PickerRow(entry: VaultEntry, onPick: (VaultEntry) -> Unit) {
        // §10.5: secure semantics on a row that releases a credential.
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                .secureClickable { onPick(entry) }
                .padding(12.dp),
        ) {
            Column {
                Text(entry.title.ifEmpty { "(untitled)" }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                if (entry.username.isNotEmpty()) {
                    Text(entry.username, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                if (entry.url.isNotEmpty()) {
                    Text(entry.url, color = UnderstoryTheme.semantic.dim, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "passgen.ime_target_package"
    }
}
