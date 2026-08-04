package com.understory.keyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.understory.keyboard.plugin.PluginClient
import com.understory.keyboard.plugin.PluginRegistry
import com.understory.keyboard.plugin.api.FieldContext
import com.understory.keyboard.plugin.api.PluginContract
import com.understory.keyboard.plugin.api.QuickAction
import com.understory.keyboard.ui.KotobaKeyboardView
import com.understory.security.Tamper

/**
 * The Kotoba IME. Typing always works — the plugin subsystem is the only
 * part gated behind tamper checks, and it is skipped entirely (along with
 * suggestions and learning) whenever the focused field is a password.
 */
class KeyboardService : InputMethodService(), KotobaKeyboardView.Listener {

    private lateinit var settings: KeyboardSettings
    private lateinit var engine: SuggestionEngine
    private lateinit var registry: PluginRegistry
    private lateinit var pluginClient: PluginClient
    private val mainHandler = Handler(Looper.getMainLooper())

    private var keyboardView: KotobaKeyboardView? = null
    private var stripContent: LinearLayout? = null

    private val composing = StringBuilder()
    private var privateMode = false
    private var pluginsActive = false
    private var lastShiftTap = 0L
    private var suggestionSeq = 0
    private var lastEngineSave = 0L

    override fun onCreate() {
        super.onCreate()
        settings = KeyboardSettings(this)
        engine = SuggestionEngine()
        runCatching { engine.import(settings.learnedWords) }
        registry = PluginRegistry(this)
        pluginClient = PluginClient(this, registry)
    }

    override fun onDestroy() {
        saveEngine(force = true)
        pluginClient.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0B0B"))
            // The keyboard's own surface must never be recorded into
            // autofill/content-capture telemetry of the host app.
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO)
        }

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (6 * density).toInt()
            setPadding(pad, 0, pad, 0)
        }
        stripContent = strip

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                strip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (44 * density).toInt(),
            ),
        )

        val kb = KotobaKeyboardView(this).apply { listener = this@KeyboardService }
        keyboardView = kb
        root.addView(
            kb,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        privateMode = isPasswordField(info)

        // Never let a screen recorder / cast session watch password entry.
        val w = window?.window
        if (privateMode) {
            w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        keyboardView?.let {
            it.hapticsEnabled = settings.haptics
            it.setPage(KeyLayouts.letters)
            it.shiftState = KotobaKeyboardView.ShiftState.OFF
        }
        updateAutoShift()

        // Plugin subsystem: user master switch AND tamper gate AND not a
        // password field. Tamper.check is memoized, so per-field cost is nil.
        pluginsActive = !privateMode &&
            settings.pluginsEnabled &&
            runCatching { !Tamper.check(applicationContext).hardFail }.getOrDefault(false)
        if (pluginsActive) {
            pluginClient.refresh { mainHandler.post { renderStrip(emptyList()) } }
        }
        renderStrip(emptyList())
    }

    override fun onFinishInput() {
        super.onFinishInput()
        composing.setLength(0)
        saveEngine(force = false)
    }

    // -- key events from the view ------------------------------------------

    override fun onChar(output: String) {
        val ic = currentInputConnection ?: return
        val view = keyboardView
        val shifted = view != null &&
            view.shiftState != KotobaKeyboardView.ShiftState.OFF &&
            view.currentPage() == PageId.LETTERS
        val text = if (shifted) output.uppercase() else output
        if (view != null && view.shiftState == KotobaKeyboardView.ShiftState.ON) {
            view.shiftState = KotobaKeyboardView.ShiftState.OFF
        }

        val composingEnabled = settings.suggestions && !privateMode
        val letterLike = text.length == 1 &&
            (text[0].isLetterOrDigit() || text[0] == '\'')
        if (composingEnabled && letterLike) {
            composing.append(text)
            ic.setComposingText(composing, 1)
            refreshSuggestions()
        } else {
            commitComposing(ic)
            ic.commitText(text, 1)
            if (text == "." || text == "!" || text == "?") updateAutoShift()
            renderStrip(emptyList())
        }
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        if (composing.isNotEmpty()) {
            composing.deleteCharAt(composing.length - 1)
            if (composing.isEmpty()) {
                ic.commitText("", 1)
                renderStrip(emptyList())
                updateAutoShift()
            } else {
                ic.setComposingText(composing, 1)
                refreshSuggestions()
            }
        } else {
            ic.deleteSurroundingText(1, 0)
            updateAutoShift()
        }
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        commitComposing(ic)
        renderStrip(emptyList())
        if (!sendDefaultEditorAction(true)) {
            ic.commitText("\n", 1)
        }
        updateAutoShift()
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        commitComposing(ic)
        ic.commitText(" ", 1)
        renderStrip(emptyList())
        updateAutoShift()
    }

    override fun onShiftTapped() {
        val view = keyboardView ?: return
        val now = SystemClock.uptimeMillis()
        view.shiftState = when {
            now - lastShiftTap < DOUBLE_TAP_MS ->
                KotobaKeyboardView.ShiftState.LOCK
            view.shiftState == KotobaKeyboardView.ShiftState.OFF ->
                KotobaKeyboardView.ShiftState.ON
            else -> KotobaKeyboardView.ShiftState.OFF
        }
        lastShiftTap = now
    }

    override fun onPageRequested(page: PageId) {
        keyboardView?.setPage(KeyLayouts.page(page))
    }

    override fun onSpaceLongPress() {
        runCatching {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        }
    }

    // -- composing / suggestions -------------------------------------------

    private fun commitComposing(ic: android.view.inputmethod.InputConnection) {
        if (composing.isEmpty()) return
        val word = composing.toString()
        ic.finishComposingText()
        composing.setLength(0)
        learn(word)
    }

    private fun pickSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        // commitText replaces the active composing region.
        ic.commitText("$word ", 1)
        composing.setLength(0)
        learn(word)
        renderStrip(emptyList())
        updateAutoShift()
    }

    private fun learn(word: String) {
        if (privateMode || !settings.learning) return
        engine.learn(word)
    }

    private fun refreshSuggestions() {
        val seq = ++suggestionSeq
        val prefix = composing.toString()
        if (prefix.isEmpty()) {
            renderStrip(emptyList())
            return
        }
        val local = engine.suggest(prefix, LOCAL_SUGGESTIONS)
        renderStrip(local)
        if (!pluginsActive) return
        pluginClient.suggestions(prefix, "", fieldContext()) { fromPlugins ->
            mainHandler.post {
                if (seq != suggestionSeq) return@post
                renderStrip((local + fromPlugins).distinct().take(MAX_STRIP_SUGGESTIONS))
            }
        }
    }

    private fun updateAutoShift() {
        val view = keyboardView ?: return
        if (view.shiftState == KotobaKeyboardView.ShiftState.LOCK) return
        if (!settings.autoCap || privateMode) return
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo ?: return
        if (editorInfo.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return
        val caps = runCatching { ic.getCursorCapsMode(editorInfo.inputType) }.getOrDefault(0)
        view.shiftState = if (caps != 0) {
            KotobaKeyboardView.ShiftState.ON
        } else {
            KotobaKeyboardView.ShiftState.OFF
        }
    }

    // -- strip rendering ----------------------------------------------------

    private fun renderStrip(suggestions: List<String>) {
        val strip = stripContent ?: return
        strip.removeAllViews()
        for (word in suggestions) {
            strip.addView(stripChip(word, CHIP_TEXT_COLOR) { pickSuggestion(word) })
        }
        if (!pluginsActive) return
        for ((entry, action) in pluginClient.actionChips()) {
            strip.addView(
                stripChip(action.label, PLUGIN_CHIP_COLOR) {
                    runPluginAction(entry, action)
                },
            )
        }
    }

    private fun stripChip(label: String, color: Int, onTap: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            val padH = (12 * density).toInt()
            setPadding(padH, 0, padH, 0)
            setOnClickListener { onTap() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            )
        }
    }

    // -- plugins ------------------------------------------------------------

    private fun fieldContext(): FieldContext {
        val editorInfo = currentInputEditorInfo
        val inputType = editorInfo?.inputType ?: 0
        return FieldContext(
            hostPackage = packageName,
            targetPackage = editorInfo?.packageName ?: "",
            inputTypeClass = inputType and InputType.TYPE_MASK_CLASS,
            multiline = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0,
        )
    }

    private fun runPluginAction(entry: PluginRegistry.PluginEntry, action: QuickAction) {
        if (!pluginsActive) return
        val ic = currentInputConnection ?: return
        val selected = runCatching { ic.getSelectedText(0)?.toString() }.getOrNull()
        val transform = action.kind == PluginContract.KIND_TRANSFORM
        val subject = when {
            !transform -> ""
            !selected.isNullOrEmpty() -> selected
            composing.isNotEmpty() -> composing.toString()
            else -> return // transform with nothing to transform: no-op
        }
        val composingWasSubject = transform && selected.isNullOrEmpty()
        pluginClient.performAction(entry.component, action.id, subject, fieldContext()) { result ->
            mainHandler.post {
                if (result.isNullOrEmpty() || privateMode) return@post
                val conn = currentInputConnection ?: return@post
                if (transform) {
                    if (composingWasSubject) {
                        // Only rewrite if the user hasn't typed on meanwhile.
                        if (composing.toString() == subject) {
                            composing.setLength(0)
                            composing.append(result)
                            conn.setComposingText(composing, 1)
                            refreshSuggestions()
                        }
                    } else {
                        conn.commitText(result, 1) // replaces the selection
                    }
                } else {
                    commitComposing(conn)
                    conn.commitText(result, 1)
                    renderStrip(emptyList())
                }
            }
        }
    }

    // -- misc ---------------------------------------------------------------

    private fun isPasswordField(info: EditorInfo?): Boolean {
        val inputType = info?.inputType ?: return false
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (cls) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    private fun saveEngine(force: Boolean) {
        if (!engine.dirty) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastEngineSave < ENGINE_SAVE_INTERVAL_MS) return
        lastEngineSave = now
        runCatching { settings.learnedWords = engine.export() }
    }

    private companion object {
        const val DOUBLE_TAP_MS = 300L
        const val LOCAL_SUGGESTIONS = 3
        const val MAX_STRIP_SUGGESTIONS = 6
        const val ENGINE_SAVE_INTERVAL_MS = 30_000L
        val CHIP_TEXT_COLOR = Color.parseColor("#E6E6E6")
        val PLUGIN_CHIP_COLOR = Color.parseColor("#7ED4EE")
    }
}
