package com.understory.passgen

import com.understory.passgen.BuildConfig
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
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.understory.security.ui.components.EmptyState
import com.understory.security.ui.components.FatalScreen
import com.understory.security.ui.components.SuiteCard
import com.understory.security.ui.components.SuiteListRow
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
                    PassgenApp()
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
     * NOTE: previously had `onUserLeaveHint() { finishAndRemoveTask() }`
     * here. Removed because the generator is stateless — no vault, no
     * unlocked KEK, no transient secrets to protect by destroying the
     * activity on user-leave. Worse, the override fired when the user
     * tapped "Open ledger" (any user-initiated activity launch triggers
     * onUserLeaveHint on the leaving activity), and `finishAndRemoveTask`
     * removed the WHOLE task — taking down VaultActivity that had just
     * been added to the same task by the manifest's standard launchMode.
     * Symptom: tapping Open ledger closed the app. The clipboard auto-
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

/** Top-level destinations surfaced in the bottom [NavigationBar]. */
private enum class PassgenTab(
    val labelRes: Int,
    val cdRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    Generate(R.string.nav_generate, R.string.cd_nav_generate, Icons.Filled.Password, Icons.Outlined.Tune),
    Ledger(R.string.nav_ledger, R.string.cd_nav_ledger, Icons.Filled.Shield, Icons.Outlined.Key),
    Receipts(R.string.nav_receipts, R.string.cd_nav_receipts, Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong),
}

/**
 * The app shell: a Material3 [Scaffold] with a [TopAppBar] and a bottom
 * [NavigationBar] across the three top-level sections — Generate / Ledger /
 * Receipts. Replaces the old single scrolling Column of buttons.
 *
 * The shared [com.understory.security.SuiteStatusFooter] (a dim peer/tier
 * smoke-test strip) is deliberately NOT used as bottom chrome here: it reads as
 * a dev status bar. The bottom bar is the user-facing NavigationBar instead, and
 * a genuine one-line security statement sits at the base of the Generate tab.
 *
 * Diagnostics is an ENG-ONLY affordance: the top-bar bug-report action is only
 * present when `BuildConfig.FLAVOR == "eng"`, and the Diagnostics route is only
 * reachable through it. In a prod (shipping) build there is no diagnostics
 * affordance and [DiagnosticsScreen] is unreachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassgenApp() {
    val isEng = BuildConfig.FLAVOR == "eng"

    var tabName by rememberSaveable { mutableStateOf(PassgenTab.Generate.name) }
    val tab = remember(tabName) { PassgenTab.valueOf(tabName) }
    val setTab: (PassgenTab) -> Unit = {
        Diagnostics.log("passgen.App", "tab: $tabName → ${it.name}")
        tabName = it.name
    }

    // Eng-only diagnostics sub-route. Unreachable in prod (the entry action that
    // sets it is gated behind isEng).
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    if (showDiagnostics) {
        BackHandler { showDiagnostics = false }
        DiagnosticsScreen(onBack = { showDiagnostics = false })
        return
    }

    // Coexistence-truth sub-route (SHIPPING, not eng-gated): a small read-only
    // screen reachable from the Generate tab's delivery section. When elevated it
    // names the actual autofill / IME / a11y holders; rootless it keeps the
    // AutofillManager heuristic + a Shizuku grant invite. Read-only, no writes.
    var showCoexistence by rememberSaveable { mutableStateOf(false) }

    if (showCoexistence) {
        BackHandler { showCoexistence = false }
        CoexistenceScreen(onBack = { showCoexistence = false })
        return
    }

    // Back at a top-level tab: minimize under TestingMode.KEEP_ALIVE_ON_LEAVE so
    // the tester can switch apps and return. No-op for release.
    KeepAliveBackHandler("passgen.App")

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (isEng) {
                        // ENG-ONLY: the Diagnostics log surface never ships in prod.
                        IconButton(onClick = { showDiagnostics = true }) {
                            Icon(
                                imageVector = Icons.Filled.BugReport,
                                contentDescription = stringResource(R.string.cd_diagnostics),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                for (t in PassgenTab.entries) {
                    val selected = t == tab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { setTab(t) },
                        icon = {
                            Icon(
                                imageVector = if (selected) t.selectedIcon else t.unselectedIcon,
                                contentDescription = stringResource(t.cdRes),
                            )
                        },
                        label = { Text(stringResource(t.labelRes)) },
                    )
                }
            }
        },
    ) { pad ->
        when (tab) {
            PassgenTab.Generate -> GenerateTab(
                modifier = Modifier.padding(pad),
                onOpenCoexistence = { showCoexistence = true },
            )
            PassgenTab.Ledger -> LedgerTab(Modifier.padding(pad))
            PassgenTab.Receipts -> ReceiptsTab(Modifier.padding(pad))
        }
    }
}

@Composable
private fun GenerateTab(
    modifier: Modifier = Modifier,
    onOpenCoexistence: () -> Unit = {},
) {
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
    var mode by remember { mutableStateOf(initial.mode) }
    var minDigits by remember { mutableIntStateOf(initial.minDigits) }
    var minSymbols by remember { mutableIntStateOf(initial.minSymbols) }
    var avoidAmbiguous by remember { mutableStateOf(initial.avoidAmbiguous) }
    var words by remember { mutableIntStateOf(initial.words) }
    var separator by remember { mutableStateOf(initial.separator) }
    var capitalize by remember { mutableStateOf(initial.capitalize) }
    var includeNumber by remember { mutableStateOf(initial.includeNumber) }

    LaunchedEffect(Unit) {
        snapshotFlow { length }.collect { lengthText = it.toString() }
    }

    // The full recipe as currently configured on screen. Single source for
    // persistence, validity, the strength meter, and the Generate action — so
    // what the meter scores is exactly what the button generates.
    fun buildSnapshot() = Settings.Snapshot(
        length = length,
        lowers = lowers,
        uppers = uppers,
        digits = digits,
        symbols = symbols,
        clearOn = autoClearOn,
        clearSeconds = autoClearSecondsText.toIntOrNull()?.takeIf { it > 0 } ?: 30,
        keepGeneratedValue = keepGeneratedValue,
        mode = mode,
        minLowers = initial.minLowers,
        minUppers = initial.minUppers,
        minDigits = minDigits,
        minSymbols = minSymbols,
        avoidAmbiguous = avoidAmbiguous,
        excludeChars = initial.excludeChars,
        words = words,
        separator = separator,
        capitalize = capitalize,
        includeNumber = includeNumber,
    )

    // Persist any change. Settings hold no secrets, only generation shape and
    // the keep-value preference (a boolean, not a password).
    DisposableEffect(
        length, lowers, uppers, digits, symbols, autoClearOn, autoClearSecondsText,
        keepGeneratedValue, mode, minDigits, minSymbols, avoidAmbiguous,
        words, separator, capitalize, includeNumber,
    ) {
        Settings.save(context, buildSnapshot())
        onDispose {}
    }

    val anyEnabled = lowers || uppers || digits || symbols
    val snapshotNow = buildSnapshot()
    val canGenerate = Generate.isValid(snapshotNow)
    val entropyBits = Generate.entropyBits(snapshotNow)

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
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UnderstoryTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.md),
    ) {
        Spacer(Modifier.height(UnderstoryTheme.spacing.md))
        Text(
            text = stringResource(R.string.msg_generator_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Integrity / accessibility warnings, when present, lead the screen in a
        // warning-tinted card so they're not lost in the scroll.
        if (tamperReport.warnings.isNotEmpty() || a11yState.activeServiceCount > 0) {
            SuiteCard {
                if (tamperReport.warnings.isNotEmpty()) {
                    Text(
                        stringResource(R.string.msg_integrity_warnings_header),
                        style = MaterialTheme.typography.titleSmall,
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
                    if (tamperReport.warnings.isNotEmpty()) Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                    Text(
                        stringResource(R.string.msg_a11y_active, a11yState.activeServiceCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = UnderstoryTheme.semantic.warning,
                    )
                    Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                    SecureOutlinedButton(
                        onClick = { A11yProbe.openA11ySettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_review_a11y))
                    }
                }
            }
        }

        // --- Mode selector: random characters vs EFF passphrase. Drives every
        // delivery path (clipboard / keyboard / autofill / vault) via Settings. ---
        Row(
            horizontalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm),
            modifier = Modifier.fillMaxWidth(),
        ) {
            CharsetChip(
                stringResource(R.string.label_mode_password),
                mode == Settings.MODE_CHARS,
                Modifier.weight(1f),
            ) { mode = Settings.MODE_CHARS }
            CharsetChip(
                stringResource(R.string.label_mode_passphrase),
                mode == Settings.MODE_WORDS,
                Modifier.weight(1f),
            ) { mode = Settings.MODE_WORDS }
        }

        // --- Recipe group: length + character sets. The sanctioned SliderRow /
        // SwitchRow controls carry their own full-width padding, so this group is
        // a full-bleed Surface (GroupCard) rather than SuiteCard's padded Column;
        // text/field/chips inside get an explicit lg inset to align with them. ---
        if (mode == Settings.MODE_CHARS) GroupCard {
            GroupHeader(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.card_recipe_title),
                subtitle = stringResource(R.string.card_recipe_desc),
            )
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UnderstoryTheme.spacing.lg),
            )
            Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
            Text(
                stringResource(R.string.label_character_sets),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = UnderstoryTheme.spacing.lg),
            )
            Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
            Row(
                horizontalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UnderstoryTheme.spacing.lg),
            ) {
                CharsetChip(stringResource(R.string.label_lowercase), lowers, Modifier.weight(1f)) { lowers = it }
                CharsetChip(stringResource(R.string.label_uppercase), uppers, Modifier.weight(1f)) { uppers = it }
            }
            Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
            Row(
                horizontalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UnderstoryTheme.spacing.lg),
            ) {
                CharsetChip(stringResource(R.string.label_digits), digits, Modifier.weight(1f)) { digits = it }
                CharsetChip(stringResource(R.string.label_symbols), symbols, Modifier.weight(1f)) { symbols = it }
            }
            if (!anyEnabled) {
                Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
                Text(
                    stringResource(R.string.msg_enable_charset),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = UnderstoryTheme.spacing.lg),
                )
            }
            // Composition-rule knobs: sites that demand "at least N digits /
            // symbols" get a guaranteed-compliant password; ambiguous-character
            // avoidance for values a human may transcribe by eye.
            if (digits) {
                SliderRow(
                    label = stringResource(R.string.label_min_digits),
                    value = minDigits.toFloat(),
                    onValueChange = { minDigits = it.toInt().coerceIn(0, 9) },
                    valueRange = 0f..9f,
                    valueText = stringResource(R.string.fmt_at_least, minDigits),
                )
            }
            if (symbols) {
                SliderRow(
                    label = stringResource(R.string.label_min_symbols),
                    value = minSymbols.toFloat(),
                    onValueChange = { minSymbols = it.toInt().coerceIn(0, 9) },
                    valueRange = 0f..9f,
                    valueText = stringResource(R.string.fmt_at_least, minSymbols),
                )
            }
            SwitchRow(
                label = stringResource(R.string.label_avoid_ambiguous),
                checked = avoidAmbiguous,
                onCheckedChange = { avoidAmbiguous = it },
                supporting = stringResource(R.string.msg_avoid_ambiguous_sub),
            )
            Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
        }

        // --- Passphrase recipe: EFF large wordlist shape. ---
        if (mode == Settings.MODE_WORDS) GroupCard {
            GroupHeader(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.card_passphrase_title),
                subtitle = stringResource(R.string.card_passphrase_desc),
            )
            SliderRow(
                label = stringResource(R.string.label_word_count),
                value = words.toFloat(),
                onValueChange = { words = it.toInt().coerceIn(2, 20) },
                valueRange = 2f..20f,
                valueText = stringResource(R.string.fmt_words, words),
            )
            OutlinedTextField(
                value = separator,
                onValueChange = { raw ->
                    separator = raw.filterNot { it.isISOControl() }.take(3)
                },
                label = { Text(stringResource(R.string.label_separator)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = UnderstoryTheme.spacing.lg),
            )
            SwitchRow(
                label = stringResource(R.string.label_capitalize),
                checked = capitalize,
                onCheckedChange = { capitalize = it },
            )
            SwitchRow(
                label = stringResource(R.string.label_include_number),
                checked = includeNumber,
                onCheckedChange = { includeNumber = it },
                supporting = stringResource(R.string.msg_include_number_sub),
            )
            Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
        }

        // --- Live strength meter for the configured recipe. ---
        StrengthMeter(bits = entropyBits)

        // --- Options group: auto-clear + receipts keep-value. ---
        GroupCard {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = UnderstoryTheme.spacing.lg),
                )
                Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
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
        }

        // --- Clipboard leak caution + primary Generate action. ---
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
                // Persist first so the on-screen recipe is exactly what every
                // path (including this one) generates from.
                val snap = buildSnapshot()
                Settings.save(context, snap)
                val chars = Generate.fromSnapshot(snap)
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
                    // Handler, so promise it only while passgen runs. chars.size,
                    // not `length`: a passphrase's character count is derived.
                    val msg = if (seconds != null) {
                        context.getString(R.string.fmt_copied_autoclear, chars.size, seconds)
                    } else {
                        context.getString(R.string.fmt_copied, chars.size)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } finally {
                    Generate.wipe(chars)
                }
            },
            enabled = canGenerate,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            colors = ButtonDefaults.buttonColors(),
        ) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
            Spacer(Modifier.padding(end = UnderstoryTheme.spacing.sm))
            Text(stringResource(R.string.action_generate_copy))
        }

        // --- Delivery methods: autofill + keyboard, status-first. ---
        SuiteSectionHeaderInset(stringResource(R.string.card_delivery_title))
        Text(
            stringResource(R.string.card_delivery_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // §7.1: status-first. Lead with who holds the slot, not a "set passgen
        // as provider" CTA. hasEnabledAutofillServices() is true only when WE
        // hold it; Android's API only distinguishes "us or not us", so we never
        // claim to name the incumbent beyond "another provider".
        SuiteCard {
            Text(stringResource(R.string.section_autofill), style = MaterialTheme.typography.titleMedium)
            if (autofillEnabled) {
                Text(
                    stringResource(R.string.msg_autofill_active),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                SecureOutlinedButton(
                    onClick = { launchAutofillSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_change_autofill))
                }
            } else {
                // Another provider holds the primary slot. On Samsung One UI the
                // OS additionally exposes an "Additional autofill services" list;
                // passgen advertises itself for it (settingsActivity +
                // <compatibility-package> in autofill_service.xml). We surface the
                // dual-slot path FIRST there so the user can add passgen alongside
                // their existing manager without displacing it.
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
                    Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                    // Primary CTA on Samsung: open the system autofill settings
                    // where the "Additional autofill services" toggle lives. No
                    // undocumented Samsung deep-link exists, so we open the
                    // documented autofill settings screen and tell the user exactly
                    // where to tap (msg_autofill_dual_slot copy above).
                    SecureOutlinedButton(
                        onClick = { launchAdditionalAutofillSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.action_add_additional_autofill))
                    }
                    Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
                }
                Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                // Replacing the primary provider is the always-available fallback,
                // demoted below the dual-slot path on Samsung. Clearly labeled —
                // this one DOES displace the incumbent.
                SecureOutlinedButton(
                    onClick = { launchAutofillSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_replace_autofill))
                }
            }
        }

        SuiteCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Keyboard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.padding(end = UnderstoryTheme.spacing.sm))
                Text(stringResource(R.string.section_keyboard), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                stringResource(R.string.msg_keyboard_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
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
                Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                SecureOutlinedButton(
                    onClick = { runCatching { imeManager?.showInputMethodPicker() } },
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
        }

        // Coexistence-truth entry: a read-only look at who actually holds the
        // autofill / keyboard / accessibility slots. Rootless it shows the
        // AutofillManager heuristic + a Shizuku grant invite; elevated it names
        // the real holders. Whole-card tap navigates to CoexistenceScreen.
        SuiteCard(onClick = onOpenCoexistence) {
            Text(
                stringResource(R.string.coex_entry_label),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.coex_entry_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Genuine, user-facing one-line security statement — replaces the dim
        // dev-looking peer/tier status footer for the shipping face.
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Shield,
                contentDescription = null,
                tint = UnderstoryTheme.semantic.dim,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.padding(end = UnderstoryTheme.spacing.xs))
            Text(
                stringResource(R.string.msg_security_line),
                style = MaterialTheme.typography.bodySmall,
                color = UnderstoryTheme.semantic.dim,
            )
        }
        Spacer(Modifier.height(UnderstoryTheme.spacing.md))
    }

    // Re-read autofill / IME / a11y state on every ON_START. A user who
    // backgrounds the app to enable our autofill/IME in system settings and
    // returns would otherwise see stale "not enabled" badges. firewall has the
    // same pattern; matching it here.
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

/**
 * Live entropy readout for the configured recipe. Honest math only: the exact
 * combinatorial strength of the shape (see [PasswordGenerator.entropyBits] /
 * [PassphraseGenerator.entropyBits]) — never a zxcvbn-style guess flattering
 * the output. Renders bits + a qualitative band + a capped progress bar.
 */
@Composable
private fun StrengthMeter(bits: Double) {
    val label: String
    val color: androidx.compose.ui.graphics.Color
    when {
        bits < 45 -> { label = stringResource(R.string.strength_weak); color = MaterialTheme.colorScheme.error }
        bits < 70 -> { label = stringResource(R.string.strength_fair); color = UnderstoryTheme.semantic.warning }
        bits < 100 -> { label = stringResource(R.string.strength_strong); color = UnderstoryTheme.semantic.success }
        else -> { label = stringResource(R.string.strength_excellent); color = MaterialTheme.colorScheme.primary }
    }
    SuiteCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.label_strength), style = MaterialTheme.typography.titleMedium)
            Text(
                "$label · " + stringResource(R.string.fmt_entropy_bits, bits.toInt().toString()),
                style = MaterialTheme.typography.titleSmall,
                color = color,
            )
        }
        Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
        LinearProgressIndicator(
            progress = { (bits / 128.0).toFloat().coerceIn(0f, 1f) },
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
        Text(
            stringResource(R.string.msg_entropy_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = UnderstoryTheme.semantic.dim,
        )
    }
}

/**
 * A full-bleed grouping surface for the sanctioned SliderRow / SwitchRow
 * controls, which carry their own horizontal padding. Unlike [SuiteCard] (which
 * pads its whole Column), this pads only vertically so those controls sit flush;
 * plain text/fields inside add their own horizontal inset to line up.
 */
@Composable
private fun GroupCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = UnderstoryTheme.spacing.sm),
            content = content,
        )
    }
}

@Composable
private fun GroupHeader(icon: ImageVector, title: String, subtitle: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(
            start = UnderstoryTheme.spacing.lg,
            end = UnderstoryTheme.spacing.lg,
            bottom = UnderstoryTheme.spacing.sm,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.padding(end = UnderstoryTheme.spacing.sm))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CharsetChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onToggle: (Boolean) -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(label) },
        modifier = modifier,
    )
}

/** Section header without the shared component's start inset (already in a padded Column). */
@Composable
private fun SuiteSectionHeaderInset(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = UnderstoryTheme.spacing.sm),
    )
}

@Composable
private fun LedgerTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = UnderstoryTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.md),
        ) {
            Spacer(Modifier.height(UnderstoryTheme.spacing.md))
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
                Text(
                    stringResource(R.string.ledger_landing_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                stringResource(R.string.ledger_landing_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SuiteCard {
                LedgerFeatureRow(
                    Icons.Filled.Password,
                    stringResource(R.string.ledger_feature_import),
                    stringResource(R.string.ledger_feature_import_sub),
                )
                LedgerFeatureRow(
                    Icons.Filled.ReceiptLong,
                    stringResource(R.string.ledger_feature_receipts),
                    stringResource(R.string.ledger_feature_receipts_sub),
                )
                LedgerFeatureRow(
                    Icons.Filled.Shield,
                    stringResource(R.string.ledger_feature_handoff),
                    stringResource(R.string.ledger_feature_handoff_sub),
                )
            }

            SecureButton(
                onClick = { openLedger(context, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Icon(Icons.Outlined.Key, contentDescription = null)
                Spacer(Modifier.padding(end = UnderstoryTheme.spacing.sm))
                Text(stringResource(R.string.action_open_ledger))
            }
            Spacer(Modifier.height(UnderstoryTheme.spacing.xxl))
        }

        // Primary create action for the ledger section.
        FloatingActionButton(
            onClick = { openLedger(context, VaultActivity.EXTRA_START_ADD) },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(UnderstoryTheme.spacing.lg),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.cd_add_entry))
        }
    }
}

@Composable
private fun LedgerFeatureRow(icon: ImageVector, title: String, subtitle: String) {
    SuiteListRow(
        headline = title,
        supporting = subtitle,
        leading = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
}

@Composable
private fun ReceiptsTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Unclaimed count is the cheap, sanctioned NON-SECRET metadata read (a plain
    // SharedPreferences int; see Receipts.unclaimedCount). This landing never
    // decrypts receipts — the actual list (behind its own real empty/loading
    // states) lives in the unlock-gated ReceiptsScreen. Re-read on each entry.
    val unclaimed = remember { runCatching { Receipts.unclaimedCount(context) }.getOrDefault(0) }

    // Nothing waiting → a real EmptyState (still offers a route to review any
    // already-handled receipts). The unclaimed counter is the only signal read
    // here; no decrypt happens on this launcher landing.
    if (unclaimed == 0) {
        EmptyState(
            title = stringResource(R.string.receipts_empty_title),
            body = stringResource(R.string.receipts_empty_body),
            icon = Icons.Outlined.ReceiptLong,
            modifier = modifier,
            action = {
                SecureOutlinedButton(onClick = { openLedger(context, VaultActivity.EXTRA_START_RECEIPTS) }) {
                    Text(stringResource(R.string.action_open_receipts))
                }
            },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = UnderstoryTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.md),
    ) {
        Spacer(Modifier.height(UnderstoryTheme.spacing.md))
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Outlined.ReceiptLong,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
            Text(
                stringResource(R.string.receipts_landing_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            stringResource(R.string.receipts_landing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SuiteCard {
            SuiteListRow(
                headline = stringResource(R.string.fmt_receipts_unclaimed, unclaimed),
                supporting = stringResource(R.string.receipts_unclaimed_sub),
                leading = {
                    Icon(
                        imageVector = Icons.Filled.ReceiptLong,
                        contentDescription = null,
                        tint = UnderstoryTheme.semantic.warning,
                    )
                },
            )
        }

        SecureButton(
            onClick = { openLedger(context, VaultActivity.EXTRA_START_RECEIPTS) },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(Icons.Filled.ReceiptLong, contentDescription = null)
            Spacer(Modifier.padding(end = UnderstoryTheme.spacing.sm))
            Text(stringResource(R.string.action_open_receipts))
        }
        Spacer(Modifier.height(UnderstoryTheme.spacing.md))
    }
}

/** Launch the unlock-gated ledger activity, optionally jumping to a sub-stage. */
private fun openLedger(context: android.content.Context, startExtra: String?) {
    runCatching {
        val intent = Intent(context, VaultActivity::class.java)
        if (startExtra != null) intent.putExtra(VaultActivity.EXTRA_START_DESTINATION, startExtra)
        context.startActivity(intent)
    }
}

private fun launchAutofillSettings(context: android.content.Context) {
    // Only the documented intent that controls the primary slot; no undocumented
    // "android.settings.AUTOFILL_SETTINGS" and no generic-Settings fallback that
    // strands the user (§7.3.3).
    runCatching {
        val intent = Intent(AndroidSettings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = Uri.parse("package:" + context.packageName)
        }
        context.startActivity(intent)
    }
}

/**
 * Open the system autofill settings screen that hosts Samsung One UI's
 * "Additional autofill services" list, so the user can add passgen as a
 * secondary service ALONGSIDE their existing primary provider.
 *
 * There is no public, documented Samsung intent that deep-links straight to the
 * "Additional autofill services" sub-screen, so we do NOT fabricate one. We open
 * the documented autofill settings surface; the in-app copy (msg_autofill_dual_slot)
 * tells the user to tap "More autofill services" / "Additional autofill services"
 * and enable Understory Keys there. If that action can't resolve on this device
 * we fall back to the primary-slot picker rather than stranding the user — the
 * OEM check upstream already limited this button to devices that expose the slot.
 */
private fun launchAdditionalAutofillSettings(context: android.content.Context) {
    // ACTION_REQUEST_SET_AUTOFILL_SERVICE lands on One UI's autofill service
    // screen, which is where the "Additional autofill services" entry lives; the
    // package-scoped data URI keeps the request tied to us. Same documented
    // intent as the primary path — the difference is honest on-screen guidance,
    // not an undocumented deep-link.
    val ok = runCatching {
        val intent = Intent(AndroidSettings.ACTION_REQUEST_SET_AUTOFILL_SERVICE).apply {
            data = Uri.parse("package:" + context.packageName)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    if (!ok) {
        // Degrade honestly: the documented autofill picker without a package URI.
        runCatching {
            context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SET_AUTOFILL_SERVICE))
        }
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
 * §7.3: whether this device's system settings expose an "Additional autofill
 * services" slot that can hold passgen ALONGSIDE the incumbent primary provider.
 *
 * Samsung One UI exposes this secondary slot; stock Android does not. passgen now
 * advertises itself for it correctly — the autofill service declares a settings
 * activity and a <compatibility-package> allowlist (res/xml/autofill_service.xml),
 * which is what makes One UI list it under "Additional autofill services". The
 * capability is delegated to [com.understory.security.DeviceProfile] so the OEM
 * check lives in one place shared across the suite.
 *
 * On a device that does NOT offer the secondary slot this returns false and the
 * UI degrades honestly: we don't show the dual-slot copy or button, and the
 * always-available keyboard path remains the coexistence channel. We never
 * fabricate a settings path that the OS doesn't provide.
 */
private fun supportsVerifiedDualAutofillSlots(): Boolean =
    com.understory.security.DeviceProfile.supportsDualAutofillSlots()
