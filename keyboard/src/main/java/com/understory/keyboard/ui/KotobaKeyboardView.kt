package com.understory.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.understory.keyboard.Key
import com.understory.keyboard.KeyCode
import com.understory.keyboard.KeyLayouts
import com.understory.keyboard.KeyPage
import com.understory.keyboard.PageId

/**
 * Custom-drawn keyboard. Deliberately NOT the long-deprecated
 * android.inputmethodservice.KeyboardView: a ~300-line Canvas view keeps
 * full control of layout, theming, and touch behavior with zero legacy
 * baggage.
 *
 * The view is mechanical only — it reports taps through [Listener] and
 * renders whatever [shiftState]/[setPage] say. All input policy (case,
 * composing, suggestions, plugins) lives in KeyboardService.
 */
class KotobaKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onChar(output: String)
        fun onDelete()
        fun onEnter()
        fun onSpace()
        fun onShiftTapped()
        fun onPageRequested(page: PageId)
        fun onSpaceLongPress()
    }

    enum class ShiftState { OFF, ON, LOCK }

    var listener: Listener? = null
    var hapticsEnabled: Boolean = true

    var shiftState: ShiftState = ShiftState.OFF
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var page: KeyPage = KeyLayouts.letters

    // Flattened key geometry, rebuilt on size/page change.
    private class KeyCell(val key: Key, val rect: RectF)

    private val cells = ArrayList<KeyCell>()
    private var pressedCell: KeyCell? = null
    private var spaceLongPressFired = false

    // Not named `handler`: that would JVM-signature-clash with View.getHandler().
    private val timerHandler = Handler(Looper.getMainLooper())
    private val deleteRepeater = object : Runnable {
        override fun run() {
            listener?.onDelete()
            timerHandler.postDelayed(this, DELETE_REPEAT_MS)
        }
    }
    private val spaceLongPress = Runnable {
        spaceLongPressFired = true
        listener?.onSpaceLongPress()
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT_COLOR
        textAlign = Paint.Align.CENTER
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics,
    )

    private fun sp(v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics,
    )

    init {
        setBackgroundColor(BACKGROUND_COLOR)
        isClickable = true
    }

    fun setPage(p: KeyPage) {
        page = p
        rebuildCells()
        invalidate()
    }

    fun currentPage(): PageId = page.id

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight =
            (page.rows.size * dp(ROW_HEIGHT_DP) + 2 * dp(V_PADDING_DP)).toInt()
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rebuildCells()
    }

    private fun rebuildCells() {
        cells.clear()
        pressedCell = null
        if (width == 0) return
        val hPad = dp(H_PADDING_DP)
        val vPad = dp(V_PADDING_DP)
        val rowHeight = dp(ROW_HEIGHT_DP)
        val usableWidth = width - 2 * hPad
        page.rows.forEachIndexed { rowIndex, row ->
            val totalWeight = row.keys.sumOf { it.weight.toDouble() }.toFloat() +
                2 * row.edgePadding
            if (totalWeight <= 0f) return@forEachIndexed
            val unit = usableWidth / totalWeight
            var x = hPad + row.edgePadding * unit
            val top = vPad + rowIndex * rowHeight
            for (key in row.keys) {
                val w = key.weight * unit
                cells.add(KeyCell(key, RectF(x, top, x + w, top + rowHeight)))
                x += w
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = dp(KEY_GAP_DP)
        val radius = dp(KEY_RADIUS_DP)
        for (cell in cells) {
            val key = cell.key
            keyPaint.color = when {
                cell === pressedCell -> PRESSED_COLOR
                key.code == KeyCode.SHIFT && shiftState != ShiftState.OFF -> ACTIVE_COLOR
                key.code == KeyCode.CHAR -> KEY_COLOR
                else -> SPECIAL_COLOR
            }
            val r = RectF(
                cell.rect.left + gap,
                cell.rect.top + gap,
                cell.rect.right - gap,
                cell.rect.bottom - gap,
            )
            canvas.drawRoundRect(r, radius, radius, keyPaint)

            val label = labelFor(key)
            if (label.isEmpty()) continue
            textPaint.textSize = if (key.code == KeyCode.CHAR) sp(20f) else sp(14f)
            val baseline = r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(label, r.centerX(), baseline, textPaint)
        }
    }

    private fun labelFor(key: Key): String = when (key.code) {
        KeyCode.CHAR ->
            if (page.id == PageId.LETTERS && shiftState != ShiftState.OFF) {
                key.label.uppercase()
            } else {
                key.label
            }
        KeyCode.SHIFT -> if (shiftState == ShiftState.LOCK) "⇪" else "⇧"
        else -> key.label
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cell = cellAt(event.x, event.y) ?: return true
                pressedCell = cell
                spaceLongPressFired = false
                if (hapticsEnabled) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                when (cell.key.code) {
                    KeyCode.DELETE -> {
                        listener?.onDelete()
                        timerHandler.postDelayed(deleteRepeater, DELETE_INITIAL_MS)
                    }
                    KeyCode.SPACE ->
                        timerHandler.postDelayed(spaceLongPress, LONG_PRESS_MS)
                    else -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val cell = cellAt(event.x, event.y)
                if (cell !== pressedCell) {
                    // Sliding off a key cancels it (and any pending repeats).
                    cancelTimers()
                    pressedCell = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val cell = pressedCell
                cancelTimers()
                pressedCell = null
                invalidate()
                if (cell != null) dispatchUp(cell.key)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelTimers()
                pressedCell = null
                invalidate()
            }
        }
        return true
    }

    private fun dispatchUp(key: Key) {
        when (key.code) {
            KeyCode.CHAR -> listener?.onChar(key.output)
            KeyCode.SHIFT -> listener?.onShiftTapped()
            KeyCode.ENTER -> listener?.onEnter()
            KeyCode.SPACE -> if (!spaceLongPressFired) listener?.onSpace()
            // DELETE already fired on DOWN (and repeats while held).
            KeyCode.DELETE -> Unit
            KeyCode.PAGE_LETTERS -> listener?.onPageRequested(PageId.LETTERS)
            KeyCode.PAGE_SYMBOLS -> listener?.onPageRequested(PageId.SYMBOLS)
            KeyCode.PAGE_SYMBOLS2 -> listener?.onPageRequested(PageId.SYMBOLS2)
        }
    }

    private fun cancelTimers() {
        timerHandler.removeCallbacks(deleteRepeater)
        timerHandler.removeCallbacks(spaceLongPress)
    }

    override fun onDetachedFromWindow() {
        cancelTimers()
        super.onDetachedFromWindow()
    }

    private fun cellAt(x: Float, y: Float): KeyCell? =
        cells.firstOrNull { it.rect.contains(x, y) }

    private companion object {
        const val ROW_HEIGHT_DP = 56f
        const val H_PADDING_DP = 4f
        const val V_PADDING_DP = 6f
        const val KEY_GAP_DP = 3f
        const val KEY_RADIUS_DP = 8f
        const val DELETE_INITIAL_MS = 350L
        const val DELETE_REPEAT_MS = 60L
        const val LONG_PRESS_MS = 500L

        val BACKGROUND_COLOR = Color.parseColor("#0B0B0B")
        val KEY_COLOR = Color.parseColor("#1F1F23")
        val SPECIAL_COLOR = Color.parseColor("#2A2A30")
        val PRESSED_COLOR = Color.parseColor("#3E9FC0")
        val ACTIVE_COLOR = Color.parseColor("#255E73")
        val TEXT_COLOR = Color.parseColor("#E6E6E6")
    }
}
