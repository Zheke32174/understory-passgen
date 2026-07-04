package com.understory.passgen

import android.content.Intent
import android.provider.Settings as AndroidSettings
import android.view.autofill.AutofillManager
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.understory.elevation.Elevation
import com.understory.elevation.ui.ElevationCard
import com.understory.elevation.ui.rememberElevationState
import com.understory.security.Diagnostics
import com.understory.security.SecureOutlinedButton
import com.understory.security.ui.components.SuiteCard
import com.understory.security.ui.components.SuiteScaffold
import com.understory.security.ui.theme.UnderstoryTheme

/**
 * The "Coexistence truth" surface (READ-ONLY).
 *
 * Two honest postures, gated on [rememberElevationState]:
 *
 *  - ELEVATED (canRunShell): shows the NAMED truth read by [CoexistenceTruth] —
 *    who actually holds the autofill slot, which IME is active, and which
 *    accessibility services are live, flagging any THIRD-PARTY a11y service (the
 *    real on-screen-scrape risk).
 *  - ROOTLESS (default): keeps the existing [AutofillManager] heuristic ("us or
 *    not us") + IME-enabled check, plus an [ElevationCard] invite that routes to
 *    the Shizuku grant flow. NEVER a dead control — the invite is the shared
 *    card's real grant button, and the fallback line deep-links to Settings.
 *
 * Fail-open: if the elevated read comes back empty (revoked mid-flight, bind
 * failure) [CoexistenceTruth.Report.elevatedRead] is false and we render the
 * rootless posture rather than an all-unknown truth card.
 */
@Composable
fun CoexistenceScreen(onBack: () -> Unit) {
    SuiteScaffold(
        title = stringResource(R.string.coex_title),
        onBack = onBack,
        showSuiteFooter = false,
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = UnderstoryTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(UnderstoryTheme.spacing.md),
        ) {
            Spacer(Modifier.height(UnderstoryTheme.spacing.md))
            Text(
                stringResource(R.string.coex_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val elevation = rememberElevationState()
            if (elevation.isElevated && Elevation.canRunShell(LocalContext.current)) {
                ElevatedTruthCard()
            } else {
                RootlessCoexistenceCard()
            }

            Spacer(Modifier.height(UnderstoryTheme.spacing.md))
        }
    }
}

/**
 * The elevated, named-truth card. Reads the three secure settings via
 * [CoexistenceTruth] off the main thread. While the read is in flight (or if it
 * degrades to not-elevated) we fall back to the rootless card so the surface is
 * never blank and never lies about elevation.
 */
@Composable
private fun ElevatedTruthCard() {
    val ctx = LocalContext.current
    val report by produceState<CoexistenceTruth.Report?>(initialValue = null) {
        value = runCatching { CoexistenceTruth.read(ctx) }
            .onFailure { Diagnostics.error("passgen.Coexistence", "read threw: ${it.message}") }
            .getOrNull()
    }

    val r = report
    if (r == null) {
        // Read in flight — honest "reading…" line, no fabricated holders.
        SuiteCard {
            Text(
                stringResource(R.string.coex_reading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (!r.elevatedRead) {
        // Grant revoked mid-flight / shell not answering: degrade to rootless.
        RootlessCoexistenceCard()
        return
    }

    SuiteCard {
        Text(
            stringResource(R.string.coex_named_header),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))

        SlotLine(
            label = stringResource(R.string.coex_slot_autofill),
            holder = r.autofill,
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        SlotLine(
            label = stringResource(R.string.coex_slot_ime),
            holder = r.ime,
        )
    }

    // Accessibility services get their own card — a live third-party a11y service
    // is the real scrape risk and deserves a warning-tinted callout, not a line.
    SuiteCard {
        Text(
            stringResource(R.string.coex_slot_a11y),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))

        if (r.a11yServices.isEmpty()) {
            Text(
                stringResource(R.string.coex_a11y_none),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            if (r.hasThirdPartyA11y) {
                Text(
                    stringResource(R.string.coex_a11y_thirdparty_warn, r.thirdPartyA11yCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = UnderstoryTheme.semantic.warning,
                )
                Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
            }
            for (svc in r.a11yServices) {
                val name = svc.label ?: svc.packageName ?: svc.component
                Text(
                    text = if (svc.isThirdParty)
                        stringResource(R.string.coex_a11y_item_thirdparty, name)
                    else
                        stringResource(R.string.coex_a11y_item_system, name),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (svc.isThirdParty) UnderstoryTheme.semantic.warning
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
            SecureOutlinedButton(
                onClick = { openA11ySettings(ctx) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.coex_action_review_a11y))
            }
        }
    }

    Text(
        stringResource(R.string.coex_readonly_note),
        style = MaterialTheme.typography.bodySmall,
        color = UnderstoryTheme.semantic.dim,
    )
}

/** One named slot line: "<label>: <holder>". Honest about empty / unknown. */
@Composable
private fun SlotLine(label: String, holder: CoexistenceTruth.SlotHolder) {
    val holderText = when {
        holder.isSelf -> stringResource(R.string.coex_holder_self)
        holder.isEmpty -> stringResource(R.string.coex_holder_none)
        holder.label != null -> holder.label
        // We read a value but couldn't parse a package — show the raw string
        // rather than guess or drop it (fail-open, honest).
        holder.rawValue != null -> stringResource(R.string.coex_holder_unparsed, holder.rawValue)
        else -> stringResource(R.string.coex_holder_none)
    }
    Text(
        text = stringResource(R.string.coex_slot_line, label, holderText),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * The rootless posture: the existing [AutofillManager] heuristic (us / not us) +
 * IME-enabled check, then the shared [ElevationCard] invite so the user can grant
 * Shizuku to upgrade to the named truth. The ElevationCard's grant button is the
 * real flow — never a dead control — and its fallback deep-links to Settings.
 */
@Composable
private fun RootlessCoexistenceCard() {
    val ctx = LocalContext.current
    val autofillManager = remember { ctx.getSystemService(AutofillManager::class.java) }
    val imeManager = remember { ctx.getSystemService(InputMethodManager::class.java) }
    val autofillIsUs = remember { autofillManager?.hasEnabledAutofillServices() == true }
    val imeIsUs = remember {
        imeManager?.enabledInputMethodList?.any { it.packageName == ctx.packageName } == true
    }

    SuiteCard {
        Text(
            stringResource(R.string.coex_heuristic_header),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        Text(
            text = if (autofillIsUs) stringResource(R.string.coex_heuristic_autofill_us)
            else stringResource(R.string.coex_heuristic_autofill_other),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.xs))
        Text(
            text = if (imeIsUs) stringResource(R.string.coex_heuristic_ime_us)
            else stringResource(R.string.coex_heuristic_ime_other),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(UnderstoryTheme.spacing.sm))
        Text(
            stringResource(R.string.coex_heuristic_note),
            style = MaterialTheme.typography.bodySmall,
            color = UnderstoryTheme.semantic.dim,
        )
    }

    // The shared grant card. `unlocks` describes exactly the read-only upgrade
    // this feature provides; the rootless fallback deep-links to a11y settings so
    // even when Shizuku isn't installed the user has a real next step.
    ElevationCard(
        unlocks = listOf(
            stringResource(R.string.coex_unlock_autofill),
            stringResource(R.string.coex_unlock_ime),
            stringResource(R.string.coex_unlock_a11y),
        ),
        rootlessFallback = stringResource(R.string.coex_rootless_fallback),
        onRootlessFallback = { openA11ySettings(ctx) },
    )
}

/** Open the system accessibility settings so the user can review live services. */
private fun openA11ySettings(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
