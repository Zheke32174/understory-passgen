package com.understory.passgen

import com.understory.security.A11yProbe
import com.understory.security.Clipboard
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.understory.security.ui.components.SuiteScaffold
import com.understory.security.ui.components.FatalScreen
import com.understory.security.ui.components.SwitchRow
import com.understory.security.ui.components.SliderRow
import com.understory.security.ui.theme.UnderstoryAccent
import com.understory.security.ui.theme.UnderstoryTheme

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
            UnderstoryTheme(accent = UnderstoryAccent.PASSGEN) {
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

        // Build the (secret-free) detail block for FatalScreen's expandable
        // section. INVARIANT preserved: only device fingerprint, installer,
        // Build.TAGS, two cert digests, and the throwable's stack — no path a
        // password value can reach.
        val details = buildString {
            appendLine("Title:  $title")
            for (r in reasons) appendLine("• $r")
            appendLine()
            appendLine("Device:  $device")
            appendLine("Installer source:  $installer")
            appendLine("Build.TAGS:  $tagsValue (expected: release-keys)")
            appendLine("Cert digest (this install):")
            appendLine(sigDigest)
            appendLine("Cert digest (expected):")
            appendLine(SuitePins.EXPECTED_CERT_SHA256)
            if (stack != null) {
                appendLine()
                appendLine("Stack trace:")
                appendLine(stack)
            }
            appendLine()
            append("Send a screenshot of this screen back to Claude so we can fix it. (FLAG_SECURE is OFF on the diagnostic screen so screenshots will work here.)")
        }

        setContent {
            UnderstoryTheme(accent = UnderstoryAccent.PASSGEN) {
                FatalScreen(
                    title = stringResource(R.string.title_diagnostic),
                    reason = stringResource(
                        R.string.fmt_diagnostic_reason,
                        title,
                    ),
                    details = details,
                )
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
    var keepGeneratedValue by remember { mutableStateOf(initial.keepGeneratedValue) }

    LaunchedEffect(Unit) {
        snapshotFlow { length }.collect { lengthText = it.toString() }
    }

    // Persist any change. Settings hold no secrets, only generation shape and
    // the keep-value preference (a boolean, not a password).
    DisposableEffect(length, lowers, uppers, digits, symbols, autoClearOn, autoClearSecondsText, keepGeneratedValue) {
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
                keepGeneratedValue = keepGeneratedValue,
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

    SuiteScaffold(title = stringResource(R.string.app_name)) { pad ->
      Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(pad)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UnderstoryTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.md),
    ) {
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        Text(
            text = stringResource(R.string.title_generator),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(R.string.msg_generator_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (tamperReport.warnings.isNotEmpty()) {
            Text(
                stringResource(R.string.msg_integrity_warnings_header),
                style = MaterialTheme.typography.bodyMedium,
                color = UnderstoryTheme.semantic.warning,
            )
            for (w in tamperReport.warnings) {
                Text(
                    stringResource(R.string.msg_integrity_warning_item, w),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UnderstoryTheme.semantic.warning,
                )
            }
            Text(
                stringResource(R.string.msg_integrity_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = UnderstoryTheme.semantic.dim,
            )
        }

        if (a11yState.activeServiceCount > 0) {
            Text(
                stringResource(R.string.msg_a11y_active, a11yState.activeServiceCount),
                style = MaterialTheme.typography.bodyMedium,
                color = UnderstoryTheme.semantic.warning,
                modifier = Modifier.fillMaxWidth(),
            )
            SecureOutlinedButton(
                onClick = { A11yProbe.openA11ySettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_review_a11y))
            }
        }

        SliderRow(
            label = stringResource(R.string.label_length),
            value = length.toFloat(),
            onValueChange = { length = it.toInt().coerceIn(1, 1000) },
            valueRange = 1f..1000f,
            valueText = stringResource(R.string.fmt_chars, length),
        )

        OutlinedTextField(
            value = lengthText,
            onValueChange = { raw ->
                val cleaned = raw.filter { it.isDigit() }.take(4)
                lengthText = cleaned
                cleaned.toIntOrNull()?.let { length = it.coerceIn(1, 1000) }
            },
            label = { Text(stringResource(R.string.label_exact_length)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SwitchRow(stringResource(R.string.label_lowercase), lowers, onCheckedChange = { lowers = it })
        SwitchRow(stringResource(R.string.label_uppercase), uppers, onCheckedChange = { uppers = it })
        SwitchRow(stringResource(R.string.label_digits), digits, onCheckedChange = { digits = it })
        SwitchRow(stringResource(R.string.label_symbols), symbols, onCheckedChange = { symbols = it })

        SwitchRow(stringResource(R.string.label_auto_clear), autoClearOn, onCheckedChange = { autoClearOn = it })
        if (autoClearOn) {
            OutlinedTextField(
                value = autoClearSecondsText,
                onValueChange = { raw ->
                    autoClearSecondsText = raw.filter { it.isDigit() }.take(4)
                },
                label = { Text(stringResource(R.string.label_seconds_before_clear)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // §5.1: "keep generated value" drives §2 receipts. Default off. Must be
        // armed BEFORE generating — the value is wiped immediately after delivery.
        SwitchRow(
            label = stringResource(R.string.label_keep_value),
            checked = keepGeneratedValue,
            onCheckedChange = { keepGeneratedValue = it },
            supporting = if (keepGeneratedValue)
                stringResource(R.string.msg_keep_value_on)
            else
                stringResource(R.string.msg_keep_value_off),
        )

        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))

        Text(
            stringResource(R.string.section_autofill),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )

        // §7.1: status-first. Lead with who holds the slot, not a "set passgen
        // as provider" CTA. hasEnabledAutofillServices() is true only when WE
        // hold it; Android's API only distinguishes "us or not us", so we never
        // claim to name the incumbent beyond "another provider".
        if (autofillEnabled) {
            Text(
                stringResource(R.string.msg_autofill_active),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                Text(stringResource(R.string.action_change_autofill))
            }
        } else {
            // §7.3: the Samsung dual-slot "Additional service" flow ships ONLY
            // behind a verified capability check. Until verified on-device it
            // returns false and we fall through to the always-true keyboard path.
            Text(
                stringResource(R.string.msg_autofill_other),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (supportsVerifiedDualAutofillSlots()) {
                Text(
                    stringResource(R.string.msg_autofill_dual_slot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Secondary, clearly-labeled — never the primary action.
            SecureOutlinedButton(
                onClick = {
                    // Only the documented intent that controls the primary slot;
                    // no undocumented "android.settings.AUTOFILL_SETTINGS" and no
                    // generic-Settings fallback that strands the user (§7.3.3).
                    runCatching {
                        val intent = Intent(AndroidSettings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
                            data = Uri.parse("package:" + context.packageName)
                        }
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_replace_autofill))
            }
        }

        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))

        Text(
            stringResource(R.string.section_ledger),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.msg_ledger_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecureOutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(context, VaultActivity::class.java))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_open_ledger))
        }

        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))

        Text(
            stringResource(R.string.section_keyboard),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            stringResource(R.string.msg_keyboard_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SecureOutlinedButton(
            onClick = {
                runCatching {
                    context.startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (imeEnabled) stringResource(R.string.action_manage_keyboard)
                else stringResource(R.string.action_enable_keyboard)
            )
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
                Text(stringResource(R.string.action_switch_keyboard))
            }
            Text(
                stringResource(R.string.msg_keyboard_picker_hint),
                style = MaterialTheme.typography.bodySmall,
                color = UnderstoryTheme.semantic.dim,
            )
        }
        // §6.4: honest known-limitation, not a silent gap.
        Text(
            stringResource(R.string.msg_keyboard_a11y_note),
            style = MaterialTheme.typography.bodySmall,
            color = UnderstoryTheme.semantic.dim,
        )

        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))

        Text(
            stringResource(R.string.section_clipboard),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Surface(
            color = UnderstoryTheme.semantic.warning.copy(alpha = 0.14f),
            contentColor = UnderstoryTheme.semantic.warning,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.msg_clipboard_leak_warning),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(UnderstoryTheme.spacing.md),
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
                val snap = Settings.load(context)
                val chars = PasswordGenerator.generate(opts)
                try {
                    val seconds = if (autoClearOn) {
                        autoClearSecondsText.toIntOrNull()?.takeIf { it > 0 } ?: 30
                    } else null
                    Clipboard.copySensitive(context, chars, seconds)
                    // §2.2: write a receipt for the clipboard generate path.
                    // Clipboard has no target field — targetKind="unknown".
                    runCatching {
                        Receipts.append(
                            context.applicationContext, "clipboard", "", "unknown", snap,
                            if (snap.keepGeneratedValue) chars else null,
                        )
                    }
                    // §5.2: honest auto-clear copy — the clear is a process-scoped
                    // Handler, so promise it only while passgen runs.
                    val msg = if (seconds != null) {
                        context.getString(R.string.fmt_copied_autoclear, length, seconds)
                    } else {
                        context.getString(R.string.fmt_copied, length)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } finally {
                    PasswordGenerator.wipe(chars)
                }
            },
            enabled = canGenerate,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(),
        ) {
            Text(stringResource(R.string.action_generate_copy))
        }

        if (!anyEnabled) {
            Text(
                stringResource(R.string.msg_enable_charset),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        Text(
            stringResource(R.string.msg_hardening),
            style = MaterialTheme.typography.bodySmall,
            color = UnderstoryTheme.semantic.dim,
        )

        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_diagnostics))
        }
        Spacer(Modifier.height(UnderstoryTheme.spacing.md))
      }
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

/**
 * §7.3: whether this device's system settings expose a verified "Additional
 * service" autofill slot that can hold passgen ALONGSIDE the incumbent.
 *
 * The dual-slot instructions were unverified on real One UI 7 (SM-S948U;
 * SAMSUNG_QUIRKS.md has no autofill entry). Rather than assert a settings path
 * that may not exist, this returns false until an on-device verification lands
 * (an operator action tracked in SAMSUNG_QUIRKS.md). Returning false makes the
 * UI fall through to the always-true keyboard-mode path — the safe channel —
 * instead of stranding the user with instructions that may be wrong.
 *
 * Kept as a function (not a hardcoded false) so flipping it after verification
 * is a one-line change with the capability check in one place.
 */
private fun supportsVerifiedDualAutofillSlots(): Boolean = false
