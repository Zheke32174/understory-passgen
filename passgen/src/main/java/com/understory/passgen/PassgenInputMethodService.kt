package com.understory.passgen

import com.understory.security.Tamper

import android.content.Intent
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Debug
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Custom IME (keyboard) that types a generated password OR a saved ledger entry
 * directly into the focused field via
 * [android.view.inputmethod.InputConnection.commitText]. The value never
 * traverses the system clipboard or the autofill IPC stack.
 *
 * V2 makes this a complete coexistence channel (design §6):
 *   1. Generate & insert  — generate using current Settings, commit, wipe, and
 *      write a receipt (§2.2). Shows a status line (§6.3) so the button is not
 *      dead-feeling on failure.
 *   2. Type a saved entry — launch [ImeFillActivity] (a transparent trampoline
 *      that can host a BiometricPrompt, which an IME service cannot). It unlocks
 *      the vault, shows the picker, and hands the chosen value back through a
 *      short-lived static slot ([pendingFill]).
 *   3. Switch keyboard    — return to the user's previous IME.
 *
 * Hardening (unchanged): FLAG_SECURE, hard refusal on debugger/tamper/package
 * mismatch, strict tap-jacking on Generate, and IMPORTANT_FOR_ACCESSIBILITY_NO
 * (documented as a known limitation on the generator screen, §6.4).
 */
class PassgenInputMethodService : InputMethodService() {

    private var hardRefuse: String? = null
    private var statusView: TextView? = null

    override fun onCreate() {
        super.onCreate()
        runCatching {
            hardRefuse = when {
                Debug.isDebuggerConnected() || Debug.waitingForDebugger() ->
                    "debugger detected"
                packageName != "com.understory.passgen" ->
                    "package mismatch"
                Tamper.check(applicationContext).hardFail ->
                    "tamper detected"
                else -> null
            }
        }.onFailure { hardRefuse = "init error: ${it.javaClass.simpleName}" }
    }

    override fun onCreateInputView(): View {
        return try {
            runCatching {
                window?.window?.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
            val refusal = hardRefuse
            if (refusal != null) refusalView(refusal) else buildKeyboardView()
        } catch (t: Throwable) {
            crashView(t)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // A saved-entry fill may have completed while this IME was backgrounded
        // by the trampoline. Consume any pending value now that we're attached.
        consumePendingFill()
    }

    private fun crashView(t: Throwable): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return TextView(this).apply {
            text = "passgen ime crash: ${t.javaClass.simpleName}: ${t.message}\n\n${sw.toString().take(2000)}"
            setTextColor(Color.parseColor("#EF5350"))
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(pad, pad, pad, pad)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
    }

    private fun buildKeyboardView(): View {
        val ctx = this
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val gap = (10 * density).toInt()

        val snap = Settings.load(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(pad, pad, pad, pad)
            setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            filterTouchesWhenObscured = true
        }

        val title = TextView(ctx).apply {
            text = "passgen keyboard"
            setTextColor(Color.parseColor("#E0E0E0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        root.addView(title)

        val info = TextView(ctx).apply {
            text = "Generate a ${snap.length}-char password, or type a saved ledger entry, " +
                "directly into the focused field. The value never reaches the clipboard."
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, gap / 2, 0, gap)
        }
        root.addView(info)

        val generateBtn = Button(ctx).apply {
            text = "Generate & insert password"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#3F51B5"))
            setOnTouchListener(ObscuredTouchGate())
            setOnClickListener { onGenerateClicked(it) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (52 * density).toInt(),
            )
            lp.bottomMargin = gap
            layoutParams = lp
        }
        root.addView(generateBtn)

        val savedBtn = Button(ctx).apply {
            text = "Type a saved entry"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D57"))
            setOnTouchListener(ObscuredTouchGate())
            setOnClickListener { onTypeSavedClicked(it) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (52 * density).toInt(),
            )
            lp.bottomMargin = gap
            layoutParams = lp
        }
        root.addView(savedBtn)

        val switchBtn = Button(ctx).apply {
            text = "Switch back to your keyboard"
            isAllCaps = false
            setTextColor(Color.parseColor("#E0E0E0"))
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            setOnClickListener { switchAwayFromUs() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * density).toInt(),
            )
            lp.bottomMargin = gap
            layoutParams = lp
        }
        root.addView(switchBtn)

        // §6.3: a visible status line so the buttons are never dead-feeling.
        val status = TextView(ctx).apply {
            text = ""
            setTextColor(Color.parseColor("#9E9E9E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        statusView = status
        root.addView(status)

        return root
    }

    private fun setStatus(text: String) {
        runCatching { statusView?.text = text }
    }

    private fun onGenerateClicked(view: View) {
        runCatching {
            if (hardRefuse != null) { setStatus("couldn't type: $hardRefuse"); return@runCatching }
            if (Tamper.check(applicationContext).hardFail) {
                hardRefuse = "tamper at click-time"
                setStatus("couldn't type: tamper detected")
                return@runCatching
            }
            if (Debug.isDebuggerConnected()) { setStatus("couldn't type: debugger"); return@runCatching }
            if (!view.hasWindowFocus()) { setStatus("couldn't type: window not focused"); return@runCatching }

            val ic = currentInputConnection
            if (ic == null) { setStatus("couldn't type: no input field focused"); return@runCatching }
            val snap = Settings.load(applicationContext)
            val opts = Settings.toGeneratorOptions(snap)
            if (!opts.isValid()) { setStatus("couldn't type: invalid generator settings"); return@runCatching }

            val chars = PasswordGenerator.generate(opts)
            try {
                ic.commitText(String(chars), 1)
                // §2.2: write a receipt (best-effort). A receipt failure must not
                // crash the IME, but the user should know the outcome.
                val ei = currentInputEditorInfo
                val target = ei?.packageName ?: ""
                val recorded = runCatching {
                    Receipts.append(
                        applicationContext, "ime", target, "package", snap,
                        if (snap.keepGeneratedValue) chars else null,
                    )
                }.isSuccess
                setStatus(if (recorded) "typed" else "typed (not recorded)")
            } finally {
                PasswordGenerator.wipe(chars)
            }
        }.onFailure {
            // Never crash the IME process; surface the outcome instead.
            setStatus("couldn't type: ${it.javaClass.simpleName}")
        }
    }

    private fun onTypeSavedClicked(view: View) {
        runCatching {
            if (hardRefuse != null) { setStatus("couldn't open: $hardRefuse"); return@runCatching }
            if (Tamper.check(applicationContext).hardFail) {
                setStatus("couldn't open: tamper detected"); return@runCatching
            }
            if (!view.hasWindowFocus()) { setStatus("couldn't open: window not focused"); return@runCatching }
            if (currentInputConnection == null) { setStatus("focus a field first"); return@runCatching }

            // Launch the transparent trampoline that can host BiometricPrompt.
            val ei = currentInputEditorInfo
            val intent = Intent(applicationContext, ImeFillActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(ImeFillActivity.EXTRA_TARGET_PACKAGE, ei?.packageName ?: "")
            }
            startActivity(intent)
            setStatus("unlock to pick a saved entry…")
        }.onFailure {
            setStatus("couldn't open: ${it.javaClass.simpleName}")
        }
    }

    /**
     * Commit a value handed back by [ImeFillActivity] via the [pendingFill] slot,
     * if any. Called on view (re)attach. The value is committed then the slot is
     * cleared immediately; the one unavoidable String lives only until commit.
     */
    private fun consumePendingFill() {
        val value = pendingFill ?: return
        pendingFill = null
        runCatching {
            val ic = currentInputConnection ?: run { setStatus("couldn't type: no input field focused"); return }
            ic.commitText(value, 1)
            setStatus("typed saved entry")
        }.onFailure { setStatus("couldn't type: ${it.javaClass.simpleName}") }
    }

    private fun switchAwayFromUs() {
        runCatching { switchToPreviousInputMethod() }
    }

    private fun refusalView(reason: String): View {
        val pad = (24 * resources.displayMetrics.density).toInt()
        return TextView(this).apply {
            text = "passgen ime disabled: $reason"
            setTextColor(Color.parseColor("#FFB74D"))
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            setPadding(pad, pad, pad, pad)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        runCatching {
            Tamper.invalidate()
            hardRefuse = when {
                Debug.isDebuggerConnected() || Debug.waitingForDebugger() ->
                    "debugger detected"
                Tamper.check(applicationContext).hardFail ->
                    "tamper detected"
                else -> null
            }
        }
    }

    companion object {
        /**
         * Short-lived hand-off slot for the IME→[ImeFillActivity]→IME round trip.
         * The trampoline writes the chosen saved value here just before it
         * finishes; the IME reads and clears it on the next view attach. Value
         * lifetime is bounded to a single commit. NOT the clipboard, NOT an
         * Intent extra that lingers (§6.1). Volatile for cross-thread visibility.
         */
        @Volatile
        @JvmStatic
        var pendingFill: String? = null
    }
}

/**
 * OnTouchListener that drops touches when the window is obscured (fully or
 * partially). Mirrors the Compose SecureButton's logic: blocked is decided at
 * DOWN and can only be raised by an obscured MOVE — it then latches for the rest
 * of the gesture, so the view never sees the UP that performs the click.
 */
private class ObscuredTouchGate : View.OnTouchListener {
    private var blocked = false

    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                blocked = isObscured(ev) || !v.hasWindowFocus()
            MotionEvent.ACTION_MOVE ->
                if (isObscured(ev)) blocked = true
        }
        return blocked
    }

    private fun isObscured(ev: MotionEvent): Boolean {
        val mask = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
            MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
        return (ev.flags and mask) != 0
    }
}
