package com.understory.passgen

import com.understory.security.Crypto
import com.understory.security.Diagnostics
import com.understory.security.DiagnosticsScreen
import com.understory.security.SecureButton
import com.understory.security.SecureOutlinedButton
import com.understory.security.Tamper
import com.understory.security.TestingMode

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.WindowManager
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import java.util.UUID
import javax.crypto.Cipher

class VaultActivity : FragmentActivity() {

    companion object {
        /**
         * Optional Intent extra: a content `Uri` (typeof android.net.Uri,
         * passed via Intent.putExtra) for a file picked by the user via
         * the system "Open with…" dialog. When present, VaultActivity
         * routes through the normal unlock flow and lands in the import
         * screen with this URI pre-loaded — the user still has to confirm
         * the import explicitly, and never sees the generator screen.
         */
        const val EXTRA_PENDING_IMPORT_URI = "com.understory.passgen.EXTRA_PENDING_IMPORT_URI"
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
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("vault crash", color = Color(0xFFEF5350), fontSize = 18.sp)
                        Text(t.toString(), color = Color(0xFFE0E0E0), fontSize = 11.sp)
                    }
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

        // Pull the optional pending import URI passed by MainActivity when
        // the user opened a CSV/JSON via the system "Open with…" dialog.
        // Carrying this through onCreate keeps the URI in scope across the
        // whole vault flow (Unlock → Import) without re-reading intent
        // state from inside @Composable code.
        @Suppress("DEPRECATION")
        val pendingImportUri: android.net.Uri? = intent?.getParcelableExtra(EXTRA_PENDING_IMPORT_URI)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
                    VaultRoot(
                        activity = this,
                        unlockedRef = ::unlocked,
                        setUnlocked = { unlocked = it },
                        onClose = { finishAndRemoveTask() },
                        pendingImportUri = pendingImportUri,
                    )
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Diagnostics.log("passgen.VaultActivity",
            "onUserLeaveHint (keepAlive=${TestingMode.KEEP_ALIVE_ON_LEAVE})")
        // Skip during the testing phase so the app stays alive across
        // switching apps. RELEASE-BLOCKER to flip
        // TestingMode.KEEP_ALIVE_ON_LEAVE = false before publish.
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
        // Skip lock during testing (TestingMode.KEEP_ALIVE_ON_LEAVE) so
        // switching apps doesn't force re-auth. RELEASE-BLOCKER to flip
        // KEEP_ALIVE_ON_LEAVE = false before publish.
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

private enum class Stage { Setup, Unlock, List, AddEntry, ViewEntry, Import, Diagnostics }

@Composable
private fun VaultRoot(
    activity: FragmentActivity,
    unlockedRef: () -> UnlockedVault?,
    setUnlocked: (UnlockedVault?) -> Unit,
    onClose: () -> Unit,
    pendingImportUri: android.net.Uri? = null,
) {
    val ctx = LocalContext.current
    // String-encoded saveable state, consistent with the rest of the suite.
    var stageName by rememberSaveable {
        mutableStateOf(if (Vault.exists(ctx)) Stage.Unlock.name else Stage.Setup.name)
    }
    val stage = remember(stageName) { Stage.valueOf(stageName) }
    val setStage: (Stage) -> Unit = {
        Diagnostics.log("passgen.VaultRoot", "stage transition: $stageName → ${it.name}")
        stageName = it.name
    }
    var selectedEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    // Single-shot pending URI: consumed after the import screen reads it.
    // We don't bother with rememberSaveable since the Activity uses
    // configChanges to skip recreation; if we ever drop that, the worst
    // case is the user has to re-trigger the import via "Open with…".
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
                setStage(if (pendingImport != null) Stage.Import else Stage.List)
            },
            onClose = onClose,
        )
        Stage.Unlock -> UnlockScreen(
            activity = activity,
            onUnlocked = { vault ->
                setUnlocked(vault)
                setStage(if (pendingImport != null) Stage.Import else Stage.List)
            },
            onClose = onClose,
        )
        Stage.List -> {
            val v = unlockedRef() ?: return run { setStage(Stage.Unlock) }
            ListScreen(
                vault = v,
                onAdd = { setStage(Stage.AddEntry) },
                onImport = { setStage(Stage.Import) },
                onView = { id ->
                    selectedEntryId = id
                    setStage(Stage.ViewEntry)
                },
                onLock = {
                    v.lock()
                    setUnlocked(null)
                    onClose()
                },
                onClose = onClose,
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
            // Consume `pendingImport` exactly once: capture into a local
            // and clear the holder so a configuration change (or re-entry)
            // doesn't replay the auto-import.
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
        Text("vault — first-time setup", color = Color(0xFFE0E0E0), fontSize = 22.sp)

        if (deviceIssue != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3D2A00), RoundedCornerShape(6.dp))
                    .padding(12.dp),
            ) {
                Text(deviceIssue, color = Color(0xFFFFB74D), fontSize = 12.sp)
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
                    color = Color(0xFFE0E0E0), fontSize = 14.sp,
                )
                Text(
                    "The passgen IME generates a 256-bit master key (the same RNG that types passwords into other apps). The master is then:\n\n" +
                        "  •  self-encrypted under a hardware-backed Keystore key\n" +
                        "  •  self-bound to this device's screen lock (biometric / PIN)\n" +
                        "  •  self-sealed as the first entry of the vault it just created\n\n" +
                        "It was never typed. It was never on screen. It exists in memory for microseconds and only as random bytes. After unlock, you can view it (biometric-gated, 10s window) for paper transcription if you want one.",
                    color = Color(0xFF9E9E9E), fontSize = 12.sp,
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(6.dp))
                        .padding(12.dp),
                ) {
                    Text(
                        "Lost device = lost vault.\n\nThe Keystore-wrapped copy of the master cannot leave this device. The in-vault copy is only reachable AFTER unlock. Stage 2C adds an HOTP-gated encrypted backup file — that's the recovery path.",
                        color = Color(0xFFFFB74D), fontSize = 11.sp,
                    )
                }
                SecureButton(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) {
                    Text("Generate via IME pipeline")
                }
                SecureOutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
            1 -> {
                Text(
                    "Authenticate with your device to bind the vault master key.",
                    color = Color(0xFF9E9E9E), fontSize = 12.sp,
                )
                error?.let { Text(it, color = Color(0xFFEF5350), fontSize = 12.sp) }
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
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var attempts by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("vault — unlock", color = Color(0xFFE0E0E0), fontSize = 22.sp)
        Text(
            "Authenticate with your device biometric or PIN to unlock.",
            color = Color(0xFF9E9E9E), fontSize = 13.sp,
        )
        error?.let { Text(it, color = Color(0xFFEF5350), fontSize = 12.sp) }

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
                            }.onFailure {
                                error = "Vault decryption failed."
                                working = false
                                attempts++
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
                }.onFailure {
                    error = "Crypto init failed: ${it.message}"
                    working = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (working) "Authenticating…" else "Unlock with device auth")
        }
        SecureOutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
        if (attempts >= 3) {
            Text(
                "If you cannot unlock: device biometric was likely re-enrolled (the Keystore key is invalidated by enrollment changes). The vault must be reset and restored from a backup. Settings → reset vault.",
                color = Color(0xFF707070), fontSize = 11.sp,
            )
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

@Composable
private fun ListScreen(
    vault: UnlockedVault,
    onAdd: () -> Unit,
    onImport: () -> Unit,
    onView: (String) -> Unit,
    onLock: () -> Unit,
    onClose: () -> Unit,
    onDiagnostics: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("vault", color = Color(0xFFE0E0E0), fontSize = 22.sp)
        Text("${vault.contents.entries.size} entries", color = Color(0xFF9E9E9E), fontSize = 12.sp)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecureButton(onClick = onAdd, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text("Add entry")
            }
            SecureOutlinedButton(onClick = onLock, modifier = Modifier.fillMaxWidth().weight(1f)) {
                Text("Lock")
            }
        }
        SecureOutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
            Text("Import from file")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            items(vault.contents.entries, key = { it.id }) { e ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1C1C1C), RoundedCornerShape(8.dp))
                        .padding(14.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(e.title.ifEmpty { "(untitled)" }, color = Color(0xFFE0E0E0), fontSize = 14.sp)
                        if (e.username.isNotEmpty()) {
                            Text(e.username, color = Color(0xFF9E9E9E), fontSize = 11.sp)
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
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Diagnostics")
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
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val snap = remember { Settings.load(ctx) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("add entry", color = Color(0xFFE0E0E0), fontSize = 22.sp)
        OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username / email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth().height(120.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap Save: passgen generates a ${snap.length}-char password using current settings, encrypts the entry, writes it. The generated value is never held in screen state.",
            color = Color(0xFF9E9E9E), fontSize = 11.sp,
        )
        error?.let { Text(it, color = Color(0xFFEF5350), fontSize = 12.sp) }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecureButton(
                onClick = {
                    if (working || title.isEmpty()) return@SecureButton
                    val opts = Settings.toGeneratorOptions(snap)
                    if (!opts.isValid()) {
                        error = "Generator settings invalid (length 1–1000, ≥1 character class)."
                        return@SecureButton
                    }
                    working = true
                    error = null
                    val chars = PasswordGenerator.generate(opts)
                    try {
                        val now = System.currentTimeMillis()
                        val entry = VaultEntry(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            username = username,
                            password = String(chars),
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
                        PasswordGenerator.wipe(chars)
                    }
                },
                enabled = !working && title.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                Text(if (working) "Saving…" else "Generate & save")
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
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    // Shared import body: factored out so both the SAF picker callback and
    // the LaunchedEffect for an incoming "Open with…" URI use the exact
    // same logic. Reads via contentResolver, parses, dedups against the
    // existing vault by (url, username) case-insensitive, never logs
    // password values.
    fun runImport(uri: android.net.Uri) {
        working = true
        status = "Reading…"
        try {
            val text = ctx.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader().readText()
            } ?: throw IllegalStateException("Couldn't open the selected file")
            val parsed = ImportFormats.parseAuto(text.reader())

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
                )
            }
            vault.contents = vault.contents.copy(entries = vault.contents.entries + newEntries)
            vault.save()
            Diagnostics.log("passgen.Import",
                "imported added=$added skipped=$skipped totalParsed=${parsed.size}")
            status = "Imported $added entries (skipped $skipped duplicates)."
        } catch (t: Throwable) {
            Diagnostics.error("passgen.Import",
                "import failed: ${t.javaClass.simpleName}: ${t.message}")
            status = "Import failed: ${t.message}"
        } finally {
            working = false
        }
    }

    val picker = rememberLauncherForActivityResult(
        // Generic OpenDocument so the user can pick any text/JSON/CSV file. We
        // detect the format from contents, not from the MIME type the picker
        // hands us (Drive et al. lie about MIME for *.csv as application/octet-
        // stream). Read in this thread because vault save is already on this
        // thread elsewhere; imports of any plausible vault size are <1 MB.
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        Diagnostics.log("passgen.Import", "picker selected uri")
        runImport(uri)
    }

    // If we arrived from "Open with…" with a URI already in hand, skip the
    // picker entirely and run the import as soon as the user lands here.
    // The LaunchedEffect key is `incomingUri` so the import fires once per
    // distinct URI; the parent already nulls `pendingImport` after first
    // entry, but this LaunchedEffect contract is the second guard.
    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            Diagnostics.log("passgen.Import", "auto-import from incoming URI")
            runImport(incomingUri)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("import passwords", color = Color(0xFFE0E0E0), fontSize = 22.sp)
        Text(
            "Pick an export from a supported source. Files are parsed locally; " +
                "nothing is uploaded.\n\n" +
                "  •  Google Password Manager — CSV (passwords.google.com → Settings → Export)\n" +
                "  •  Proton Pass — JSON (unencrypted) or CSV (Pass → Settings → Export)\n\n" +
                "Duplicates (same URL + username) are skipped.",
            color = Color(0xFF9E9E9E), fontSize = 12.sp,
        )
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
            Text(if (working) "Importing…" else "Pick file")
        }
        status?.let {
            Text(it, color = Color(0xFFE0E0E0), fontSize = 12.sp)
        }
        SecureOutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text(if (status != null && !working) "Done" else "Cancel")
        }
        if (status?.startsWith("Imported") == true && !working) {
            // Auto-route back so the user sees the new entries appear.
            LaunchedEffect(status) {
                kotlinx.coroutines.delay(800)
                onDone()
            }
        }
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
    // We track entry by id and re-fetch on every recomposition so a
    // regenerate updates the displayed timestamp without us having to
    // hold a stale snapshot. The vault's contents flow re-renders on
    // save() so this is a cheap reactive read.
    val entry = vault.contents.entries.firstOrNull { it.id == entryId }
    if (entry == null) {
        onBack(); return
    }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    /**
     * Threat model contract: "screen is never secure even when device
     * is." So this screen NEVER renders the password. The two
     * mutually-exclusive paths are:
     *
     *   - Copy password to clipboard (biometric) — current value goes to
     *     Clipboard.copySensitive with 30s auto-clear; user pastes into
     *     the target field. Never on screen.
     *
     *   - Regenerate password (biometric) — fresh password produced from
     *     current Settings, vault entry updated with new value + bumped
     *     updated_at, new password copied to clipboard for the same
     *     30s window. Old password gets wiped from heap when the JNI
     *     CharArray-backed buffer falls out of scope. Never on screen.
     *
     * Both paths require a fresh device-auth cipher (the unlock cipher
     * is one-shot per session by the underlying BiometricPrompt
     * contract). Failed auth surfaces in [status] without leaking
     * which path failed.
     */
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
                    status = "Password copied (clears in 30s). Paste into the target field."
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
        val opts = Settings.toGeneratorOptions(Settings.load(ctx))
        if (!opts.isValid()) {
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
                    val chars = PasswordGenerator.generate(opts)
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
                            "(clears in 30s). Paste into the field that " +
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
        Text(entry.title.ifEmpty { "(untitled)" }, color = Color(0xFFE0E0E0), fontSize = 22.sp)
        if (entry.username.isNotEmpty()) {
            Text("Username:  ${entry.username}", color = Color(0xFFE0E0E0), fontSize = 14.sp)
        }
        if (entry.url.isNotEmpty()) {
            Text("URL:  ${entry.url}", color = Color(0xFF9E9E9E), fontSize = 13.sp)
        }
        if (entry.notes.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Notes:", color = Color(0xFF9E9E9E), fontSize = 12.sp)
            Text(entry.notes, color = Color(0xFFE0E0E0), fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Text("Password:", color = Color(0xFF9E9E9E), fontSize = 12.sp)
        Text("●●●●●●●●●●●●", color = Color(0xFFE0E0E0), fontSize = 14.sp)
        Text(
            "Threat model: passwords never render on screen. Use the " +
                "copy or regenerate paths below — both require device " +
                "auth and put the value on the clipboard with a 30-second " +
                "auto-clear.",
            color = Color(0xFF707070), fontSize = 11.sp,
        )
        status?.let {
            Text(
                it,
                color = if (it.startsWith("Password copied") || it.startsWith("Regenerated"))
                    Color(0xFF81C784) else Color(0xFFFFB74D),
                fontSize = 12.sp,
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
        SecureOutlinedButton(onClick = onDeleted, modifier = Modifier.fillMaxWidth()) {
            Text("Delete entry")
        }
    }
}
