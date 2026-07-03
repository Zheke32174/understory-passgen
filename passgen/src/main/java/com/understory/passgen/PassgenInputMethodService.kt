package com.understory.passgen

import com.understory.security.Tamper

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
 * Custom IME (keyboard) that types a generated password directly into the
 * focused field via [android.view.inputmethod.InputConnection.commitText].
 * The value never traverses the system clipboard or the autofill IPC stack.
 *
 * The IME view itself uses minimal plain Android Views (no Compose) to keep
 * the surface area small and predictable. Two buttons:
 *   1. Generate & insert  — generate a password using current Settings,
 *      commit to the focused field, wipe the buffer.
 *   2. Switch keyboard    — return to the user's previous IME.
 *
 * Hardening:
 *   - FLAG_SECURE on the IME window (no screenshot / screen recording capture)
 *   - Hard refusal on debugger / package mismatch / Tamper.hardFail
 *   - Strict tap-jacking: every Generate touch is filtered for
 *     FLAG_WINDOW_IS_OBSCURED + FLAG_WINDOW_IS_PARTIALLY_OBSCURED
 *   - Important-for-content-capture and important-for-autofill set to NO on
 *     our keyboard view, so the system will not feed our UI to ContentCapture
 *     or to other autofill providers.
 */
class PassgenInputMethodService : InputMethodService() {

    private var hardRefuse: String? = null

    override fun onCreate() {
        super.onCreate()
        // Pre-flight checks wrapped in runCatching: any exception here would
        // otherwise take down the IME process and surface as Android's
        // "this app has a bug" dialog. Default to a refusal placeholder
        // if anything goes wrong.
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
            // FLAG_SECURE on the IME window itself — keeps screen-recording and
            // screenshot tools from capturing our keyboard.
            runCatching {
                window?.window?.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            }
            val refusal = hardRefuse
            if (refusal != null) refusalView(refusal) else buildKeyboardView()
        } catch (t: Throwable) {
            // Crash-catcher: any exception in view construction would otherwise
            // take down the entire passgen process, surfacing as Android's
            // generic "this app has a bug" dialog with no diagnostic. Catch
            // here, render the throwable in the refusal view so the user can
            // screenshot it for diagnosis.
            crashView(t)
        }
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
            // Refuse to be inspected by ContentCapture and autofill — the
            // keyboard's view tree should not be fed to either pipeline.
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
            text = "Will type a ${snap.length}-char password directly into the focused field. " +
                "The value never reaches the clipboard."
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
            layoutParams = lp
        }
        root.addView(switchBtn)

        return root
    }

    private fun onGenerateClicked(view: View) {
        runCatching {
            // Re-check tamper at click time; an attacker could have hooked us
            // between onCreate and this click.
            if (hardRefuse != null) return@runCatching
            if (Tamper.check(applicationContext).hardFail) {
                hardRefuse = "tamper at click-time"
                return@runCatching
            }
            if (Debug.isDebuggerConnected()) return@runCatching
            if (!view.hasWindowFocus()) return@runCatching

            val ic = currentInputConnection ?: return@runCatching
            val snap = Settings.load(applicationContext)
            val opts = Settings.toGeneratorOptions(snap)
            if (!opts.isValid()) return@runCatching

            val chars = PasswordGenerator.generate(opts)
            try {
                ic.commitText(String(chars), 1)
            } finally {
                PasswordGenerator.wipe(chars)
            }
        }
        // Swallow any exception silently. A crash here would take down the
        // whole IME process. Better behavior: tap does nothing visible. If
        // the user reports "Generate doesn't work," we'll plumb logging.
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
        // Re-evaluate refusal each time we attach to a new input — the device
        // state may have changed since service start. Wrapped in runCatching:
        // an exception here on a hot path (every text-field focus event) would
        // crash the IME process repeatedly.
        runCatching {
            // Force a fresh Tamper read; the 5s cache could otherwise
            // absorb a hostile install that occurred mid-IME-session.
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
}

/**
 * OnTouchListener that drops touches when the window is obscured (fully or
 * partially). Mirrors the Compose SecureButton's approach: re-checks on every
 * DOWN and MOVE event — overlays that flicker between DOWN and UP would slip
 * through a DOWN-only filter. Returns true to consume → click suppressed.
 */
private class ObscuredTouchGate : View.OnTouchListener {
    override fun onTouch(v: View, ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val mask = MotionEvent.FLAG_WINDOW_IS_OBSCURED or
                    MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
                if ((ev.flags and mask) != 0) return true
            }
        }
        return false
    }
}
