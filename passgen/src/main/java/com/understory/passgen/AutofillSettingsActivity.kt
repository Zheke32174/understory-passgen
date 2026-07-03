package com.understory.passgen

import com.understory.security.Diagnostics
import com.understory.security.Tamper

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Debug

/**
 * Settings entry point referenced by res/xml/autofill_service.xml
 * (android:settingsActivity). The system autofill picker — and, on Samsung One
 * UI, the "Additional autofill services" list — shows a gear / settings
 * affordance next to each listed service and launches THIS activity when the
 * user taps it. Declaring a settingsActivity (together with the
 * <compatibility-package> block) is part of what makes One UI surface passgen in
 * the additional-services list rather than only the single primary slot.
 *
 * It owns no UI of its own: it immediately forwards the user into
 * [MainActivity], whose Generate tab carries the delivery-methods section
 * (autofill / keyboard status + the buttons that open the relevant system
 * settings). This keeps a single place that explains and manages delivery,
 * instead of duplicating that surface here.
 *
 * exported=true is REQUIRED — the caller is the system Settings process, a
 * different UID. There is nothing sensitive to protect at this door: it carries
 * no secret, renders nothing, and only starts our own launcher activity. The
 * usual hard-fail guards (debugger / package spoof / tamper) still apply so a
 * compromised environment can't use it as a lever.
 */
class AutofillSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            onCreateImpl()
        } catch (t: Throwable) {
            // Never surface a crash dialog from a system-launched door. Fail
            // closed: just finish. The user can still open the app directly.
            finish()
        }
    }

    private fun onCreateImpl() {
        Diagnostics.log("passgen.AutofillSettings", "onCreate — routing to MainActivity")

        // Hard refusal: debugger / spoofed package name / tamper. Mirrors the
        // other entry points; silent finish, no diagnostic leak.
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            finish(); return
        }
        if (packageName != "com.understory.passgen") {
            finish(); return
        }
        if (Tamper.check(applicationContext).hardFail) {
            finish(); return
        }

        // Bring the app's main surface forward. MainActivity is launchMode
        // singleTask, so this resumes an existing instance (delivery methods
        // visible on the Generate tab) rather than stacking a duplicate.
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        finish()
    }
}
