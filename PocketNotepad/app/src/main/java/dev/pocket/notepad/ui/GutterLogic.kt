package dev.pocket.notepad.ui

import java.util.Arrays
import dev.pocket.notepad.model.Rope

/**
 * Which gutter numbers light up for a selection — pure function, unit tested.
 * A logical line activates when the selection (or collapsed caret) touches ANY
 * of its visual rows, including soft-wrapped continuations. Activation is
 * computed by walking from the bottom: a touched continuation marks the chain,
 * the numbered row above consumes it, and every numbered row resets the chain
 * so activity never leaks between logical lines.
 */
object GutterLogic {
    /** A visual row starts a logical line when offset 0 or right after a newline. */
    fun numbered(root: Rope, start: Int): Boolean =
        start == 0 || root[start - 1] == '\n'

    /** Allocation-free hot path: the editor's onDraw reuses its own buffers. */
    fun activateInto(root: Rope, starts: IntArray, ends: IntArray,
                     rawSelStart: Int, rawSelEnd: Int,
                     count: Int, out: BooleanArray) {
        val total = root.length
        val selStart = minOf(rawSelStart, rawSelEnd).coerceIn(0, total)
        val selEnd = maxOf(rawSelStart, rawSelEnd).coerceIn(0, total)
        Arrays.fill(out, 0, count, false)
        var continuationTouched = false
        for (i in count - 1 downTo 0) {
            val continuation = !numbered(root, starts[i])
            val touched = if (selEnd > selStart) {
                ends[i] > selStart && starts[i] < selEnd
            } else {
                // Collapsed caret: belongs to the row that contains it; a caret
                // at the very end of the document belongs to the last row.
                selStart in starts[i] until ends[i] ||
                    (i == count - 1 && selStart == ends[i])
            }
            if (continuation) {
                if (touched) continuationTouched = true
            } else {
                out[i] = touched || continuationTouched
                continuationTouched = false
            }
        }
    }

    /** Convenience allocation version, kept for tests. */
    fun activate(root: Rope, starts: IntArray, ends: IntArray,
                 rawSelStart: Int, rawSelEnd: Int): BooleanArray {
        val out = BooleanArray(starts.size)
        activateInto(root, starts, ends, rawSelStart, rawSelEnd, starts.size, out)
        return out
    }
}
