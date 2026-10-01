package dev.pocket.notepad.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.GetChars
import android.text.InputFilter
import android.text.Layout
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Scroller
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.pocket.notepad.NotepadViewModel
import dev.pocket.notepad.R
import dev.pocket.notepad.model.EditorUiState
import dev.pocket.notepad.model.Limits
import dev.pocket.notepad.model.Rope
import dev.pocket.notepad.model.Selection

/**
 * Framework EditText whose scroll stack was tuned empirically across several
 * builds (see the touch/scroller block below) and whose line numbers it draws
 * itself, on its own canvas, so they can never lag the text.
 */
class NotepadEditText(context: Context, attrs: AttributeSet?) : EditText(context, attrs) {
    var selectionChanged: ((Int, Int) -> Unit)? = null
    var pasteAction: (() -> Unit)? = null
    var undoAction: (() -> Unit)? = null
    var redoAction: (() -> Unit)? = null
    var saveAction: (() -> Unit)? = null
    var newAction: (() -> Unit)? = null
    var openAction: (() -> Unit)? = null

    // Line numbers are painted by THIS view, inside its own onDraw pass: the
    // same canvas, the same frame, the same scroll offset as the text. A
    // sibling gutter View required a cross-view invalidate every scroll frame
    // — those pokes were being coalesced/skipped on some devices, freezing the
    // numbers until the next selection change. There is no such window here.
    private val density = resources.displayMetrics.density
    // Gutter sized for a 4-digit column ("9999") with room for the compact
    // 10k / 10.6k / 34.8k forms beyond it — never widens past these.
    private val gutterWidthPx = 40f * density       // band: numbers right-align here
    private val textLeftPadPx = 56f * density       // 16dp breathing room after band
    private val edgePadPx = 16f * density           // matches editor_view.xml padding
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        // Explicit init: Paint defaults to opaque BLACK, which combined with
        // the old setter guard (initial field == dark-mode #999999) left the
        // paint black on OLED — invisible numbers in dark mode.
        color = 0xFF999999.toInt()
        typeface = Typeface.MONOSPACE
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.RIGHT
    }
    private val activePaint = Paint(numberPaint).apply { color = 0xFFFF0000.toInt() }
    var numbersColor = 0xFF999999.toInt()
        // No change-guard: Paint.color assignment is free, and the guard was
        // the bug (a sentinel default can equal the real first value). Editor
        // host's own muted-cache prevents redundant invalidates.
        set(value) { field = value; numberPaint.color = value; invalidateGutter() }
    var showNumbers = false
        set(value) {
            if (field != value) {
                field = value
                setPadding(
                    if (value) textLeftPadPx.toInt() else edgePadPx.toInt(),
                    paddingTop, paddingRight, paddingBottom
                )
                invalidate()
            }
        }
    // Rope the current Layout was built from — bind() and edits keep this in
    // lockstep with the text buffer so wrap classification never races (the
    // duplicated-"1" bug). Never read model state during draw.
    var numberSource: Rope = Rope.Empty

    // ---- Draggable scroll handle ------------------------------------------
    // Framework scrollbars on TextView/ScrollView are INDICATORS — Android has
    // never made them grabbable there (it's a known framework gap; only the
    // ListView family got thumb hit-testing). The styled thumb shipped in
    // v1.5.x was therefore decorative no matter how it was configured. This
    // is a real one: drawn by this view, hit-tested in onTouchEvent, drives
    // scrollTo directly. Tap the track to jump, grab the thumb to scrub.
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66808080 }
    private val thumbActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB0606060.toInt() }
    private val thumbRectScratch = android.graphics.RectF()
    private var scrubbingThumb = false
    private var thumbGrabOffsetY = 0f
    private val thumbWidthPx get() = 5f * density
    private val thumbEdgePx get() = 2f * density
    private val thumbHitPx get() = 18f * density      // generous grab target

    /** Fill rect and return true when content is scrollable. */
    private fun currentThumbRect(rect: android.graphics.RectF): Boolean {
        val textLayout = layout ?: return false
        val max = textLayout.height + totalPaddingTop + totalPaddingBottom - height
        if (max <= 0) return false
        val trackTop = totalPaddingTop.toFloat()
        val trackBottom = (height - totalPaddingBottom).toFloat()
        val trackH = trackBottom - trackTop
        val visible = height.toFloat() / (max + height.toFloat())
        val thumbH = (trackH * visible).coerceAtLeast(40f * density)
        val travel = trackH - thumbH
        val y = trackTop + (scrollY.toFloat() / max) * travel
        rect.set(width - thumbEdgePx - thumbWidthPx, y, width - thumbEdgePx, y + thumbH)
        return true
    }

    private fun drawScrollHandle(canvas: Canvas) {
        if (!currentThumbRect(thumbRectScratch)) return
        val paint = if (scrubbingThumb) thumbActivePaint else thumbPaint
        val left = thumbRectScratch.left
        canvas.drawRoundRect(left, thumbRectScratch.top, thumbRectScratch.right,
            thumbRectScratch.bottom, 2.5f * density, 2.5f * density, paint)
    }

    private fun applyThumbScrub(y: Float) {
        val textLayout = layout ?: return
        val max = textLayout.height + totalPaddingTop + totalPaddingBottom - height
        if (max <= 0) return
        val trackTop = totalPaddingTop.toFloat()
        val trackH = (height - totalPaddingBottom - totalPaddingTop).toFloat()
        val visible = height.toFloat() / (max + height.toFloat())
        val thumbH = (trackH * visible).coerceAtLeast(40f * density)
        val travel = (trackH - thumbH).coerceAtLeast(1f)
        val frac = ((y - thumbGrabOffsetY - trackTop) / travel).coerceIn(0f, 1f)
        scrollTo(0, (frac * max).toInt())
    }

    // Zero-allocation draw path: lint's DrawAllocation fired here, and at
    // 120 Hz a scroll frame is 8.3 ms — GC churn from per-frame arrays is
    // exactly the kind of cost that reads as "stiff". Buffers sized to the
    // viewport's visual line count (a few dozen rows; ~4096 covers even
    // pathological 12-line visible runs) and reused across frames.
    private var lineStartsBuf = IntArray(256)
    private var lineEndsBuf = IntArray(256)
    private var groupActiveBuf = BooleanArray(256)
    private val numberScratch = StringBuilder(6)

    /**
     * Gutter labels: 1..9999 verbatim, then compact k form (10000 → "10k",
     * 10600 → "10.6k"). One decimal at most, and the digit count is stable
     * across a given magnitude — combined with the MONOSPACE paint this keeps
     * the column width constant while scrolling, which is what the user
     * reported as "jiggling".
     */
    private fun writeLineNumber(value: Int): CharSequence {
        numberScratch.setLength(0)
        if (value < 10000) {
            numberScratch.append(value)
        } else {
            val k = value / 1000
            val tenth = (value % 1000) / 100
            numberScratch.append(k)
            if (tenth != 0) numberScratch.append('.').append(tenth)
            numberScratch.append('k')
        }
        return numberScratch
    }

    /** Quintic ease-out: fast launch, long glide tail. */
    private val quinticEaseOut = android.view.animation.Interpolator { t ->
        val inv = 1f - t
        1f - inv * inv * inv * inv * inv
    }

    /**
     * Scroll physics: QUINTIC fling — the v1.4.2 shape the user A/B'd against
     * stock and kept (stock spline felt stiff). The v1.5.2+ hybrid mirrored an
     * OverScroller into the base Scroller by calling startScroll(0,0) every
     * frame; startScroll restarts the base clock, so the two curves fought —
     * momentum mushed out (stiff) and the position jittered between curves,
     * which the gutter numbers made visible as wobble. One scroller, one
     * curve: the interpolator is handed to the base constructor, so the
     * framework's own fling rides the quintic profile end to end.
     */
    private class QuinticFlingScroller(context: Context, interpolator: android.view.animation.Interpolator,
                                      private val boost: () -> Float)
        : Scroller(context, interpolator) {
        override fun fling(startX: Int, startY: Int, velocityX: Int, velocityY: Int,
                           minX: Int, maxX: Int, minY: Int, maxY: Int) {
            // Boost lengthens the glide; the interpolator shapes its feel.
            super.fling(startX, startY,
                (velocityX * boost()).toInt(), (velocityY * boost()).toInt(),
                minX, maxX, minY, maxY)
        }
    }

    /**
     * Fixed tuning, chosen from the user's own reports (scroll that dies on
     * release) — the on-device dials are gone. FLING_BOOST > 1 lengthens the
     * glide; the deceleration shape is the v1.4.2 quintic the user kept.
     */
    private var flingBoost = 1.6f
    private var unbufferedTouch = true
    // 120Hz hint back ON: the user's v1.4.2 A/B verdict was "quintic fling +
    // 120Hz hint felt right; stock felt stiff" — v1.5.x had it off.
    private var highRateHint = true
        set(value) {
            field = value
            if (!value) requestHighRate(false)   // drop any active hint immediately
        }
    private val glideScroller = QuinticFlingScroller(context, quinticEaseOut) { flingBoost }
    private var wantsHighRate = false

    /** Frame telemetry: armed during touch/fling, reports once per gesture. */
    val frameHealth = FrameHealth()
    var onScrollStats: ((fps: Int, longFrames: Int) -> Unit)? = null
    private val disarmMeter = Runnable {
        frameHealth.setArmed(false)
        onScrollStats?.invoke(frameHealth.fps, frameHealth.longFrames)
    }

    init {
        setScroller(glideScroller)
    }

    fun cancelGlide() = glideScroller.abortAnimation()

    private fun requestHighRate(active: Boolean) {
        if (Build.VERSION.SDK_INT < 35 || active == wantsHighRate) return
        wantsHighRate = active
        setRequestedFrameRate(if (active) 120f else 0f)
    }

    override fun computeScroll() {
        super.computeScroll()
        // Glide ended inside this call; with no finger down, let the panel idle
        // and report the gesture's frame health once.
        if (glideScroller.isFinished && !isPressed && !isHovered) {
            requestHighRate(false)
            scheduleDisarm()
        }
    }

    /**
     * Observational touch override: unbuffered drag + refresh-rate hint only,
     * both switchable. Clicks/caret still go to EditText (super), so no custom
     * performClick path exists and the accessibility lint heuristic does not
     * apply.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Handle strip first: a grab here must never place the caret.
                if (event.x >= width - thumbHitPx && currentThumbRect(thumbRectScratch)) {
                    scrubbingThumb = true
                    thumbGrabOffsetY = if (event.y < thumbRectScratch.top ||
                        event.y > thumbRectScratch.bottom) thumbRectScratch.height() / 2f
                        else event.y - thumbRectScratch.top      // grab, keep offset
                    removeCallbacks(disarmMeter)
                    frameHealth.setArmed(true)
                    applyThumbScrub(event.y)
                    return true
                }
                removeCallbacks(disarmMeter)
                frameHealth.setArmed(true)
                if (unbufferedTouch && Build.VERSION.SDK_INT >= 28) requestUnbufferedDispatch(event)
                if (highRateHint) requestHighRate(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrubbingThumb) { applyThumbScrub(event.y); return true }
                if (unbufferedTouch && Build.VERSION.SDK_INT >= 28) requestUnbufferedDispatch(event)
                if (highRateHint) requestHighRate(true)
            }
            MotionEvent.ACTION_UP -> {
                if (scrubbingThumb) { scrubbingThumb = false; invalidateBands(); scheduleDisarm(); return true }
                if (glideScroller.isFinished) { requestHighRate(false); scheduleDisarm() }
            }
            MotionEvent.ACTION_CANCEL -> {
                scrubbingThumb = false
                requestHighRate(false); scheduleDisarm()
            }
        }
        return super.onTouchEvent(event)
    }

    private fun scheduleDisarm() {
        if (!frameHealth.isArmed) return
        removeCallbacks(disarmMeter)
        postDelayed(disarmMeter, 150)
    }

    private fun invalidateGutter() = invalidate(0, 0, textLeftPadPx.toInt(), height)

    /** Redraw both side bands: line numbers (left) and scroll handle (right). */
    private fun invalidateBands() {
        invalidate(0, 0, textLeftPadPx.toInt(), height)
        invalidate((width - thumbHitPx).toInt(), 0, width, height)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        // Keep the handle glued to reality during drag/fling/caret scrolls,
        // and line numbers current — one region invalidate covers both bands.
        if (showNumbers || currentThumbRect(thumbRectScratch)) invalidateBands()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (showNumbers) invalidateGutter()
        selectionChanged?.invoke(selStart, selEnd)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)   // stock text render, stock scroll behavior
        drawScrollHandle(canvas)
        if (!showNumbers) return
        val textLayout = layout ?: return
        val root = numberSource
        val total = root.length
        if (total == 0 || textLayout.lineCount == 0) return
        val firstLine = textLayout.getLineForVertical(scrollY)
        val lastLine = (textLayout.getLineForVertical(scrollY + height) + 1)
            .coerceAtMost(textLayout.lineCount - 1)
        if (lastLine < firstLine) return
        val count = lastLine - firstLine + 1
        // Reuse viewport-sized buffers; grow only when a genuinely larger
        // window appears (font-scale change), never per frame.
        if (count > lineStartsBuf.size) {
            lineStartsBuf = IntArray(count)
            lineEndsBuf = IntArray(count)
            groupActiveBuf = BooleanArray(count)
        }
        val starts = lineStartsBuf
        val ends = lineEndsBuf
        for (i in 0 until count) {
            starts[i] = textLayout.getLineStart(firstLine + i).coerceAtMost(total)
            ends[i] = textLayout.getLineEnd(firstLine + i).coerceAtMost(total)
        }
        val selA = selectionStart
        val selB = selectionEnd
        if (selA >= 0 && selB >= 0)
            GutterLogic.activateInto(root, starts, ends, selA, selB, count, groupActiveBuf)
        else java.util.Arrays.fill(groupActiveBuf, 0, count, false)
        var number = if (starts[0] == 0) 1 else root.newlinesBefore(starts[0]) + 1
        for (i in 0 until count) {
            // Soft-wrapped continuations deliberately get no number.
            if (starts[i] == 0 || root[starts[i] - 1] == '\n') {
                // The framework already translated this canvas by -scrollY before
                // onDraw ran (that's why super.onDraw places text without scroll
                // math) — subtracting scrollY again would double-shift numbers.
                val baseline = textLayout.getLineBaseline(firstLine + i) + totalPaddingTop
                val paint = if (groupActiveBuf[i]) activePaint else numberPaint
                // CharSequence overload: the scratch StringBuilder is one, so
                // labels cost zero fresh Strings on the scroll hot path.
                val label = writeLineNumber(number)
                canvas.drawText(label, 0, label.length,
                    gutterWidthPx, baseline.toFloat(), paint)
                number++
            }
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.paste, android.R.id.pasteAsPlainText -> { pasteAction?.invoke(); true }
        android.R.id.undo -> { undoAction?.invoke(); true }
        android.R.id.redo -> { redoAction?.invoke(); true }
        else -> super.onTextContextMenuItem(id)
    }
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_Z -> { if (event.isShiftPressed) redoAction?.invoke() else undoAction?.invoke(); return true }
                KeyEvent.KEYCODE_Y -> { redoAction?.invoke(); return true }
                KeyEvent.KEYCODE_V -> { pasteAction?.invoke(); return true }
                KeyEvent.KEYCODE_S -> { saveAction?.invoke(); return true }
                KeyEvent.KEYCODE_N -> { newAction?.invoke(); return true }
                KeyEvent.KEYCODE_O -> { openAction?.invoke(); return true }
            }
        }
        return super.onKeyShortcut(keyCode, event)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !event.isShiftPressed && isEnabled) {
            val from = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
            val to = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
            text?.replace(from, to, "\t")
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

private class RopeText(private val rope: Rope) : CharSequence, GetChars {
    override val length: Int get() = rope.length
    override fun get(index: Int): Char = rope[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = RopeText(rope.subSequence(startIndex, endIndex))
    override fun getChars(start: Int, end: Int, dest: CharArray, destoff: Int) = rope.copyTo(dest, destoff, start, end)
    override fun toString(): String = rope.toString()
}

private class EditorHost(context: Context, private val model: NotepadViewModel) : LinearLayout(context) {
    val editor = LayoutInflater.from(context).inflate(R.layout.editor_view, this, false) as NotepadEditText
    private var synchronizing = false
    private var boundRevision = -1L
    private var beforeSelection = Selection()
    private val density = resources.displayMetrics.density

    // Applied-theme cache: bind() runs on every keystroke, but theme writes
    // (setTextColor et al.) force full repaints and must only run on change.
    private var boundInk = 0
    private var boundMuted = 0
    private var boundSurface = 0
    private var boundHighlight = 0

    init {
        orientation = HORIZONTAL
        isSaveEnabled = false
        editor.isSaveEnabled = false
        editor.breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE
        editor.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        editor.setLineSpacing(4f * density, 1f)
        editor.setHorizontallyScrolling(false)
        editor.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        editor.contentDescription = "Text editor"
        if (Build.VERSION.SDK_INT >= 29) editor.setTextCursorDrawable(R.drawable.caret)
        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        editor.filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
            if (synchronizing) null
            else if (model.blocked || boundRevision != model.ui.value.revision) dest.subSequence(dstart, dend)
            else if (dest.length.toLong() - (dend - dstart) + (end - start) > Limits.MAX_CHARACTERS) {
                model.notify(Limits.SIZE_MESSAGE)
                dest.subSequence(dstart, dend)
            } else if ((start until end).any { source[it] == 0.toChar() }) {
                model.notify("Binary NUL characters are not supported.")
                dest.subSequence(dstart, dend)
            } else null
        })
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (!synchronizing) {
                    // Typing always beats momentum: a custom scroller misses the
                    // framework's implicit fling-cancel on text changes.
                    editor.cancelGlide()
                    beforeSelection = Selection(editor.selectionStart, editor.selectionEnd)
                }
            }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (synchronizing || model.blocked || boundRevision != model.ui.value.revision || s == null) return
                val inserted = s.subSequence(start, start + count)
                // A rejected InputFilter replacement can still generate an unchanged callback.
                val old = model.ui.value.root
                if (before == count && (0 until count).all { old[start + it] == inserted[it] }) return
                boundRevision = model.nativeEdit(start, before, inserted, beforeSelection)
                editor.numberSource = model.ui.value.root
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        editor.selectionChanged = { start, end ->
            if (!synchronizing && boundRevision == model.ui.value.revision) model.select(start, end)
        }
        editor.pasteAction = model::paste
        editor.undoAction = model::undo
        editor.redoAction = model::redo
    }

    fun bind(state: EditorUiState, ink: Int, muted: Int, surface: Int, highlight: Int) {
        if (surface != boundSurface) { setBackgroundColor(surface); boundSurface = surface }
        if (ink != boundInk) { editor.setTextColor(ink); boundInk = ink }
        if (muted != boundMuted) {
            editor.setHintTextColor(muted)
            editor.numbersColor = muted
            boundMuted = muted
        }
        if (highlight != boundHighlight) { editor.highlightColor = highlight; boundHighlight = highlight }
        editor.showNumbers = state.lineNumbers
        val enabled = !state.busy && state.pendingExport == null
        if (editor.isEnabled != enabled) editor.isEnabled = enabled
        if (boundRevision != state.revision) {
            synchronizing = true
            try {
                editor.text?.let { BaseInputConnection.removeComposingSpans(it) }
                val patch = state.patch
                if (patch != null && patch.fromRevision == boundRevision) {
                    editor.text?.replace(patch.start, patch.start + patch.removedLength, RopeText(patch.inserted))
                    val chosen = patch.selection.bounded(state.root.length)
                    editor.setSelection(chosen.start, chosen.end)
                } else {
                    editor.setText(RopeText(state.root), TextView.BufferType.EDITABLE)
                    val chosen = model.selection.bounded(state.root.length)
                    editor.setSelection(chosen.start, chosen.end)
                }
                boundRevision = state.revision
                editor.numberSource = state.root
                if (editor.hasFocus()) context.getSystemService(InputMethodManager::class.java)?.restartInput(editor)
            } finally { synchronizing = false }
        }
    }
}

@Composable
fun NativeEditor(
    model: NotepadViewModel, state: EditorUiState, modifier: Modifier = Modifier,
    onSave: () -> Unit, onNew: () -> Unit, onOpen: () -> Unit,
    onScrollStats: (fps: Int, longFrames: Int) -> Unit = { _, _ -> }
) {
    val colors = MaterialTheme.colorScheme
    AndroidView(
        factory = { EditorHost(it, model) }, modifier = modifier,
        update = { host ->
            host.editor.saveAction = onSave
            host.editor.newAction = onNew
            host.editor.openAction = onOpen
            host.editor.onScrollStats = onScrollStats
            host.bind(state, colors.onSurface.toArgb(), colors.onSurfaceVariant.toArgb(),
                colors.surface.toArgb(), colors.secondaryContainer.toArgb())
        }
    )
}
