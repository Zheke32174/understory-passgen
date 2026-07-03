package com.understory.passgen

import com.understory.security.Tamper

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.service.autofill.Dataset
import android.widget.RemoteViews

/**
 * Invisible activity launched by the autofill framework after the user taps the
 * passgen suggestion. Generates a password, packages it as a Dataset response,
 * returns it through the autofill IPC, and finishes — without ever rendering
 * a UI surface.
 *
 * The password CharArray is wiped immediately after the AutofillValue is built.
 * The AutofillValue itself is constructed once and only escapes this process via
 * the autofill framework's binder transaction to the requesting field.
 */
class GenerateAndFillActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            onCreateImpl(savedInstanceState)
        } catch (t: Throwable) {
            // Crash-catcher: any exception in autofill-fill flow would
            // otherwise take down the passgen process. Fail closed: cancel
            // the autofill request so the system moves on cleanly.
            setResult(RESULT_CANCELED); finish()
        }
    }

    private fun onCreateImpl(savedInstanceState: Bundle?) {
        // Hard refusal: debugger / spoofed package name / wrong caller / tamper.
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            setResult(RESULT_CANCELED); finish(); return
        }
        if (packageName != "com.understory.passgen") {
            setResult(RESULT_CANCELED); finish(); return
        }
        if (Tamper.check(applicationContext).hardFail) {
            setResult(RESULT_CANCELED); finish(); return
        }

        // Belt-and-braces: even though the activity uses NoDisplay theme,
        // make doubly sure no surface this activity ever owns can be captured.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setHideOverlayWindows(true)
        }

        @Suppress("UNCHECKED_CAST", "DEPRECATION")
        val ids: ArrayList<AutofillId>? =
            intent.getParcelableArrayListExtra(EXTRA_AUTOFILL_IDS)

        if (ids.isNullOrEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val snap = Settings.load(applicationContext)
        val opts = Settings.toGeneratorOptions(snap)
        if (!opts.isValid()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val target = intent.getStringExtra(EXTRA_TARGET) ?: ""
        val kind = intent.getStringExtra(EXTRA_TARGET_KIND) ?: "unknown"

        val chars = PasswordGenerator.generate(opts)
        val value: AutofillValue = try {
            // §2.2: write a receipt BEFORE the wipe so the user is never silently
            // locked out. Synchronous on purpose: this invisible activity is
            // about to finish() and the process may die, so we must guarantee the
            // write is durable first (§8). Read `chars` for the receipt before the
            // finally-block wipes it. Best-effort — a receipt failure must not
            // fail the fill.
            runCatching {
                Receipts.append(
                    applicationContext, "autofill", target, kind, snap,
                    if (snap.keepGeneratedValue) chars else null,
                )
            }
            // String construction is unavoidable here — AutofillValue.forText takes
            // a CharSequence. We minimize lifetime: build it once, return, wipe.
            AutofillValue.forText(String(chars))
        } finally {
            PasswordGenerator.wipe(chars)
        }

        // Neutral presentation again. Same fixed label that was shown above the
        // keyboard — never the value.
        val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
            setTextViewText(android.R.id.text1, "passgen — filled")
        }

        @Suppress("DEPRECATION")
        val datasetBuilder = Dataset.Builder(presentation)
        for (id in ids) {
            datasetBuilder.setValue(id, value, presentation)
        }
        val replyIntent = Intent().apply {
            putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, datasetBuilder.build())
        }
        setResult(RESULT_OK, replyIntent)
        finish()
    }

    companion object {
        const val EXTRA_AUTOFILL_IDS = "passgen.autofill_ids"
        const val EXTRA_TARGET = "passgen.autofill_target"
        const val EXTRA_TARGET_KIND = "passgen.autofill_target_kind"
    }
}
