package com.understory.passgen

import com.understory.security.Tamper

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.os.Debug
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.widget.RemoteViews

/**
 * Autofill provider. Inspects the requesting view tree, identifies password fields,
 * and returns a single dataset whose presentation is a fixed neutral label —
 * never the password. The actual value is produced by [GenerateAndFillActivity]
 * when the user taps the suggestion; control returns through the autofill
 * authentication channel, never through any rendered surface.
 */
class PassgenAutofillService : AutofillService() {

    companion object {
        // Distinct request codes per dataset's auth pending intent.
        // Without these the framework would reuse a single PendingIntent
        // and one of the two auth flows would shadow the other.
        private const val REQUEST_CODE_SAVED = 1
        private const val REQUEST_CODE_GENERATE = 2
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        try {
            onFillRequestImpl(request, cancellationSignal, callback)
        } catch (t: Throwable) {
            // Crash-catcher: any exception here would otherwise take down the
            // passgen process and surface as Android's "this app has a bug"
            // dialog. Fail closed — return null response so the autofill
            // framework moves on without our suggestion.
            runCatching { callback.onSuccess(null) }
        }
    }

    private fun onFillRequestImpl(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        // Refuse to serve a fill request if a debugger is attached to our process.
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            callback.onSuccess(null)
            return
        }
        // Self-verify the package — defends against a process-name spoof in
        // some custom-ROM environments.
        if (packageName != "com.understory.passgen") {
            callback.onSuccess(null)
            return
        }
        // Tamper / hooking detection.
        if (Tamper.check(applicationContext).hardFail) {
            callback.onSuccess(null)
            return
        }
        val ctx = request.fillContexts
        if (ctx.isEmpty()) {
            callback.onSuccess(null)
            return
        }
        val structure: AssistStructure = ctx.last().structure
        val passwordIds = collectPasswordFieldIds(structure)
        if (passwordIds.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        // Find username fields too so the saved-entry path can fill
        // both halves. Generate-new path doesn't use them; that's still
        // a single password fill.
        val usernameIds = collectUsernameFieldIds(structure)

        // Extract the target's web domain (browser autofill) and app
        // package (native-app autofill). Used by FillSavedEntryActivity
        // to filter entries by relevance — informational only; the
        // user can always pick from the full list.
        val webDomain = extractWebDomain(structure)
        val appPackage = extractAppPackage(structure)

        val snap = Settings.load(applicationContext)

        // ---------- Dataset 1: pick a saved entry ----------
        val savedPresentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "passgen — pick saved entry")
        }
        val savedAuthIntent = Intent(this, FillSavedEntryActivity::class.java).apply {
            putParcelableArrayListExtra(
                FillSavedEntryActivity.EXTRA_AUTOFILL_PASSWORD_IDS,
                ArrayList(passwordIds),
            )
            if (usernameIds.isNotEmpty()) {
                putParcelableArrayListExtra(
                    FillSavedEntryActivity.EXTRA_AUTOFILL_USERNAME_IDS,
                    ArrayList(usernameIds),
                )
            }
            putExtra(FillSavedEntryActivity.EXTRA_WEB_DOMAIN, webDomain)
            putExtra(FillSavedEntryActivity.EXTRA_APP_PACKAGE, appPackage)
        }
        val savedAuthPending = PendingIntent.getActivity(
            this,
            REQUEST_CODE_SAVED,
            savedAuthIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        @Suppress("DEPRECATION")
        val savedDataset = Dataset.Builder(savedPresentation).apply {
            for (id in passwordIds) setValue(id, null, savedPresentation)
            for (id in usernameIds) setValue(id, null, savedPresentation)
            setAuthentication(savedAuthPending.intentSender)
        }.build()

        // ---------- Dataset 2: generate a new password ----------
        val genPresentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "passgen — generate (${snap.length} chars)")
        }
        val genAuthIntent = Intent(this, GenerateAndFillActivity::class.java).apply {
            putParcelableArrayListExtra(
                GenerateAndFillActivity.EXTRA_AUTOFILL_IDS,
                ArrayList(passwordIds),
            )
            // §2.2: carry the resolved target so the generate activity can write
            // a receipt against the right site/app. Prefer web domain (browser
            // fills) over the hosting app package (native fills).
            val (target, kind) = when {
                webDomain.isNotEmpty() -> webDomain to "domain"
                appPackage.isNotEmpty() -> appPackage to "package"
                else -> "" to "unknown"
            }
            putExtra(GenerateAndFillActivity.EXTRA_TARGET, target)
            putExtra(GenerateAndFillActivity.EXTRA_TARGET_KIND, kind)
        }
        val genAuthPending = PendingIntent.getActivity(
            this,
            REQUEST_CODE_GENERATE,
            genAuthIntent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        @Suppress("DEPRECATION")
        val genDataset = Dataset.Builder(genPresentation).apply {
            for (id in passwordIds) setValue(id, null, genPresentation)
            setAuthentication(genAuthPending.intentSender)
        }.build()

        val response = FillResponse.Builder()
            .addDataset(savedDataset)
            .addDataset(genDataset)
            .build()

        callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // We never save anything from the user.
        callback.onSuccess()
    }

    private fun collectPasswordFieldIds(structure: AssistStructure): List<AutofillId> {
        val out = mutableListOf<AutofillId>()
        for (i in 0 until structure.windowNodeCount) {
            walk(structure.getWindowNodeAt(i).rootViewNode, out)
        }
        return out
    }

    private fun walk(node: AssistStructure.ViewNode, out: MutableList<AutofillId>) {
        if (isPasswordField(node)) {
            node.autofillId?.let(out::add)
        }
        for (i in 0 until node.childCount) {
            walk(node.getChildAt(i), out)
        }
    }

    /** Find AutofillIds that look like a username/email field. Mirrors
     *  the password-detection heuristics but with the username-flavored
     *  AUTOFILL_HINT constants and HTML autocomplete tokens. */
    private fun collectUsernameFieldIds(structure: AssistStructure): List<AutofillId> {
        val out = mutableListOf<AutofillId>()
        for (i in 0 until structure.windowNodeCount) {
            walkUsername(structure.getWindowNodeAt(i).rootViewNode, out)
        }
        return out
    }

    private fun walkUsername(node: AssistStructure.ViewNode, out: MutableList<AutofillId>) {
        if (isUsernameField(node)) {
            node.autofillId?.let(out::add)
        }
        for (i in 0 until node.childCount) {
            walkUsername(node.getChildAt(i), out)
        }
    }

    private fun isUsernameField(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints
        if (hints != null) {
            for (h in hints) {
                if (h.equals(View.AUTOFILL_HINT_USERNAME, ignoreCase = true)) return true
                if (h.equals(View.AUTOFILL_HINT_EMAIL_ADDRESS, ignoreCase = true)) return true
                if (h.contains("username", ignoreCase = true)) return true
                if (h.contains("email", ignoreCase = true)) return true
            }
        }
        val it = node.inputType
        if (it and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            val variation = it and InputType.TYPE_MASK_VARIATION
            if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            ) return true
        }
        val html = node.htmlInfo
        if (html != null && html.tag.equals("input", ignoreCase = true)) {
            val attrs = html.attributes ?: return false
            for (kv in attrs) {
                if (kv.first.equals("autocomplete", ignoreCase = true)) {
                    val v = kv.second.lowercase()
                    if (v.contains("username") || v.contains("email")) return true
                }
                if (kv.first.equals("name", ignoreCase = true)) {
                    val v = kv.second.lowercase()
                    if (v.contains("user") || v.contains("email") || v == "login") return true
                }
                if (kv.first.equals("type", ignoreCase = true) &&
                    kv.second.equals("email", ignoreCase = true)
                ) return true
            }
        }
        return false
    }

    /** Browser autofill: window root nodes carry a webDomain string for
     *  HTML <form> contexts. Returns the first non-empty domain found,
     *  or empty string if this is a native-app fill. */
    private fun extractWebDomain(structure: AssistStructure): String {
        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i).rootViewNode
            val d = root.webDomain
            if (!d.isNullOrEmpty()) return d
            // Some browsers nest the webDomain on the inner FormNode rather
            // than the root. Walk shallow to pick it up.
            for (j in 0 until root.childCount) {
                val c = root.getChildAt(j).webDomain
                if (!c.isNullOrEmpty()) return c
            }
        }
        return ""
    }

    /** Native-app autofill: structure.activityComponent gives us the
     *  Activity hosting the password field, and from it the package. */
    private fun extractAppPackage(structure: AssistStructure): String {
        val component = structure.activityComponent ?: return ""
        return component.packageName ?: ""
    }

    private fun isPasswordField(node: AssistStructure.ViewNode): Boolean {
        val hints = node.autofillHints
        if (hints != null) {
            for (h in hints) {
                if (h.equals(View.AUTOFILL_HINT_PASSWORD, ignoreCase = true)) return true
                // Common explicit password-ish hints used by browsers and apps.
                if (h.contains("password", ignoreCase = true)) return true
                if (h.equals("newPassword", ignoreCase = true)) return true
            }
        }
        val it = node.inputType
        if (it and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            val variation = it and InputType.TYPE_MASK_VARIATION
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            ) return true
        }
        if (it and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER) {
            val variation = it and InputType.TYPE_MASK_VARIATION
            if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        }
        // HTML5 password inputs surface via htmlInfo. Modern login forms
        // increasingly use autocomplete="new-password" / "current-password"
        // on <input type="text"> for password-manager compatibility — match
        // those too. Standard <input type="password"> is also matched.
        val html = node.htmlInfo
        if (html != null && html.tag.equals("input", ignoreCase = true)) {
            val attrs = html.attributes ?: return false
            for (kv in attrs) {
                if (kv.first.equals("type", ignoreCase = true) &&
                    kv.second.equals("password", ignoreCase = true)
                ) return true
                if (kv.first.equals("autocomplete", ignoreCase = true)) {
                    val v = kv.second.lowercase()
                    if (v.contains("new-password") || v.contains("current-password")) return true
                }
            }
        }
        return false
    }
}
