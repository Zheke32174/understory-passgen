package com.understory.passgen

import com.understory.passgen.BuildConfig
import com.understory.security.Crypto
import com.understory.security.Diagnostics
import com.understory.security.DiagnosticsScreen
import com.understory.security.SecureButton
import com.understory.security.SecureOutlinedButton
import com.understory.security.Tamper
import com.understory.security.TestingMode
import com.understory.security.VaultRecovery
import com.understory.security.VaultRecoveryScreen
import com.understory.security.VaultResetHooks
import com.understory.security.ui.Bg
import com.understory.security.ui.components.FatalScreen
import com.understory.security.ui.theme.UnderstoryAccent
import com.understory.security.ui.theme.UnderstoryTheme

import android.app.KeyguardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher

class VaultActivity : FragmentActivity() {

    companion object {
        /**
         * Optional Intent extra: a content `Uri` for a file the user picked via
         * the system "Open with…" dialog. When present, VaultActivity routes
         * through the normal unlock flow and lands in the import screen with this
         * URI pre-loaded behind an explicit confirmation (§9); the generator
         * screen is never shown.
         */
        const val EXTRA_PENDING_IMPORT_URI = "com.understory.passgen.EXTRA_PENDING_IMPORT_URI"

        /**
         * Optional Intent extra: a start-destination hint from the launcher's
         * bottom-nav landing screens. After the normal unlock flow completes,
         * the ledger opens directly on the requested sub-stage instead of the
         * default List. Unlock is NEVER bypassed — this only chooses which
         * post-unlock stage to land on. Unknown / absent = List.
         */
        const val EXTRA_START_DESTINATION = "com.understory.passgen.EXTRA_START_DESTINATION"
        const val EXTRA_START_ADD = "add"
        const val EXTRA_START_RECEIPTS = "receipts"

        /** Display name used in reset confirmation + honest copy. */
        const val APP_NAME = "Understory Keys"
    }

    private var unlocked: UnlockedVault? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        Diagnostics.log("passgen.VaultActivity", "onCreate (savedInstanceState=${savedInstanceState != null})")
        super.onCreate(savedInstanceState)
        try {
            initialize()
        } catch (t: Throwable) {
            Diagnostics.error("passgen.VaultActivity",
                "onCreate threw: ${t.javaClass.simpleName}: ${t.message}")
            setContent {
                UnderstoryTheme(accent = UnderstoryAccent.PASSGEN) {
                    FatalScreen(
                        title = getString(R.string.title_vault_crash),
                        reason = getString(R.string.msg_vault_crash),
                        details = t.toString(),
                    )
                }
            }
        }
    }

    private fun initialize() {
        val debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        if (debuggerAttached || Tamper.check(applicationContext).hardFail) {
            finishAndRemoveTask(); return
        }

        if (!TestingMode.ALLOW_SCREENSHOTS) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.setHideOverlayWindows(true)
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setRecentsScreenshotEnabled(false)
            }
        }

        // If a stale v1 vault exists from earlier builds, wipe it. v2 is
        // device-credential-bound and can't be migrated without the v1 typed
        // master, which we explicitly don't ask for.
        if (Vault.exists(applicationContext) && !Vault.isCurrentVersion(applicationContext)) {
            Vault.delete(applicationContext)
        }

        @Suppress("DEPRECATION")
        val pendingImportUri: android.net.Uri? = intent?.getParcelableExtra(EXTRA_PENDING_IMPORT_URI)
        val startDestination: String? = intent?.getStringExtra(EXTRA_START_DESTINATION)

        setContent {
            UnderstoryTheme(accent = UnderstoryAccent.PASSGEN) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    VaultRoot(
                        activity = this,
                        unlockedRef = ::unlocked,
                        setUnlocked = { unlocked = it },
                        onClose = { finishAndRemoveTask() },
                        pendingImportUri = pendingImportUri,
                        startDestination = startDestination,
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Diagnostics.log("passgen.VaultActivity",
            "onUserLeaveHint (keepAlive=${TestingMode.KEEP_ALIVE_ON_LEAVE})")
        if (TestingMode.KEEP_ALIVE_ON_LEAVE) return
        unlocked?.lock()
        unlocked = null
        finishAndRemoveTask()
    }

    override fun onPause() {
        super.onPause()
        val keepAlive = TestingMode.KEEP_ALIVE_ON_LEAVE
        Diagnostics.log("passgen.VaultActivity",
            "onPause (changingConfigs=$isChangingConfigurations, keepAlive=$keepAlive)")
        if (!isChangingConfigurations && !keepAlive) {
            unlocked?.lock()
            unlocked = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Diagnostics.log("passgen.VaultActivity", "onDestroy")
        unlocked?.lock()
        unlocked = null
    }
}

private enum class Stage {
    Setup, Unlock, Recovery, Rebind, List, AddEntry, ViewEntry, Import, Receipts, Export, Restore, Diagnostics
}

/**
 * App glue for the shared reset flow (§3.3). passgen's reset deletes the vault
 * file + the device-auth Keystore key; RECEIPTS deliberately survive (they are
 * on [ReceiptsCrypto]'s separate, non-auth-bound key and may be the user's only
 * record of what to re-recover).
 */
private class PassgenResetHooks(
    private val goToSetup: () -> Unit,
) : VaultResetHooks {
    override fun exists(ctx: Context): Boolean = Vault.exists(ctx)

    // passgen exports via the standalone Export screen (the self-sealing
    // recovery file), not through the shared reset export-first hook (which
    // targets the common envelope). Reset runs export-less; the user is directed
    // to Export before reset. Returns null (no payload produced here).
    override fun exportPayload(unlocked: Any): ByteArray? = null

    override fun wipe(ctx: Context) {
        // Vault.delete deletes vault.bin + Crypto.deleteDeviceAuthKey().
        // Receipts are NOT touched — different key, must survive vault reset.
        Vault.delete(ctx)
    }

    override fun goToSetup() = goToSetup.invoke()
}

@Composable
private fun VaultRoot(
    activity: FragmentActivity,
    unlockedRef: () -> UnlockedVault?,
    setUnlocked: (UnlockedVault?) -> Unit,
    onClose: () -> Unit,
    pendingImportUri: android.net.Uri? = null,
    startDestination: String? = null,
) {
    val ctx = LocalContext.current
    // Where to land AFTER unlock/setup completes. A pending import always wins
    // (the user explicitly opened a file); otherwise the bottom-nav landing hint
    // chooses Add or Receipts; default List. Unlock is never bypassed — this
    // only selects the first post-unlock stage.
    val postUnlockStage: () -> Stage = {
        when {
            pendingImportUri != null -> Stage.Import
            startDestination == VaultActivity.EXTRA_START_ADD -> Stage.AddEntry
            startDestination == VaultActivity.EXTRA_START_RECEIPTS -> Stage.Receipts
            else -> Stage.List
        }
    }
    // Initial stage: if the vault file exists but the device-auth key was
    // destroyed by biometric re-enrollment / lock-screen change, the key state
    // is PERMANENTLY_INVALIDATED — route straight to Recovery instead of a
    // doomed unlock (§3.2). Otherwise Unlock (existing vault) or Setup (fresh).
    val initialStage = remember {
        when {
            !Vault.exists(ctx) -> Stage.Setup
            VaultRecovery.keyStateAtStartup(ctx, headerExists = true) ==
                VaultRecovery.VaultKeyState.PERMANENTLY_INVALIDATED -> Stage.Rebind
            else -> Stage.Unlock
        }
    }
    var stageName by rememberSaveable { mutableStateOf(initialStage.name) }
    val stage = remember(stageName) { Stage.valueOf(stageName) }
    val setStage: (Stage) -> Unit = {
        Diagnostics.log("passgen.VaultRoot", "stage transition: $stageName → ${it.name}")
        stageName = it.name
    }
    var selectedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    // Whether the Recovery screen was reached from an invalidated key (export
    // impossible) or a deliberate reset from an unlocked vault (key usable).
    var recoveryKeyUsable by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf(pendingImportUri) }
    val backToList: () -> Unit = {
        pendingImport = null
        setStage(Stage.List)
    }

    when (stage) {
        Stage.Setup -> SetupScreen(
            activity = activity,
            onCreated = { vault ->
                setUnlocked(vault)
                setStage(postUnlockStage())
            },
            onRestore = { setStage(Stage.Restore) },
            onClose = onClose,
        )
        Stage.Unlock -> UnlockScreen(
            activity = activity,
            onUnlocked = { vault ->
                setUnlocked(vault)
                setStage(postUnlockStage())
            },
            // A permanently-invalidated key routes to the silent-first re-bind
            // (sealed kit → else recovery-file import), NOT the reset flow.
            onRecovery = { setStage(Stage.Rebind) },
            onClose = onClose,
        )
        Stage.Recovery -> {
            BackHandler { onClose() }
            // The shared screen's export-first step is left to its own in-place
            // advance (onExportFirst = null): navigating to a separate Export
            // stage would recompose this screen with a fresh ResetPlan and drop
            // the continuation. The honest path is the List's "Export / hand off"
            // button BEFORE reset — the EXPLAIN copy already tells the user to
            // export one now if they haven't.
            VaultRecoveryScreen(
                keyUsable = recoveryKeyUsable,
                appName = VaultActivity.APP_NAME,
                hooks = PassgenResetHooks(goToSetup = { setStage(Stage.Setup) }),
                onExportFirst = null,
            )
        }
        Stage.Rebind -> {
            BackHandler { onClose() }
            RebindScreen(
                activity = activity,
                onRebound = { vault ->
                    setUnlocked(vault)
                    setStage(Stage.List)
                },
                // No in-vault sealed kit → fall back to importing the exported
                // recovery file (still no typing).
                onNeedRecoveryFile = { setStage(Stage.Restore) },
                onClose = onClose,
            )
        }
        Stage.List -> {
            val v = unlockedRef() ?: return run { setStage(Stage.Unlock) }
            ListScreen(
                vault = v,
                onAdd = { setStage(Stage.AddEntry) },
                onImport = { setStage(Stage.Import) },
                onExport = { setStage(Stage.Export) },
                onReceipts = { setStage(Stage.Receipts) },
                onView = { id ->
                    selectedEntryId = id
                    setStage(Stage.ViewEntry)
                },
                onLock = {
                    v.lock()
                    setUnlocked(null)
                    onClose()
                },
                onReset = { recoveryKeyUsable = true; setStage(Stage.Recovery) },
                onDiagnostics = { setStage(Stage.Diagnostics) },
            )
        }
        Stage.AddEntry -> {
            val v = unlockedRef() ?: return run { setStage(Stage.Unlock) }
            BackHandler { backToList() }
            AddEntryScreen(
                vault = v,
                onSaved = backToList,
                onCancel = backToList,
            )
        }
        Stage.ViewEntry -> {
            val v = unlockedRef() ?: return run { setStage(Stage.Unlock) }
            val id = selectedEntryId
            if (id == null) {
                setStage(Stage.List)
            } else {
                BackHandler { backToList() }
                ViewEntryScreen(
                    activity = activity,
                    vault = v,
                    entryId = id,
                    onBack = backToList,
                    onDeleted = {
                        v.contents = v.contents.copy(entries = v.contents.entries.filter { it.id != id })
                        v.save()
                        setStage(Stage.List)
                    },
                )
            }
        }
        Stage.Import -> {
            val v = unlockedRef() ?: return run { setStage(Stage.Unlock) }
            BackHandler { backToList() }
            val incoming = pendingImport
            if (incoming != null) {
                pendingImport = null
            }
            ImportScreen(
                vault = v,
                incomingUri = incoming,
                onDone = backToList,
                onCancel = backToList,
            )
        }
        Stage.Receipts -> {
            // Reachable only after unlock in this session (§5.4). If the vault
            // isn't open, bounce to unlock.
            val v = unlockedRef() ?: return run { setStage(Stage.Unlock) }
            BackHandler { backToList() }
            ReceiptsScreen(
                activity = activity,
                vault = v,
                onBack = backToList,
            )
        }
        Stage.Export -> {
            val v = unlockedRef() ?: return run { setStage(Stage.Unlock) }
            BackHandler { backToList() }
            ExportScreen(
                vault = v,
                onDone = backToList,
            )
        }
        Stage.Restore -> {
            BackHandler { setStage(if (Vault.exists(ctx)) Stage.Unlock else Stage.Setup) }
            RestoreScreen(
                activity = activity,
                onRestored = { vault ->
                    setUnlocked(vault)
                    setStage(Stage.List)
                },
                onCancel = { setStage(if (Vault.exists(ctx)) Stage.Unlock else Stage.Setup) },
            )
        }
        Stage.Diagnostics -> {
            BackHandler { backToList() }
            DiagnosticsScreen(onBack = backToList)
        }
    }
}

private fun deviceUnsupportedReason(ctx: Context): String? {
    val km = ctx.getSystemService(KeyguardManager::class.java)
    if (km == null || !km.isDeviceSecure) {
        return "Device screen lock required.\n\nThe vault binds the master key to your device's PIN / pattern / biometric. Set up a screen lock in system Settings, then come back."
    }
    val bm = BiometricManager.from(ctx)
    val canAuth = bm.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
    )
    if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
        return "BiometricPrompt unavailable on this device (status $canAuth). Vault requires either a strong biometric (fingerprint / face) or device credential (PIN / pattern). Configure in system Settings."
    }
    return null
}

@Composable
private fun SetupScreen(
    activity: FragmentActivity,
    onCreated: (UnlockedVault) -> Unit,
    onRestore: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var step by remember { mutableStateOf(0) } // 0=intro, 1=biometric-prompt-running
    var error by remember { mutableStateOf<String?>(null) }

    val deviceIssue = remember { deviceUnsupportedReason(ctx) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ledger — first-time setup", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)

        if (deviceIssue != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(UnderstoryTheme.semantic.warning.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                    .padding(12.dp),
            ) {
                Text(deviceIssue, color = UnderstoryTheme.semantic.warning, style = MaterialTheme.typography.bodyMedium)
            }
            SecureOutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Close")
            }
            return@Column
        }

        when (step) {
            0 -> {
                Text(
                    "Self-generated, self-sealed.",
                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge,
                )
                // §3.4: honest copy — no false "10s reveal window", the RNG is
                // not "the IME pipeline running".
                Text(
                    "passgen generates a 256-bit master key with the same cryptographic RNG it uses to generate your passwords. The master is self-encrypted under a hardware-backed, screen-lock-bound Keystore key and sealed inside the ledger it just created. It is never shown and never typed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        // §3.4: the real recovery story, not vaporware.
                        "This device self-seals a recovery kit so a fingerprint or screen-lock change re-binds the vault automatically — nothing to type.\n\nFor a lost or replaced device, export the recovery file under Ledger → Export and keep it somewhere safe. Make one now or any time.",
                        color = UnderstoryTheme.semantic.warning, style = MaterialTheme.typography.bodySmall,
                    )
                }
                SecureButton(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text("Create ledger")
                }
                SecureOutlinedButton(onClick = onRestore, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from backup instead")
                }
                SecureOutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
            1 -> {
                Text(
                    "Authenticate with your device to bind the vault master key.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                LaunchedEffect(Unit) {
                    runCatching {
                        val cipher = Crypto.deviceAuthCipherForEncrypt()
                        promptAuth(
                            activity = activity,
                            title = "Bind vault to this device",
                            cipher = cipher,
                            onSuccess = { authedCipher ->
                                runCatching {
                                    val v = Vault.createV2(ctx, authedCipher)
                                    onCreated(v)
                                }.onFailure { error = "Setup failed: ${it.message}" }
                            },
                            onError = { msg -> error = "Authentication failed: $msg" },
                            onCancel = { error = "Authentication cancelled."; step = 0 },
                        )
                    }.onFailure { error = "Crypto init failed: ${it.message}" }
                }
            }
        }
    }
}

@Composable
private fun UnlockScreen(
    activity: FragmentActivity,
    onUnlocked: (UnlockedVault) -> Unit,
    onRecovery: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ledger — unlock", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Authenticate with your device biometric or PIN to unlock.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }

        SecureButton(
            onClick = {
                if (working) return@SecureButton
                working = true
                error = null
                runCatching {
                    val iv = Vault.ivForUnlock(ctx)
                    val cipher = Crypto.deviceAuthCipherForDecrypt(iv)
                    promptAuth(
                        activity = activity,
                        title = "Unlock vault",
                        cipher = cipher,
                        onSuccess = { authedCipher ->
                            runCatching {
                                val v = Vault.unlockV2(ctx, authedCipher)
                                onUnlocked(v)
                            }.onFailure { t ->
                                working = false
                                // Distinguish an invalidated key (route to
                                // Recovery) from a transient failure (retry).
                                if (VaultRecovery.classifyUnlockFailure(t) ==
                                    VaultRecovery.VaultKeyState.PERMANENTLY_INVALIDATED
                                ) {
                                    onRecovery()
                                } else {
                                    error = "Vault decryption failed."
                                }
                            }
                        },
                        onError = { msg ->
                            error = "Authentication failed: $msg"
                            working = false
                        },
                        onCancel = {
                            error = "Authentication cancelled."
                            working = false
                        },
                    )
                }.onFailure { t ->
                    working = false
                    if (VaultRecovery.classifyUnlockFailure(t) ==
                        VaultRecovery.VaultKeyState.PERMANENTLY_INVALIDATED
                    ) {
                        onRecovery()
                    } else {
                        error = "Crypto init failed: ${t.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (working) "Authenticating…" else "Unlock with device auth")
        }
        // Real Recovery entry point (replaces the fictional "Settings → reset
        // vault" hint, A19). Available any time, not buried after 3 attempts.
        SecureOutlinedButton(onClick = onRecovery, modifier = Modifier.fillMaxWidth()) {
            Text("Can't unlock?")
        }
        SecureOutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
    }
}

private fun promptAuth(
    activity: FragmentActivity,
    title: String,
    cipher: Cipher,
    onSuccess: (Cipher) -> Unit,
    onError: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val executor = ContextCompat.getMainExecutor(activity)
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            val c = result.cryptoObject?.cipher
            if (c == null) onError("no cipher") else onSuccess(c)
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
        override fun onAuthenticationFailed() {
            // Single-attempt failure (e.g. wrong fingerprint). The system
            // will retry; we leave the prompt open.
        }
    }
    val prompt = BiometricPrompt(activity, executor, callback)
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setSubtitle("passgen vault")
        .setAllowedAuthenticators(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
        .build()
    prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
}

/** Non-master entries — the master-KEK entry is infrastructure, never a credential (§10.1). */
private fun UnlockedVault.userEntries(): List<VaultEntry> =
    contents.entries.filter { it.title != Vault.MASTER_ENTRY_TITLE }

@Composable
private fun ListScreen(
    vault: UnlockedVault,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onReceipts: () -> Unit,
    onView: (String) -> Unit,
    onLock: () -> Unit,
    onReset: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    val ctx = LocalContext.current
    val entries = vault.userEntries()
    val unclaimed = remember { Receipts.unclaimedCount(ctx) }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Ledger", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text("${entries.size} entries", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecureButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text("Add entry")
            }
            SecureOutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text("Lock")
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecureOutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text("Import")
            }
            SecureOutlinedButton(onClick = onExport, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text("Export / hand off")
            }
        }
        SecureOutlinedButton(onClick = onReceipts, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (unclaimed > 0) "Unclaimed generated passwords ($unclaimed)"
                else "Generated-password receipts",
            )
        }

        if (entries.isEmpty()) {
            // §10.3 empty state.
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(16.dp),
            ) {
                Text(
                    "Your ledger is empty. Import from Google, Proton, or Bitwarden, or add an entry — then hand off to Bitwarden any time via Export.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(entries, key = { it.id }) { e ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(14.dp),
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(e.title.ifEmpty { "(untitled)" }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                            if (e.username.isNotEmpty()) {
                                Text(e.username, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(6.dp))
                            SecureOutlinedButton(
                                onClick = { onView(e.id) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Open")
                            }
                        }
                    }
                }
            }
        }
        SecureOutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Reset ledger")
        }
        // ENG-ONLY: the Diagnostics log surface never ships in prod. Gated on
        // BuildConfig.FLAVOR == "eng"; in a prod build there is no button and no
        // route into DiagnosticsScreen from the ledger.
        if (BuildConfig.FLAVOR == "eng") {
            OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
                Text("Diagnostics")
            }
        }
    }
}

@Composable
private fun AddEntryScreen(
    vault: UnlockedVault,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var manualPassword by remember { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val snap = remember { Settings.load(ctx) }

    // Materialize an entry with the given password value, save, and route back.
    fun commit(passwordValue: String, wipe: (() -> Unit)?) {
        try {
            val now = System.currentTimeMillis()
            val entry = VaultEntry(
                id = UUID.randomUUID().toString(),
                title = title,
                username = username,
                password = passwordValue,
                url = url,
                notes = notes,
                created = now,
                updated = now,
            )
            vault.contents = vault.contents.copy(entries = vault.contents.entries + entry)
            vault.save()
            onSaved()
        } catch (t: Throwable) {
            error = "Save failed: ${t.message}"
            working = false
        } finally {
            wipe?.invoke()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("add entry", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username / email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().height(120.dp))

        Spacer(Modifier.height(4.dp))
        // §10.4: for a migration buffer the user must be able to store an
        // existing credential, not only a freshly generated one.
        Text(
            "Store an existing password (e.g. one Bitwarden won't export), or generate a new ${snap.length}-char one.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = manualPassword,
            onValueChange = { manualPassword = it },
            label = { Text("Existing password (optional)") },
            singleLine = true,
            visualTransformation = if (showManual) androidx.compose.ui.text.input.VisualTransformation.None
            else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { showManual = !showManual }) {
            Text(if (showManual) "Hide" else "Show", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecureButton(
                onClick = {
                    if (working || title.isEmpty()) return@SecureButton
                    working = true
                    error = null
                    if (manualPassword.isNotEmpty()) {
                        // Store the user-provided existing password verbatim.
                        commit(manualPassword, wipe = null)
                    } else {
                        if (!Generate.isValid(snap)) {
                            error = "Generator settings invalid (length 1–1000, ≥1 character class)."
                            working = false
                            return@SecureButton
                        }
                        val chars = Generate.fromSnapshot(snap)
                        commit(String(chars), wipe = { PasswordGenerator.wipe(chars) })
                    }
                },
                enabled = !working && title.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Text(
                    when {
                        working -> "Saving…"
                        manualPassword.isNotEmpty() -> "Save"
                        else -> "Generate & save"
                    },
                )
            }
            SecureOutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun ImportScreen(
    vault: UnlockedVault,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    incomingUri: android.net.Uri? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    // Parsed preview awaiting the user's explicit confirmation (§9). Holds the
    // detected format label + the parsed rows; NO vault write happens until the
    // user taps Import.
    var preview by remember { mutableStateOf<Pair<String, List<ImportFormats.ImportedPassword>>?>(null) }
    var previewName by remember { mutableStateOf("") }

    // 8 MiB cap before readText() bounds a hostile-file ANR (§8). Everything off
    // the main thread with a visible working state.
    val maxBytes = 8L * 1024 * 1024

    fun parseOnly(uri: android.net.Uri, displayName: String) {
        working = true
        status = "Reading…"
        preview = null
        previewName = displayName
        scope.launch {
            val outcome = runCatching {
                withContext(Bg.io) {
                    val size = runCatching {
                        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                    }.getOrNull() ?: -1L
                    if (size in 1..Long.MAX_VALUE && size > maxBytes) {
                        error("File too large (${size / 1024} KiB > ${maxBytes / 1024} KiB).")
                    }
                    val text = ctx.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader().readText()
                    } ?: error("Couldn't open the selected file")
                    if (text.toByteArray(Charsets.UTF_8).size > maxBytes) {
                        error("File too large.")
                    }
                    val format = ImportFormats.detect(text.take(2048))
                    val parsed = ImportFormats.parseAuto(text.reader())
                    format.name to parsed
                }
            }
            outcome.fold(
                onSuccess = { (fmt, rows) ->
                    working = false
                    if (rows.isEmpty()) {
                        status = "No importable entries found in that file."
                    } else {
                        status = null
                        preview = fmt to rows
                    }
                },
                onFailure = {
                    working = false
                    status = "Import failed: ${it.message}"
                },
            )
        }
    }

    fun commit(parsed: List<ImportFormats.ImportedPassword>) {
        working = true
        status = "Importing…"
        preview = null
        scope.launch {
            val outcome = runCatching {
                withContext(Bg.io) {
                    val existing = vault.contents.entries.map {
                        it.url.trim().lowercase() to it.username.trim().lowercase()
                    }.toHashSet()
                    var added = 0
                    var skipped = 0
                    val now = System.currentTimeMillis()
                    val newEntries = parsed.mapNotNull { p ->
                        val key = p.url.trim().lowercase() to p.username.trim().lowercase()
                        if (key in existing) { skipped++; return@mapNotNull null }
                        existing += key
                        added++
                        VaultEntry(
                            id = UUID.randomUUID().toString(),
                            title = p.title.ifEmpty { p.url.ifEmpty { p.username } },
                            username = p.username,
                            password = p.password,
                            url = p.url,
                            notes = p.notes,
                            created = now,
                            updated = now,
                            source = "import",
                        )
                    }
                    vault.contents = vault.contents.copy(entries = vault.contents.entries + newEntries)
                    vault.save()
                    added to skipped
                }
            }
            outcome.fold(
                onSuccess = { (added, skipped) ->
                    working = false
                    Diagnostics.log("passgen.Import", "imported added=$added skipped=$skipped")
                    status = "Imported $added entries (skipped $skipped duplicates)."
                },
                onFailure = {
                    working = false
                    Diagnostics.error("passgen.Import", "import failed: ${it.message}")
                    status = "Import failed: ${it.message}"
                },
            )
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Diagnostics.log("passgen.Import", "picker selected uri")
        parseOnly(uri, displayName = fileNameOf(ctx, uri))
    }

    // §9: an incoming ACTION_VIEW URI is parsed for a preview only — it does NOT
    // auto-import. The user must tap Import on the confirmation card. This makes
    // the manifest's "no code path bypasses the confirmation" contract true.
    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            Diagnostics.log("passgen.Import", "parse-only from incoming URI")
            parseOnly(incomingUri, displayName = fileNameOf(ctx, incomingUri))
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("import passwords", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pick an export from a supported source. Files are parsed locally; " +
                "nothing is uploaded.\n\n" +
                "  •  Google Password Manager — CSV\n" +
                "  •  Proton Pass — JSON (unencrypted) or CSV\n" +
                "  •  Bitwarden — CSV or JSON (unencrypted)\n\n" +
                "You'll review what was found before anything is written. " +
                "Duplicates (same URL + username) are skipped.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )

        val pv = preview
        if (pv != null) {
            // Confirmation card (§9).
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(UnderstoryTheme.semantic.success.copy(alpha = 0.14f), RoundedCornerShape(8.dp))
                    .padding(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Import ${pv.second.size} entries from ${previewName.ifEmpty { "the selected file" }}?",
                        color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Source: ${pv.first}. Duplicates (same URL + username) will be skipped.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecureButton(
                    onClick = { commit(pv.second) },
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) { Text("Import") }
                SecureOutlinedButton(
                    onClick = { preview = null; status = null },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) { Text("Cancel") }
            }
        } else {
            SecureButton(
                onClick = {
                    status = null
                    runCatching {
                        picker.launch(arrayOf("text/csv", "text/comma-separated-values",
                            "application/json", "text/plain", "*/*"))
                    }.onFailure {
                        status = "Couldn't open file picker: ${it.message}"
                    }
                },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (working) "Working…" else "Pick file")
            }
        }

        if (working) {
            Spacer(Modifier.height(4.dp))
            CircularProgressIndicator()
        }
        status?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        SecureOutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(if (status?.startsWith("Imported") == true && !working) "Done" else "Cancel")
        }
        if (status?.startsWith("Imported") == true && !working) {
            LaunchedEffect(status) {
                kotlinx.coroutines.delay(800)
                onDone()
            }
        }
    }
}

private val exportDateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

@Composable
private fun ExportScreen(
    vault: UnlockedVault,
    onDone: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var plaintextArmed by remember { mutableStateOf(false) }
    var plaintextFormat by remember { mutableStateOf("bitwarden_csv") }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    val today = remember { exportDateFormat.format(Date()) }

    // Non-master entries for every export.
    fun exportableEntries(): List<VaultEntry> = vault.userEntries()

    // Lane 1: the self-sealing recovery FILE (operator directive 2026-07-03).
    // Opaque, self-contained: RecoveryFile seals a random recovery key plus the
    // key-encrypted PAYLOAD into one blob, so it restores on a brand-new device
    // (where no vault.bin exists yet). The opaque payload is the serialized user
    // entries — exactly what a restore reconstructs onto a fresh vault. Nothing
    // is displayed and nothing is typed; the app self-manages the key.
    val recoveryFileSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        status = "Writing recovery file…"
        scope.launch {
            val outcome = runCatching {
                withContext(Bg.io) {
                    val payload = Vault.serialize(VaultContents(exportableEntries()))
                        .toByteArray(Charsets.UTF_8)
                    try {
                        ctx.contentResolver.openOutputStream(uri)?.use { out ->
                            com.understory.backup.RecoveryFile.exportKit(ctx, out, payload)
                        } ?: error("Could not open the chosen file for writing.")
                    } finally {
                        Crypto.wipe(payload)
                    }
                }
            }
            outcome.fold(
                onSuccess = { working = false; status = "Recovery file saved." },
                onFailure = { working = false; status = "Export failed: ${it.message}" },
            )
        }
    }

    val plaintextSaver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*"),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        working = true
        status = "Writing…"
        val fmt = plaintextFormat
        scope.launch {
            val outcome = runCatching {
                withContext(Bg.io) {
                    val entries = exportableEntries()
                    val text = when (fmt) {
                        "bitwarden_csv" -> ExportFormats.toBitwardenCsv(entries)
                        "bitwarden_json" -> ExportFormats.toBitwardenJson(entries)
                        else -> ExportFormats.toGenericCsv(entries)
                    }
                    ctx.contentResolver.openOutputStream(uri)?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("Could not open the chosen file for writing.")
                }
            }
            outcome.fold(
                onSuccess = { working = false; status = "Plaintext file saved. Import it into Bitwarden, then delete it." },
                onFailure = { working = false; status = "Export failed: ${it.message}" },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Export / hand off", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)

        // Lane 1: the self-sealing recovery FILE (recommended). No passphrase to
        // type or remember — the app self-manages a random recovery key sealed
        // inside the opaque file.
        Text("Export recovery file (recommended)", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
        Text(
            "Writes one opaque recovery file that can rebuild your vault on a new device. Nothing to type or remember.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            modifier = Modifier.fillMaxWidth()
                .background(UnderstoryTheme.semantic.warning.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                .padding(12.dp),
        ) {
            Text(
                "Keep this file somewhere safe — anyone who has it can open your vault.",
                color = UnderstoryTheme.semantic.warning, style = MaterialTheme.typography.bodySmall,
            )
        }
        SecureButton(
            onClick = {
                status = null
                runCatching {
                    recoveryFileSaver.launch("understory-keys-$today.ukit")
                }.onFailure { status = "Couldn't open the save dialog: ${it.message}" }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Export recovery file") }

        Spacer(Modifier.height(8.dp))

        // Lane 2: plaintext hand-off to Bitwarden — dangerous, gated.
        Text("Hand off to Bitwarden (plaintext)", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
        com.understory.security.ui.components.SwitchRow(
            label = "Enable plaintext export",
            checked = plaintextArmed,
            onCheckedChange = { plaintextArmed = it },
            supporting = "Off by default. Writes your passwords UNENCRYPTED.",
        )
        if (plaintextArmed) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
                    .padding(12.dp),
            ) {
                Text(
                    "This writes your passwords UNENCRYPTED so you can import them into Bitwarden, then delete the file. Anyone who reads the file reads your passwords.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text("Format:", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            FormatRadio("Bitwarden CSV", "bitwarden_csv", plaintextFormat) { plaintextFormat = it }
            FormatRadio("Bitwarden JSON", "bitwarden_json", plaintextFormat) { plaintextFormat = it }
            FormatRadio("Generic CSV", "generic_csv", plaintextFormat) { plaintextFormat = it }
            SecureButton(
                onClick = {
                    status = null
                    val ext = if (plaintextFormat == "bitwarden_json") "json" else "csv"
                    runCatching {
                        plaintextSaver.launch("understory-keys-bitwarden-$today.$ext")
                    }.onFailure { status = "Couldn't open the save dialog: ${it.message}" }
                },
                enabled = !working,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text("I understand — export plaintext") }
        }

        if (working) {
            Spacer(Modifier.height(4.dp))
            CircularProgressIndicator()
        }
        status?.let {
            Text(
                it,
                color = if (it.contains("saved")) UnderstoryTheme.semantic.success else UnderstoryTheme.semantic.warning,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        SecureOutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(if (status?.contains("saved") == true) "Done" else "Back")
        }
    }
}

@Composable
private fun FormatRadio(label: String, value: String, selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.RadioButton(
            selected = selected == value,
            onClick = { onSelect(value) },
        )
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RestoreScreen(
    activity: FragmentActivity,
    onRestored: (UnlockedVault) -> Unit,
    onCancel: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    // Restore = import the opaque recovery file (operator directive 2026-07-03).
    // Pick the file via SAF, RecoveryFile.importKit reads R from inside it and
    // returns the payload — the user never types a key. Then create a fresh
    // vault under one biometric bind and write the recovered entries into it.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) picker@{ uri ->
        if (uri == null) return@picker
        working = true
        status = "Reading recovery file…"
        scope.launch {
            // 1) Import + parse the entries off the main thread.
            val decoded = runCatching {
                withContext(Bg.io) {
                    val payload = ctx.contentResolver.openInputStream(uri)?.use { input ->
                        com.understory.backup.RecoveryFile.importKit(input)
                    } ?: error("Couldn't open the selected file")
                    try {
                        Vault.parse(String(payload, Charsets.UTF_8))
                    } finally {
                        Crypto.wipe(payload)
                    }
                }
            }
            decoded.fold(
                onSuccess = { contents ->
                    // 2) Create the fresh vault under a biometric prompt, then
                    // write the recovered entries. createV2 seals a new master
                    // and a fresh in-vault recovery kit.
                    status = "Authenticate to create the restored vault…"
                    runCatching {
                        val cipher = Crypto.deviceAuthCipherForEncrypt()
                        promptAuth(
                            activity = activity,
                            title = "Bind restored vault to this device",
                            cipher = cipher,
                            onSuccess = { authed ->
                                scope.launch {
                                    val outcome = runCatching {
                                        withContext(Bg.io) {
                                            val v = Vault.createV2(ctx, authed)
                                            val now = System.currentTimeMillis()
                                            val restored = contents.entries
                                                .filter { it.title != Vault.MASTER_ENTRY_TITLE }
                                                .map {
                                                    it.copy(
                                                        id = UUID.randomUUID().toString(),
                                                        created = now,
                                                        updated = now,
                                                    )
                                                }
                                            v.contents = v.contents.copy(entries = v.contents.entries + restored)
                                            v.save()
                                            v
                                        }
                                    }
                                    outcome.fold(
                                        onSuccess = { v -> working = false; onRestored(v) },
                                        onFailure = { working = false; status = "Restore failed: ${it.message}" },
                                    )
                                }
                            },
                            onError = { msg -> working = false; status = "Authentication failed: $msg" },
                            onCancel = { working = false; status = "Authentication cancelled." },
                        )
                    }.onFailure { working = false; status = "Crypto init failed: ${it.message}" }
                },
                onFailure = {
                    working = false
                    status = "Not a valid recovery file, or it's corrupt."
                },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Restore from recovery file", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pick the recovery file you exported earlier (.ukit). Your entries are restored into a fresh ledger on this device. Nothing to type.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
        )
        SecureButton(
            onClick = {
                status = null
                runCatching { picker.launch(arrayOf("application/octet-stream", "*/*")) }
                    .onFailure { status = "Couldn't open file picker: ${it.message}" }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (working) "Restoring…" else "Pick recovery file") }

        if (working) {
            Spacer(Modifier.height(4.dp))
            CircularProgressIndicator()
        }
        status?.let { Text(it, color = UnderstoryTheme.semantic.warning, style = MaterialTheme.typography.bodyMedium) }
        SecureOutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel")
        }
    }
}

/**
 * Silent-first re-bind after a biometric re-enrollment / lock-screen change
 * bricked the vault's device-auth key (operator directive 2026-07-03).
 *
 * Step 1 (silent, nothing on screen): read the vault's KEK from the in-vault
 * sealed recovery kit ([RecoveryFile.readKekFromSealedKit]). The wrap key is not
 * auth-bound, so this needs no prompt and shows nothing.
 *   - If the kit yields a KEK, mint a FRESH device-auth key (one biometric bind
 *     — the single unavoidable action; the vault key is auth-required by design)
 *     and re-wrap the recovered KEK under it via [Vault.rebindFromKek]. No key is
 *     ever shown and nothing is typed.
 *   - If the kit is gone (returns null), fall back to importing the exported
 *     recovery file ([onNeedRecoveryFile] → Restore screen). Still no typing.
 */
@Composable
private fun RebindScreen(
    activity: FragmentActivity,
    onRebound: (UnlockedVault) -> Unit,
    onNeedRecoveryFile: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // null = still probing the sealed kit; true = kit present (offer re-bind);
    // false = kit gone (handed off to the recovery-file import).
    var kitPresent by remember { mutableStateOf<Boolean?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    // Step 1: silently probe the sealed kit. Nothing rendered, no prompt.
    LaunchedEffect(Unit) {
        val present = withContext(Bg.io) {
            com.understory.backup.RecoveryFile.hasSealedKit(ctx) &&
                com.understory.backup.RecoveryWrapKey.keyExists()
        }
        if (!present) {
            kitPresent = false
            onNeedRecoveryFile()
        } else {
            kitPresent = true
        }
    }

    // Step 2: recover the KEK from the kit and re-bind under a fresh device-auth
    // key (one biometric confirm). Falls back to the recovery-file import if the
    // kit turns out to be undecryptable after all.
    fun runRebind() {
        if (working) return
        working = true
        status = null
        scope.launch {
            val kek = withContext(Bg.io) {
                com.understory.backup.RecoveryFile.readKekFromSealedKit(ctx)
            }
            if (kek == null) {
                working = false
                onNeedRecoveryFile()
                return@launch
            }
            // The old auth key is invalidated — drop it before minting a fresh
            // one, then bind the vault to the new key under one prompt.
            runCatching {
                Crypto.deleteDeviceAuthKey()
                val cipher = Crypto.deviceAuthCipherForEncrypt()
                promptAuth(
                    activity = activity,
                    title = "Re-bind vault to this device",
                    cipher = cipher,
                    onSuccess = { authed ->
                        scope.launch {
                            val outcome = runCatching {
                                withContext(Bg.io) { Vault.rebindFromKek(ctx, kek, authed) }
                            }
                            Crypto.wipe(kek)
                            outcome.fold(
                                onSuccess = { v -> working = false; onRebound(v) },
                                onFailure = { working = false; status = "Re-bind failed: ${it.message}" },
                            )
                        }
                    },
                    onError = { msg -> Crypto.wipe(kek); working = false; status = "Authentication failed: $msg" },
                    onCancel = { Crypto.wipe(kek); working = false; status = "Authentication cancelled." },
                )
            }.onFailure { Crypto.wipe(kek); working = false; status = "Crypto init failed: ${it.message}" }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Restore access", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        when (kitPresent) {
            null -> {
                Text(
                    "Checking this device's sealed recovery kit…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
                CircularProgressIndicator()
            }
            true -> {
                Text(
                    "Your fingerprint or screen lock changed, which re-locked the vault's key. This device still holds a sealed recovery kit, so it can re-bind the vault to your new lock with one confirmation. Nothing is shown and nothing to type.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
                status?.let { Text(it, color = UnderstoryTheme.semantic.warning, style = MaterialTheme.typography.bodyMedium) }
                if (working) {
                    Spacer(Modifier.height(4.dp))
                    CircularProgressIndicator()
                }
                SecureButton(
                    onClick = ::runRebind,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (working) "Re-binding…" else "Re-bind vault") }
                SecureOutlinedButton(onClick = onNeedRecoveryFile, modifier = Modifier.fillMaxWidth()) {
                    Text("Use my recovery file instead")
                }
                SecureOutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Close")
                }
            }
            false -> {
                // Handoff to the recovery-file import already fired; keep a plain
                // line in case the transition hasn't drawn yet.
                Text(
                    "Opening your recovery file…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun ReceiptsScreen(
    activity: FragmentActivity,
    vault: UnlockedVault,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Decrypt the receipts off the main thread; the vault is already unlocked so
    // no second prompt is needed to LIST them (§5.4). Reveal of a saved value is
    // separately biometric-gated below.
    var receipts by remember { mutableStateOf<List<Receipts.Receipt>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var dismissId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            val loaded = runCatching { withContext(Bg.io) { Receipts.load(ctx) } }
            loaded.fold(
                onSuccess = { receipts = it.sortedByDescending { r -> r.createdAt } },
                onFailure = { receipts = emptyList(); status = "Couldn't read receipts: ${it.message}" },
            )
        }
    }
    LaunchedEffect(Unit) { reload() }

    fun revealValue(r: Receipts.Receipt) {
        val value = r.savedValue ?: return
        // Lightweight biometric confirm (the vault is already open) on the vault
        // key; copy to clipboard with the standard auto-clear, never render.
        runCatching {
            val iv = Vault.ivForUnlock(activity)
            val cipher = Crypto.deviceAuthCipherForDecrypt(iv)
            promptAuth(
                activity = activity,
                title = "Reveal saved password",
                cipher = cipher,
                onSuccess = {
                    com.understory.security.Clipboard.copySensitive(
                        context = ctx,
                        text = value,
                        autoClearSeconds = 30,
                        label = "passgen-receipt",
                    )
                    status = "Copied (clears in 30s while passgen is running)."
                },
                onError = { msg -> status = "Authentication failed: $msg" },
                onCancel = { },
            )
        }.onFailure { status = "Crypto init failed: ${it.message}" }
    }

    fun saveToLedger(r: Receipts.Receipt) {
        val value = r.savedValue ?: return
        val now = System.currentTimeMillis()
        val entry = VaultEntry(
            id = UUID.randomUUID().toString(),
            title = r.target.ifEmpty { "(unknown app)" },
            username = "",
            password = value,
            url = if (r.targetKind == "domain") r.target else "",
            notes = "",
            created = now,
            updated = now,
            source = "receipt",
        )
        vault.contents = vault.contents.copy(entries = vault.contents.entries + entry)
        vault.save()
        scope.launch { withContext(Bg.io) { Receipts.markClaimed(ctx, r.id) } }
        status = "Saved to ledger."
        reload()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Generated-password receipts", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)

        val list = receipts
        when {
            list == null -> {
                CircularProgressIndicator()
            }
            list.isEmpty() -> {
                Text(
                    "No generated-password receipts yet. When you generate a password via the keyboard or autofill, a receipt lands here so you never lose it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                )
            }
            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().weight(1f)) {
                    items(list, key = { it.id }) { r ->
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .padding(12.dp),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    r.target.ifEmpty { "unknown app" },
                                    color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    "${sourceLabel(r.source)} · ${relativeTime(r.createdAt)} · " +
                                        if (r.savedValue != null) "value saved" else "not saved",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                                )
                                if (r.savedValue == null) {
                                    Text(
                                        "Value not stored (you had 'keep generated value' off). If you're locked out, use the site's account recovery.",
                                        color = UnderstoryTheme.semantic.dim, style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (r.savedValue != null) {
                                        SecureOutlinedButton(
                                            onClick = { revealValue(r) },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Reveal", style = MaterialTheme.typography.bodyMedium) }
                                        SecureOutlinedButton(
                                            onClick = { saveToLedger(r) },
                                            modifier = Modifier.weight(1f),
                                        ) { Text("Save", style = MaterialTheme.typography.bodyMedium) }
                                    }
                                    SecureOutlinedButton(
                                        onClick = { dismissId = r.id },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Dismiss", style = MaterialTheme.typography.bodyMedium) }
                                }
                            }
                        }
                    }
                }
            }
        }
        status?.let { Text(it, color = UnderstoryTheme.semantic.success, style = MaterialTheme.typography.bodyMedium) }
        SecureOutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }

    val toDismiss = dismissId
    if (toDismiss != null) {
        AlertDialog(
            onDismissRequest = { dismissId = null },
            title = { Text("Dismiss reminder?") },
            text = { Text("This won't recover the password — dismiss the reminder?") },
            confirmButton = {
                TextButton(onClick = {
                    dismissId = null
                    scope.launch { withContext(Bg.io) { Receipts.markClaimed(ctx, toDismiss) } }
                    reload()
                }) { Text("Dismiss") }
            },
            dismissButton = {
                TextButton(onClick = { dismissId = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun ViewEntryScreen(
    activity: FragmentActivity,
    vault: UnlockedVault,
    entryId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val ctx = LocalContext.current
    val entry = vault.contents.entries.firstOrNull { it.id == entryId }
    if (entry == null) {
        onBack(); return
    }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun copyExistingPassword() {
        if (working) return
        working = true
        status = null
        runCatching {
            val iv = Vault.ivForUnlock(activity)
            val cipher = Crypto.deviceAuthCipherForDecrypt(iv)
            promptAuth(
                activity = activity,
                title = "Copy password",
                cipher = cipher,
                onSuccess = {
                    com.understory.security.Clipboard.copySensitive(
                        context = ctx,
                        text = entry.password,
                        autoClearSeconds = 30,
                        label = "passgen-password",
                    )
                    status = "Password copied (clears in 30s while passgen is running). Paste into the target field."
                    working = false
                },
                onError = { msg ->
                    status = "Authentication failed: $msg"
                    working = false
                },
                onCancel = { working = false },
            )
        }.onFailure {
            status = "Crypto init failed: ${it.message}"
            working = false
        }
    }

    fun regeneratePassword() {
        if (working) return
        val snap = Settings.load(ctx)
        if (!Generate.isValid(snap)) {
            status = "Generator settings invalid (length 1–1000, ≥1 character class)."
            return
        }
        working = true
        status = null
        runCatching {
            val iv = Vault.ivForUnlock(activity)
            val cipher = Crypto.deviceAuthCipherForDecrypt(iv)
            promptAuth(
                activity = activity,
                title = "Regenerate password",
                cipher = cipher,
                onSuccess = {
                    val chars = Generate.fromSnapshot(snap)
                    try {
                        val now = System.currentTimeMillis()
                        val newPassword = String(chars)
                        vault.contents = vault.contents.copy(
                            entries = vault.contents.entries.map { e ->
                                if (e.id == entryId) e.copy(password = newPassword, updated = now)
                                else e
                            },
                        )
                        vault.save()
                        com.understory.security.Clipboard.copySensitive(
                            context = ctx,
                            text = newPassword,
                            autoClearSeconds = 30,
                            label = "passgen-password",
                        )
                        status = "Regenerated + saved. New value copied " +
                            "(clears in 30s while passgen is running). Paste into the field that " +
                            "asks for the new password."
                    } catch (t: Throwable) {
                        status = "Regenerate failed: ${t.message}"
                    } finally {
                        PasswordGenerator.wipe(chars)
                        working = false
                    }
                },
                onError = { msg ->
                    status = "Authentication failed: $msg"
                    working = false
                },
                onCancel = { working = false },
            )
        }.onFailure {
            status = "Crypto init failed: ${it.message}"
            working = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(entry.title.ifEmpty { "(untitled)" }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.headlineSmall)
        if (entry.username.isNotEmpty()) {
            Text("Username:  ${entry.username}", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        }
        if (entry.url.isNotEmpty()) {
            Text("URL:  ${entry.url}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (entry.notes.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Notes:", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(entry.notes, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text("Password:", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text("●●●●●●●●●●●●", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge)
        Text(
            "Threat model: passwords never render on screen. Use the " +
                "copy or regenerate paths below — both require device " +
                "auth and put the value on the clipboard with a 30-second " +
                "auto-clear.",
            color = UnderstoryTheme.semantic.dim, style = MaterialTheme.typography.bodySmall,
        )
        status?.let {
            Text(
                it,
                color = if (it.startsWith("Password copied") || it.startsWith("Regenerated"))
                    UnderstoryTheme.semantic.success else UnderstoryTheme.semantic.warning,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(4.dp))
        SecureButton(
            onClick = ::copyExistingPassword,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (working) "Authenticating…" else "Copy password to clipboard (biometric)")
        }
        SecureOutlinedButton(
            onClick = ::regeneratePassword,
            enabled = !working,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (working) "Authenticating…" else "Regenerate password + save (biometric)")
        }

        Spacer(Modifier.height(20.dp))
        SecureOutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
        Spacer(Modifier.height(8.dp))
        // §10.2: confirm-on-delete, visually separated from Back, error-styled.
        SecureButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text("Delete entry")
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete '${entry.title.ifEmpty { "(untitled)" }}'?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDeleted() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

// -- small helpers -----------------------------------------------------------

private fun sourceLabel(source: String): String = when (source) {
    "autofill" -> "autofill"
    "ime" -> "keyboard"
    "clipboard" -> "clipboard"
    else -> source
}

private fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val mins = diff / 60000
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}

/** Best-effort display name from a content URI; falls back to "" on any error. */
private fun fileNameOf(ctx: Context, uri: Uri): String = runCatching {
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else ""
    } ?: ""
}.getOrDefault("")
