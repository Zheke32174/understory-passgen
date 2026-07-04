package com.understory.passgen

import android.content.Context
import android.content.pm.PackageManager
import com.understory.elevation.Elevation
import com.understory.security.Diagnostics

/**
 * READ-ONLY "coexistence truth" reader.
 *
 * Without elevation, passgen can only ask [android.view.autofill.AutofillManager]
 * `hasEnabledAutofillServices()` — a boolean that answers "is it US?" and nothing
 * about WHO the incumbent is, which IME is active, or which accessibility
 * services are live (the real on-screen-scrape risk). This reader upgrades that
 * to the NAMED truth when — and only when — a privileged shell is granted, by
 * reading three `secure` settings:
 *
 *   settings get secure autofill_service            → who holds the autofill slot
 *   settings get secure default_input_method        → the active IME
 *   settings get secure enabled_accessibility_services → live a11y services
 *
 * DOCTRINE (hard):
 *  - READ-ONLY. No writes, ever. Every call goes through [Elevation.readShell],
 *    which returns null (never throws) when unelevated or on any error.
 *  - FAIL-OPEN on a parse miss. A dump-format drift or an unexpected token must
 *    DEGRADE to "unknown" and let the existing rootless posture stand — never
 *    block, never alarm, never fabricate a holder we didn't actually read.
 *  - This reader NEVER acts on another app. It only names what is already set.
 *    (The suite-wide "don't touch the launcher / settings / Tailscale / Shizuku /
 *    siblings" exclusion is about *actions*; naming is safe. We still tag our own
 *    package and known siblings when we recognise them, purely for honest copy.)
 */
object CoexistenceTruth {

    /** Our own package, so we can say "passgen holds it" rather than a raw id. */
    private const val SELF_PKG = "com.understory.passgen"

    /**
     * Package prefixes we treat as first-party / system when deciding whether an
     * enabled accessibility service is a THIRD-PARTY scrape risk. Mirrors
     * [com.understory.security.A11yProbe]'s allow-list so the two agree.
     */
    private val SYSTEM_A11Y_PREFIXES = listOf(
        "com.google.android.",
        "com.android.",
        "com.samsung.android.",
        "android.",
    )

    /** One slot's resolved truth. [rawValue] is the exact setting string we read. */
    data class SlotHolder(
        /** The component/flattened value the setting held, or null when empty/unset. */
        val rawValue: String?,
        /** Package parsed out of [rawValue], or null when we couldn't parse one. */
        val packageName: String?,
        /** Best-effort human label for [packageName]; falls back to the package id. */
        val label: String?,
        /** True when we recognised this as passgen itself. */
        val isSelf: Boolean,
    ) {
        /** Nothing is set for this slot (`null`/`""`/literal "null" from the shell). */
        val isEmpty: Boolean get() = packageName == null && rawValue.isNullOrBlank()
    }

    /** One enabled accessibility service and whether it's a third-party scrape risk. */
    data class A11yService(
        val component: String,
        val packageName: String?,
        val label: String?,
        val isThirdParty: Boolean,
    )

    /**
     * The whole elevated read. [elevatedRead] is false when we could not run the
     * privileged read at all (unelevated, or every read returned null) — the UI
     * MUST then fall back to the rootless AutofillManager heuristic, NOT show a
     * half-empty truth card.
     */
    data class Report(
        val elevatedRead: Boolean,
        val autofill: SlotHolder,
        val ime: SlotHolder,
        val a11yServices: List<A11yService>,
    ) {
        /** Any enabled third-party a11y service → the real on-screen-scrape risk. */
        val hasThirdPartyA11y: Boolean get() = a11yServices.any { it.isThirdParty }
        val thirdPartyA11yCount: Int get() = a11yServices.count { it.isThirdParty }
    }

    private val EMPTY_SLOT = SlotHolder(rawValue = null, packageName = null, label = null, isSelf = false)

    /**
     * Run the elevated read. Suspends (shell IPC). Never throws; on any miss the
     * corresponding field degrades to an empty/unknown holder and [Report.elevatedRead]
     * reflects whether ANY of the three reads actually returned data.
     *
     * Gate the CALL SITE on [Elevation.canRunShell]; this still self-guards so a
     * race (grant revoked mid-flight) degrades to a not-elevated [Report] instead
     * of throwing.
     */
    suspend fun read(ctx: Context): Report {
        if (!Elevation.canRunShell(ctx)) {
            return Report(elevatedRead = false, autofill = EMPTY_SLOT, ime = EMPTY_SLOT, a11yServices = emptyList())
        }

        val autofillRaw = Elevation.readShell(ctx, listOf("settings", "get", "secure", "autofill_service"))
        val imeRaw = Elevation.readShell(ctx, listOf("settings", "get", "secure", "default_input_method"))
        val a11yRaw = Elevation.readShell(ctx, listOf("settings", "get", "secure", "enabled_accessibility_services"))

        // "Elevated read happened" = at least one of the three came back non-null.
        // If ALL three are null the shell isn't really answering (bind failure,
        // revoked mid-flight): degrade to the rootless heuristic rather than
        // render an all-unknown truth card that looks broken.
        val elevatedRead = autofillRaw != null || imeRaw != null || a11yRaw != null
        if (!elevatedRead) {
            Diagnostics.log("passgen.Coexistence", "elevated read returned null for all three settings — degrading to rootless")
            return Report(elevatedRead = false, autofill = EMPTY_SLOT, ime = EMPTY_SLOT, a11yServices = emptyList())
        }

        return Report(
            elevatedRead = true,
            autofill = parseSlot(ctx, autofillRaw),
            ime = parseSlot(ctx, imeRaw),
            a11yServices = parseA11y(ctx, a11yRaw),
        )
    }

    /**
     * Parse a single `settings get secure <slot>` value into a [SlotHolder].
     *
     * The shell emits the literal string "null" (not an empty line) when a secure
     * setting is unset. Values are flattened ComponentNames — "pkg/.Class" or
     * "pkg/pkg.Class". We defensively accept a bare package id too. Any shape we
     * don't recognise degrades to "raw value present, package unknown" — we show
     * the raw string honestly rather than guess or drop it.
     */
    private fun parseSlot(ctx: Context, raw: String?): SlotHolder {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrBlank() || trimmed.equals("null", ignoreCase = true)) {
            return EMPTY_SLOT
        }
        val pkg = packageOf(trimmed)
        val isSelf = pkg == SELF_PKG
        return SlotHolder(
            rawValue = trimmed,
            packageName = pkg,
            label = pkg?.let { labelFor(ctx, it) },
            isSelf = isSelf,
        )
    }

    /**
     * Parse `enabled_accessibility_services` — a ':'-separated list of flattened
     * ComponentNames — into per-service entries, flagging third-party ones.
     *
     * On any drift (unexpected separators, unparseable entries) we keep the
     * entries we COULD read and drop only the ones we couldn't; we never throw and
     * never claim a service we didn't see.
     */
    private fun parseA11y(ctx: Context, raw: String?): List<A11yService> {
        val trimmed = raw?.trim()
        if (trimmed.isNullOrBlank() || trimmed.equals("null", ignoreCase = true)) return emptyList()
        // Android uses ':' as the list separator; some OEM dumps use newlines.
        return trimmed
            .split(':', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { component ->
                val pkg = packageOf(component) ?: return@mapNotNull A11yService(
                    component = component,
                    packageName = null,
                    label = null,
                    // Can't attribute a package → treat as third-party (fail toward
                    // caution for the RISK flag, but never toward blocking).
                    isThirdParty = true,
                )
                A11yService(
                    component = component,
                    packageName = pkg,
                    label = labelFor(ctx, pkg),
                    isThirdParty = SYSTEM_A11Y_PREFIXES.none { pkg.startsWith(it) },
                )
            }
    }

    /** Extract the package id from a flattened ComponentName, or accept a bare id. */
    private fun packageOf(flattened: String): String? {
        val v = flattened.trim()
        if (v.isEmpty()) return null
        val pkg = if (v.contains('/')) v.substringBefore('/') else v
        // A package id has at least one dot and no whitespace; anything else is a
        // format we don't recognise → null (degrade, don't guess).
        return pkg.takeIf { it.isNotBlank() && it.contains('.') && it.none { c -> c.isWhitespace() } }
    }

    /** Best-effort app label for a package; falls back to the package id on any miss. */
    private fun labelFor(ctx: Context, pkg: String): String {
        if (pkg == SELF_PKG) return "Understory Keys (passgen)"
        return runCatching {
            val pm = ctx.packageManager
            val ai = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(ai).toString().ifBlank { pkg }
        }.getOrElse {
            when (it) {
                is PackageManager.NameNotFoundException -> pkg
                else -> pkg
            }
        }
    }
}
