package com.understory.passgen

import com.understory.security.A11yProbe
import com.understory.security.Clipboard
import com.understory.security.DeviceProfile
import com.understory.security.Diagnostics
import com.understory.security.DiagnosticsDump
import com.understory.security.DiagnosticsScreen
import com.understory.security.KeepAliveBackHandler
import com.understory.security.SecureButton
import com.understory.security.SecureOutlinedButton
import com.understory.security.SuitePins
import com.understory.security.Tamper
import com.understory.security.TestingMode

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Eng-build only: opens the rolling diagnostic dump in
        // /Documents/understory/passgen.log on first call this process,
        // mirrors every Diagnostics.log() to disk thereafter. No-op in
        // production builds (package name doesn't end in ".eng").
        DiagnosticsDump.activateIfEng(this)
        Diagnostics.log("passgen.MainActivity", "onCreate (savedInstanceState=${savedInstanceState != null})")
        super.onCreate(savedInstanceState)

        // CATCH-ALL diagnostic wrapper. If anything below throws, we render a
        // diagnostic screen instead of dying silently. Other entry points
        // (autofill / IME / fill activity) keep silent hard-fail; this one
        // becomes verbose so a failing device tells us what's wrong.
        try {
            initialize(savedInstanceState)
        } catch (t: Throwable) {
            Diagnostics.error("passgen.MainActivity",
                "onCreate threw: ${t.javaClass.simpleName}: ${t.message}")
            renderDiagnostic("UNCAUGHT EXCEPTION", listOf(t.javaClass.name + ": " + t.message), t)
        }
    }

    override fun onPause() {
        super.onPause()
        Diagnostics.log("passgen.MainActivity", "onPause")
        DiagnosticsDump.snapshotState(this, "onPause")
    }

    override fun onStop() {
        super.onStop()
        Diagnostics.log("passgen.MainActivity", "onStop (changingConfigs=$isChangingConfigurations)")
        DiagnosticsDump.snapshotState(this, "onStop")
    }

    override fun onDestroy() {
        super.onDestroy()
        Diagnostics.log("passgen.MainActivity", "onDestroy")
    }

    private fun initialize(savedInstanceState: Bundle?) {
        // Block screenshots, screen recording, casting and external display
        // for the lifetime of the activity. TestingMode.ALLOW_SCREENSHOTS
        // skips this so we can screenshot the diagnostic surface during
        // testing; RELEASE-BLOCKER to flip back (see RELEASE_BLOCKERS.md).
        if (!TestingMode.ALLOW_SCREENSHOTS) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }

        // Block other apps from drawing system overlays on top of us. (API 31+.)
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

        runCatching { WindowCompat.setDecorFitsSystemWindows(window, false) }

        // Production hard-fail: silent exit on any tamper / debugger / sibling
        // attestation failure. No diagnostic info leaks to the screen — an
        // attacker iterating against a check shouldn't get feedback. Uncaught
        // exceptions still surface (via the catch in onCreate) because those
        // indicate a real bug.
        val debuggerAttached = Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        if (debuggerAttached ||
            Tamper.check(applicationContext).hardFail ||
            com.understory.security.SuiteAttestation.verify(applicationContext).hardFail
        ) {
            finishAndRemoveTask()
            return
        }

        // ACTION_VIEW with a content URI lands here when the user picked a
        // CSV/JSON file from a file manager / Drive and chose passgen from
        // the "Open with…" dialog. Forward the URI to VaultActivity so the
        // import path runs through the normal biometric unlock + explicit
        // confirmation, then finish ourselves so the generator UI never
        // briefly flashes before we navigate away.
        val incomingUri = if (intent?.action == Intent.ACTION_VIEW) intent?.data else null
        if (incomingUri != null) {
            Diagnostics.log("passgen.MainActivity",
                "ACTION_VIEW received — forwarding to VaultActivity")
            val forward = Intent(this, VaultActivity::class.java).apply {
                putExtra(VaultActivity.EXTRA_PENDING_IMPORT_URI, incomingUri)
                // Carry forward the per-URI read grant the system gave us
                // for `intent.data`. Without this flag the receiving
                // activity gets a SecurityException when it tries to open
                // the URI, even though the user just authorized it.
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(forward)
            finish()
            return
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    PassgenRoot()
                }
            }
        }

        // Note: lifted `window.decorView.filterTouchesWhenObscured = true`
        // per SAMSUNG_QUIRKS.md — the global decor filter silently drops
        // legitimate taps under Samsung Edge Panel and similar overlays.
        // Per-control SecureButton wrappers still gate the destructive paths
        // (Generate / Save to vault / Copy to clipboard / Reveal). FLAG_SECURE
        // on the window still prevents screenshot / overlay capture.
    }

    private fun renderDiagnostic(title: String, reasons: List<String>, t: Throwable?) {
        // INVARIANT: nothing rendered on this screen may include any password
        // value (generated or vault-stored). FLAG_SECURE is cleared at the end
        // of this method so the user can screenshot the diagnostic. The only
        // strings rendered are: device fingerprint, installer source name,
        // Build.TAGS, two cert digests, and a Throwable's class+message+stack
        // — none of which are paths the password value can reach. Future
        // edits MUST preserve this. If you need to add a field, audit the
        // values it can carry.
        val device = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
        val installer = runCatching {
            packageManager.getInstallSourceInfo(packageName).installingPackageName ?: "(none)"
        }.getOrDefault("(unknown)")
        val sigDigest = runCatching {
            val pi = packageManager.getPackageInfo(
                packageName,
                android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
            )
            val info = pi.signingInfo!!
            val sigs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            sigs.firstOrNull()?.let {
                java.security.MessageDigest.getInstance("SHA-256")
                    .digest(it.toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
            } ?: "(no signers)"
        }.getOrElse { "(error: ${it.message})" }
        val tagsValue = Build.TAGS ?: "(null)"

        val stack = t?.let {
            val sw = java.io.StringWriter()
            it.printStackTrace(java.io.PrintWriter(sw))
            sw.toString()
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("passgen — diagnostic", color = Color(0xFFEF5350), fontSize = 22.sp)
                        Text(title, color = Color(0xFFFFB74D), fontSize = 16.sp)
                        for (r in reasons) {
                            Text("• $r", color = Color(0xFFE0E0E0), fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Device:  $device", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                        Text("Installer source:  $installer", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                        Text("Build.TAGS:  $tagsValue (expected: release-keys)", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                        Text("Cert digest (this install):", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                        Text(sigDigest, color = Color(0xFFE0E0E0), fontSize = 10.sp)
                        Text("Cert digest (expected):", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                        Text(SuitePins.EXPECTED_CERT_SHA256, color = Color(0xFFE0E0E0), fontSize = 10.sp)
                        if (stack != null) {
                            Spacer(Modifier.height(8.dp))
                            Text("Stack trace:", color = Color(0xFF9E9E9E), fontSize = 11.sp)
                            Text(stack, color = Color(0xFFE0E0E0), fontSize = 10.sp)
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Send a screenshot of this screen back to Claude so we can fix it. (FLAG_SECURE is OFF on the diagnostic screen so screenshots will work here.)",
                            color = Color(0xFF707070),
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
        // Diagnostic screen needs to be screenshot-able so user can send it back.
        runCatching {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * The instant the user explicitly backgrounds the app (home, switcher,
     * app-to-app navigation) we finish the activity. We deliberately do NOT
     * do this in onPause — onPause fires for transient events (incoming
     * notifications, system dialogs) and we'd kill the auto-clear timer.
     * The clipboard-clear Handler is app-process-scoped, so it survives this
     * activity's death as long as the process lives.
     */
    /**
     * NOTE: previously had `onUserLeaveHint() { finishAndRemoveTask() }`
     * here. Removed because GeneratorScreen is stateless — no vault, no
     * unlocked KEK, no transient secrets to protect by destroying the
     * activity on user-leave. Worse, the override fired when the user
     * tapped "Open vault" (any user-initiated activity launch triggers
     * onUserLeaveHint on the leaving activity), and `finishAndRemoveTask`
     * removed the WHOLE task — taking down VaultActivity that had just
     * been added to the same task by the manifest's standard launchMode.
     * Symptom: tapping Open vault closed the app. The clipboard auto-
     * clear Handler is process-scoped so it survives this activity's
     * normal lifecycle without any destroy-on-leave action.
     */

    /**
     * Re-check Tamper on resume so a hostile package install during the
     * background interval (Lucky Patcher / Frida / Xposed) is observed
     * before the user's next interaction. Aegis and firewall both do
     * this; passgen had been missing it. The 5s Tamper cache was
     * absorbing post-resume changes for up to 5s after foreground.
     */
    override fun onResume() {
        super.onResume()
        Diagnostics.log("passgen.MainActivity", "onResume")
        Tamper.invalidate()
        if (Tamper.check(applicationContext).hardFail) {
            Diagnostics.error("passgen.MainActivity", "Tamper.check hardFail on resume — finishing")
            finishAndRemoveTask()
        }
    }
}

private enum class PassgenRoute { Generator, Diagnostics }

@Composable
private fun PassgenRoot() {
    var routeName by rememberSaveable { mutableStateOf(PassgenRoute.Generator.name) }
    val route = remember(routeName) { PassgenRoute.valueOf(routeName) }
    val setRoute: (PassgenRoute) -> Unit = {
        Diagnostics.log("passgen.Root", "route transition: $routeName → ${it.name}")
        routeName = it.name
    }
    val backToGenerator: () -> Unit = { setRoute(PassgenRoute.Generator) }
    when (route) {
        PassgenRoute.Generator -> {
            // Back at root: minimize to background under
            // TestingMode.KEEP_ALIVE_ON_LEAVE so the user can switch apps
            // and come back. No-op for release (BackHandler not installed,
            // system default finish runs).
            KeepAliveBackHandler("passgen.Root")
            GeneratorScreen(
                onDiagnostics = { setRoute(PassgenRoute.Diagnostics) },
            )
        }
        PassgenRoute.Diagnostics -> {
            BackHandler { backToGenerator() }
            DiagnosticsScreen(onBack = backToGenerator)
        }
    }
}

@Composable
private fun GeneratorScreen(onDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val initial = remember { Settings.load(context) }

    var length by remember { mutableIntStateOf(initial.length) }
    var lengthText by remember { mutableStateOf(initial.length.toString()) }
    var lowers by remember { mutableStateOf(initial.lowers) }
    var uppers by remember { mutableStateOf(initial.uppers) }
    var digits by remember { mutableStateOf(initial.digits) }
    var symbols by remember { mutableStateOf(initial.symbols) }
    var autoClearOn by remember { mutableStateOf(initial.clearOn) }
    var autoClearSecondsText by remember { mutableStateOf(initial.clearSeconds.toString()) }

    LaunchedEffect(Unit) {
        snapshotFlow { length }.collect { lengthText = it.toString() }
    }

    // Persist any change. Settings hold no secrets, only generation shape.
    DisposableEffect(length, lowers, uppers, digits, symbols, autoClearOn, autoClearSecondsText) {
        Settings.save(
            context,
            Settings.Snapshot(
                length = length,
                lowers = lowers,
                uppers = uppers,
                digits = digits,
                symbols = symbols,
                clearOn = autoClearOn,
                clearSeconds = autoClearSecondsText.toIntOrNull()?.takeIf { it > 0 } ?: 30,
            ),
        )
        onDispose {}
    }

    val anyEnabled = lowers || uppers || digits || symbols
    val canGenerate = anyEnabled && length in 1..1000

    val autofillManager = remember { context.getSystemService(AutofillManager::class.java) }
    var autofillEnabled by remember {
        mutableStateOf(autofillManager?.hasEnabledAutofillServices() == true)
    }
    val imeManager = remember { context.getSystemService(InputMethodManager::class.java) }
    var imeEnabled by remember {
        mutableStateOf(isPassgenImeEnabled(context, imeManager))
    }
    var a11yState by remember { mutableStateOf(A11yProbe.check(context)) }
    val tamperReport = remember { Tamper.check(context) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = "passgen",
            color = Color(0xFFE0E0E0),
            fontSize = 28.sp,
        )
        Text(
            text = "Hardened password generator. The password value never appears on screen.",
            color = Color(0xFF9E9E9E),
            fontSize = 13.sp,
        )

        if (tamperReport.warnings.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "⚠  Device integrity warnings:",
                color = Color(0xFFFFB74D),
                fontSize = 12.sp,
            )
            for (w in tamperReport.warnings) {
                Text(
                    "    • $w",
                    color = Color(0xFFFFB74D),
                    fontSize = 11.sp,
                )
            }
            Text(
                "Generation still works, but on a rooted device the threat surface is much larger. Hard-fail conditions (Lucky Patcher, Xposed, Frida, repackage) abort the app entirely.",
                color = Color(0xFF707070),
                fontSize = 11.sp,
            )
        }

        if (a11yState.activeServiceCount > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                "⚠  ${a11yState.activeServiceCount} third-party accessibility service(s) active. " +
                    "Accessibility services can read text on screen and inject taps. " +
                    "Tap to review.",
                color = Color(0xFFFFB74D),
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth(),
            )
            SecureOutlinedButton(
                onClick = { A11yProbe.openA11ySettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Review accessibility services")
            }
        }

        Spacer(Modifier.height(8.dp))

        Text("Length: $length", color = Color(0xFFE0E0E0))
        Slider(
            value = length.toFloat(),
            onValueChange = { length = it.toInt().coerceIn(1, 1000) },
            valueRange = 1f..1000f,
            steps = 0,
        )

        OutlinedTextField(
            value = lengthText,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() }.take(4)
                lengthText = cleaned
                cleaned.toIntOrNull()?.let { length = it.coerceIn(1, 1000) }
            },
            label = { Text("Exact length (1–1000)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))

        ToggleRow("Lowercase  a–z", lowers) { lowers = it }
        ToggleRow("Uppercase  A–Z", uppers) { uppers = it }
        ToggleRow("Digits  0–9", digits) { digits = it }
        ToggleRow("Symbols  !@#…", symbols) { symbols = it }

        Spacer(Modifier.height(8.dp))

        ToggleRow("Auto-clear clipboard", autoClearOn) { autoClearOn = it }
        if (autoClearOn) {
            OutlinedTextField(
                value = autoClearSecondsText,
                onValueChange = { raw ->
                    autoClearSecondsText = raw.filter { it.isDigit() }.take(4)
                },
                label = { Text("Seconds before clear") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Recommended:  Autofill",
            color = Color(0xFFE0E0E0),
            fontSize = 16.sp,
        )

        if (DeviceProfile.isSamsung()) {
            // Samsung exposes a primary + additional autofill slot pair. Best
            // practice is: keep your existing password manager (Samsung Pass,
            // Bitwarden, 1Password, Google) in the primary slot, add passgen
            // as the additional service. Both respond when you focus a field,
            // so passgen's "generate" suggestion appears alongside your
            // existing manager's saved entries.
            Text(
                "Samsung mode:  keep your existing password manager (Samsung Pass / Bitwarden / Google) in the Primary slot. Add passgen in the Additional slot. Both will respond to password fields — your saved entries plus passgen's 'generate' option.",
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp,
            )
            SecureOutlinedButton(
                onClick = {
                    runCatching {
                        // Samsung's autofill page lives under General Management.
                        // The standard ACTION_SETTINGS reliably lands there; the
                        // ACTION_REQUEST_SET_AUTOFILL_SERVICE intent only
                        // controls the Primary slot, which is the wrong slot
                        // for our coexistence flow.
                        val intent = Intent("android.settings.AUTOFILL_SETTINGS")
                        context.startActivity(intent)
                    }.onFailure {
                        runCatching {
                            context.startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open autofill settings → use 'Additional service' slot")
            }
            Text(
                "    Path:  Settings → General management → Passwords, autofill and personal data → Autofill service → tap 'Additional service' → pick passgen.",
                color = Color(0xFF707070),
                fontSize = 11.sp,
            )
            // Provide an escape hatch for users who want passgen as the only
            // autofill provider after all (uninstalled their old manager, etc.).
            SecureOutlinedButton(
                onClick = {
                    runCatching {
                        val intent = Intent(AndroidSettings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                            data = Uri.parse("package:" + context.packageName)
                        }
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (autofillEnabled) "Autofill primary = passgen — change" else "Or: set passgen as PRIMARY (replaces existing)")
            }
        } else {
            // Stock Android / Pixel / OnePlus / Xiaomi / etc.: ONE autofill
            // provider at a time. The user has to choose between (a) replacing
            // their existing manager with passgen as the active autofill, or
            // (b) using passgen via the IME path or Credential Manager
            // (Android 14+) to coexist with their existing manager.
            Text(
                "Standard mode:  Android lets only one autofill provider be active at a time. To coexist with your existing password manager (Bitwarden, 1Password, Google), use Credential Manager (Android 14+) or the Custom keyboard mode below — neither competes for the autofill slot.",
                color = Color(0xFF9E9E9E),
                fontSize = 12.sp,
            )
            SecureOutlinedButton(
                onClick = {
                    runCatching {
                        val intent = Intent(AndroidSettings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                            data = Uri.parse("package:" + context.packageName)
                        }
                        context.startActivity(intent)
                    }.onFailure {
                        runCatching {
                            context.startActivity(Intent(AndroidSettings.ACTION_SETTINGS))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (autofillEnabled) "Autofill enabled — change provider" else "Set passgen as autofill provider")
            }

            Text(
                "    Note: Android's Credential Manager API isn't a fit here — it's designed for storage providers (Google / Bitwarden) acknowledging passwords the app already chose. There's no standard 'ask provider to generate' flow. The Custom keyboard mode below is the universal coexistence path.",
                color = Color(0xFF707070),
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Vault:  encrypted local password database",
            color = Color(0xFFE0E0E0),
            fontSize = 16.sp,
        )
        Text(
            "Argon2id + AES-256-GCM + Android Keystore device-binding. The vault self-generates a 256-bit master key at first run, self-encrypts it under your device's screen-lock-bound Keystore key, and never displays it. Unlock and per-entry reveal both require device biometric / PIN. No typed master, no typed reveal-lock.",
            color = Color(0xFF9E9E9E),
            fontSize = 12.sp,
        )
        SecureOutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(context, VaultActivity::class.java))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Open vault")
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Alternative:  Custom keyboard",
            color = Color(0xFFE0E0E0),
            fontSize = 16.sp,
        )
        Text(
            "Strongest path. Switch to passgen as your keyboard on a password field, tap Generate, the password is typed directly into the field. Bypasses clipboard AND autofill IPC. Works in apps that block autofill.",
            color = Color(0xFF9E9E9E),
            fontSize = 12.sp,
        )
        SecureOutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (imeEnabled) "Keyboard enabled — manage input methods" else "Enable passgen as keyboard")
        }
        if (imeEnabled) {
            SecureOutlinedButton(
                onClick = {
                    runCatching {
                        imeManager?.showInputMethodPicker()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Switch to passgen keyboard now")
            }
            Text(
                "The picker only shows when an input field is currently focused. If it doesn't appear, focus a password field first then return here.",
                color = Color(0xFF707070),
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(16.dp))

        Text(
            "Fallback:  Clipboard copy",
            color = Color(0xFFE0E0E0),
            fontSize = 16.sp,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF3D2A00), RoundedCornerShape(6.dp))
                .padding(10.dp),
        ) {
            Text(
                "⚠  CLIPBOARD CAN LEAK ON SAMSUNG / GBOARD\n\n" +
                    "Even with the sensitive-content flag, both Samsung Keyboard and Gboard maintain a private clipboard panel that snapshots clipboard changes into a separate store. The auto-clear timer cannot remove entries from those panels — only the keyboard's own UI can. On Samsung this is the long-press paste menu / clipboard panel; on Gboard it's the clipboard chip.\n\n" +
                    "Prefer Custom keyboard or Autofill modes on Samsung devices.",
                color = Color(0xFFFFB74D),
                fontSize = 11.sp,
            )
        }
        SecureButton(
            onClick = {
                val opts = PasswordGenerator.Options(
                    length = length,
                    lowers = lowers,
                    uppers = uppers,
                    digits = digits,
                    symbols = symbols,
                )
                val chars = PasswordGenerator.generate(opts)
                try {
                    val seconds = if (autoClearOn) {
                        autoClearSecondsText.toIntOrNull()?.takeIf { it > 0 } ?: 30
                    } else null
                    Clipboard.copySensitive(context, chars, seconds)
                    val msg = if (seconds != null) {
                        "Copied ($length chars). Auto-clear in ${seconds}s."
                    } else {
                        "Copied ($length chars)."
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } finally {
                    PasswordGenerator.wipe(chars)
                }
            },
            enabled = canGenerate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(),
        ) {
            Text("Generate & Copy (clipboard)")
        }

        if (!anyEnabled) {
            Text(
                "Enable at least one character set.",
                color = Color(0xFFEF5350),
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            "Hardening: FLAG_SECURE blocks screenshots and screen recording. The app holds no INTERNET, SMS, telephony, satellite, Bluetooth, NFC, contacts, or location permissions — verifiable in the install dialog.",
            color = Color(0xFF707070),
            fontSize = 11.sp,
        )

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text("Diagnostics")
        }
        com.understory.security.SuiteStatusFooter()
    }

    // Re-read autofill / IME / a11y state on every ON_START. The
    // previous LaunchedEffect(Unit) only fired once per composition
    // lifetime, never tracking actual lifecycle resumes — so a user who
    // backgrounds the app to enable our autofill/IME in system settings
    // and returned would see stale "not enabled" badges. firewall has
    // the same pattern; matching it here.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                autofillEnabled = autofillManager?.hasEnabledAutofillServices() == true
                imeEnabled = isPassgenImeEnabled(context, imeManager)
                a11yState = A11yProbe.check(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

private fun isPassgenImeEnabled(
    ctx: android.content.Context,
    imm: InputMethodManager?,
): Boolean {
    val mgr = imm ?: return false
    return mgr.enabledInputMethodList.any {
        it.packageName == ctx.packageName
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color(0xFFE0E0E0))
        Switch(checked = value, onCheckedChange = onChange)
    }
}
