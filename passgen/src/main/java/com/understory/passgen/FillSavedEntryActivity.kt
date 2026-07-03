package com.understory.passgen

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.service.autofill.Dataset
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.understory.security.Crypto
import com.understory.security.Diagnostics
import com.understory.security.Tamper
import com.understory.security.secureClickable

/**
 * Saved-entry autofill flow.
 *
 * Path:
 *   1. PassgenAutofillService returns a "pick saved entry" Dataset.
 *   2. User taps it -> Android invokes this activity via the auth
 *      IntentSender on the dataset.
 *   3. Activity prompts biometric (BiometricPrompt with the device-
 *      auth cipher tied to the same Keystore key the vault uses).
 *   4. Vault unlocks. Entries filtered by web domain / requesting
 *      package (passed via intent extras).
 *   5. Compose list shows entry titles + usernames ONLY — never the
 *      password. Threat model: "screen is never secure even when
 *      device is."
 *   6. User taps an entry -> we build a Dataset with username +
 *      password values for the requested AutofillIds, return it via
 *      AutofillManager.EXTRA_AUTHENTICATION_RESULT, finish.
 *
 * The password is constructed once as an AutofillValue and dispatched
 * to the system via binder; it never lives in this activity's view
 * tree, so FLAG_SECURE on the window is a defense-in-depth, not the
 * primary guarantee.
 */
class FillSavedEntryActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            onCreateImpl(savedInstanceState)
        } catch (t: Throwable) {
            Diagnostics.error("passgen.FillSavedEntry",
                "onCreate threw: ${t.javaClass.simpleName}: ${t.message}")
            setResult(RESULT_CANCELED); finish()
        }
    }

    private fun onCreateImpl(savedInstanceState: Bundle?) {
        // Same hard refusals as GenerateAndFillActivity. If any tamper /
        // debugger / wrong-package signal trips, we cancel out and let
        // the autofill framework move on.
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            setResult(RESULT_CANCELED); finish(); return
        }
        if (packageName != "com.understory.passgen") {
            setResult(RESULT_CANCELED); finish(); return
        }
        if (Tamper.check(applicationContext).hardFail) {
            setResult(RESULT_CANCELED); finish(); return
        }

        // FLAG_SECURE + setHideOverlayWindows so screenshots / overlays
        // can't lift the entry list. The list contains usernames + URLs
        // (cleartext-by-design) but not passwords; defense-in-depth.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val passwordIds: ArrayList<AutofillId>? =
            intent.getParcelableArrayListExtra(EXTRA_AUTOFILL_PASSWORD_IDS)
        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val usernameIds: ArrayList<AutofillId>? =
            intent.getParcelableArrayListExtra(EXTRA_AUTOFILL_USERNAME_IDS)
        val webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN).orEmpty()
        val appPackage = intent.getStringExtra(EXTRA_APP_PACKAGE).orEmpty()

        if (passwordIds.isNullOrEmpty()) {
            setResult(RESULT_CANCELED); finish(); return
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
                    AutofillRoot(
                        webDomain = webDomain,
                        appPackage = appPackage,
                        onFilled = { entry ->
                            returnDataset(entry, usernameIds, passwordIds)
                        },
                        onCancelled = {
                            setResult(RESULT_CANCELED)
                            finish()
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun AutofillRoot(
        webDomain: String,
        appPackage: String,
        onFilled: (VaultEntry) -> Unit,
        onCancelled: () -> Unit,
    ) {
        // Single state machine: GateState.Authenticating renders the
        // gate copy + kicks off the BiometricPrompt; on success it
        // flips to Picking and we render EntryPickerScreen with the
        // unlocked vault. On failure / cancel we render an Error state
        // and the user can retry or back out.
        var state by remember { mutableStateOf<GateState>(GateState.Authenticating) }

        when (val s = state) {
            GateState.Authenticating -> AuthenticatingScreen(
                onUnlocked = { state = GateState.Picking(it) },
                onError = { msg -> state = GateState.Error(msg) },
                onCancel = onCancelled,
            )
            is GateState.Picking -> EntryPickerScreen(
                vault = s.vault,
                webDomain = webDomain,
                appPackage = appPackage,
                onPick = { entry ->
                    // §5.3: lock the vault as soon as we've captured the entry to
                    // fill — previously only the cancel path locked, leaving the
                    // KEK live after a successful pick.
                    onFilled(entry)
                    s.vault.lock()
                    // Don't reset state; activity finishes via returnDataset.
                },
                onCancel = {
                    s.vault.lock()
                    onCancelled()
                },
            )
            is GateState.Error -> ErrorScreen(
                message = s.message,
                onRetry = { state = GateState.Authenticating },
                onCancel = onCancelled,
            )
        }
    }

    private sealed class GateState {
        data object Authenticating : GateState()
        data class Picking(val vault: UnlockedVault) : GateState()
        data class Error(val message: String) : GateState()
    }

    @Composable
    private fun AuthenticatingScreen(
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
            Text("passgen — saved entry", color = Color(0xFFE0E0E0), fontSize = 22.sp)
            Text(
                "Authenticate to unlock the vault and pick which saved " +
                    "credential to fill. The password itself never reaches " +
                    "this screen — it goes from the unlocked vault directly " +
                    "to the requesting app's password field via the autofill " +
                    "framework.",
                color = Color(0xFF9E9E9E), fontSize = 12.sp,
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
                    .setTitle("Unlock vault to autofill")
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
    private fun ErrorScreen(
        message: String,
        onRetry: () -> Unit,
        onCancel: () -> Unit,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("passgen — saved entry", color = Color(0xFFE0E0E0), fontSize = 22.sp)
            Text(message, color = Color(0xFFEF5350), fontSize = 12.sp)
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Retry")
            }
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }

    @Composable
    private fun EntryPickerScreen(
        vault: UnlockedVault,
        webDomain: String,
        appPackage: String,
        onPick: (VaultEntry) -> Unit,
        onCancel: () -> Unit,
    ) {
        // Filter heuristic: case-insensitive substring match between the
        // requesting domain/package and each entry's url field. We lean
        // permissive — partial matches surface to the top with a small
        // visual marker; everything else is shown below so the user can
        // still pick across-account entries.
        // §10.1: the master-KEK entry is infrastructure, never a fillable
        // credential — filter it out of the pickable list.
        val all = vault.contents.entries.filter { it.title != Vault.MASTER_ENTRY_TITLE }
        val matched = remember(webDomain, appPackage, all.size) {
            all.filter { entryMatches(it, webDomain, appPackage) }
        }
        val rest = remember(matched, all.size) {
            (all - matched.toSet()).sortedBy { it.title.lowercase() }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("passgen — saved entry", color = Color(0xFFE0E0E0), fontSize = 22.sp)
            if (webDomain.isNotEmpty()) {
                Text("Web domain: $webDomain", color = Color(0xFF9E9E9E), fontSize = 11.sp)
            } else if (appPackage.isNotEmpty()) {
                Text("App: $appPackage", color = Color(0xFF9E9E9E), fontSize = 11.sp)
            }

            if (all.isEmpty()) {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(Color(0xFF141414), RoundedCornerShape(6.dp))
                        .padding(20.dp),
                ) {
                    Text(
                        "Vault is empty. Use 'passgen — generate' on the " +
                            "autofill menu to create a new password.",
                        color = Color(0xFF707070), fontSize = 12.sp,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (matched.isNotEmpty()) {
                        item(key = "header-matched") {
                            Text("Matches",
                                color = Color(0xFF66BB6A), fontSize = 12.sp)
                        }
                        items(matched, key = { "m-${it.id}" }) { entry ->
                            EntryRow(entry, onPick)
                        }
                    }
                    if (rest.isNotEmpty()) {
                        item(key = "header-rest") {
                            Spacer(Modifier.height(8.dp))
                            Text("All entries",
                                color = Color(0xFF707070), fontSize = 12.sp)
                        }
                        items(rest, key = { "r-${it.id}" }) { entry ->
                            EntryRow(entry, onPick)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }

    @Composable
    private fun EntryRow(entry: VaultEntry, onPick: (VaultEntry) -> Unit) {
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(Color(0xFF1C1C1C), RoundedCornerShape(6.dp))
                .secureClickable { onPick(entry) }
                .padding(12.dp),
        ) {
            Column {
                Text(entry.title.ifEmpty { "(untitled)" },
                    color = Color(0xFFE0E0E0), fontSize = 14.sp)
                if (entry.username.isNotEmpty()) {
                    Text(entry.username,
                        color = Color(0xFF9E9E9E), fontSize = 12.sp)
                }
                if (entry.url.isNotEmpty()) {
                    Text(entry.url,
                        color = Color(0xFF707070), fontSize = 11.sp)
                }
            }
        }
    }

    private fun returnDataset(
        entry: VaultEntry,
        usernameIds: List<AutofillId>?,
        passwordIds: List<AutofillId>,
    ) {
        try {
            val passwordValue = AutofillValue.forText(entry.password)
            val usernameValue = if (entry.username.isNotEmpty())
                AutofillValue.forText(entry.username) else null

            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "passgen — filled")
            }

            @Suppress("DEPRECATION")
            val builder = Dataset.Builder(presentation)
            for (id in passwordIds) builder.setValue(id, passwordValue, presentation)
            if (usernameValue != null && usernameIds != null) {
                for (id in usernameIds) builder.setValue(id, usernameValue, presentation)
            }
            val replyIntent = Intent().apply {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, builder.build())
            }
            setResult(RESULT_OK, replyIntent)
            Diagnostics.log("passgen.FillSavedEntry",
                "filled entry=${entry.id.take(8)} hasUsername=${usernameValue != null}")
        } catch (t: Throwable) {
            Diagnostics.error("passgen.FillSavedEntry",
                "returnDataset threw: ${t.javaClass.simpleName}: ${t.message}")
            setResult(RESULT_CANCELED)
        } finally {
            finish()
        }
    }

    companion object {
        const val EXTRA_AUTOFILL_PASSWORD_IDS = "passgen.autofill_password_ids"
        const val EXTRA_AUTOFILL_USERNAME_IDS = "passgen.autofill_username_ids"
        const val EXTRA_WEB_DOMAIN = "passgen.web_domain"
        const val EXTRA_APP_PACKAGE = "passgen.app_package"
    }
}

/**
 * Match heuristic for autofill saved-entry filtering.
 *
 *   - Web domain present: strip `www.` and compare the right-most
 *     two labels of the entry's URL host against the requesting
 *     domain. Catches "github.com" matching an entry with URL
 *     "https://github.com/login".
 *   - App package present: substring match against the entry URL.
 *     "com.example.app" matches an entry whose URL is just the
 *     package name OR contains it.
 *
 * Returns true if EITHER signal matches. The caller surfaces matched
 * entries above the rest; nothing is hidden from the picker — the
 * user can always scroll down to "All entries" and pick across-domain.
 */
private fun entryMatches(entry: VaultEntry, webDomain: String, appPackage: String): Boolean {
    if (entry.url.isEmpty()) return false
    val entryUrl = entry.url.lowercase()
    if (webDomain.isNotEmpty()) {
        val target = webDomain.lowercase().removePrefix("www.")
        if (entryUrl.contains(target)) return true
    }
    if (appPackage.isNotEmpty()) {
        if (entryUrl.contains(appPackage.lowercase())) return true
    }
    return false
}
