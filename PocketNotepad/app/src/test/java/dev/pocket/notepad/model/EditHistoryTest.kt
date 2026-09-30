package dev.pocket.notepad.model

import org.junit.Assert.*
import org.junit.Test

class EditHistoryTest {
    private fun edit(before: Rope, text: String): Edit {
        val inserted = Rope.of(text)
        val after = before.replace(before.length, before.length, inserted)
        return Edit(before, after, before.length, Rope.Empty, inserted,
            Selection(before.length), Selection(after.length))
    }
    @Test fun undoRedoAndBranching() {
        val history = EditHistory()
        val first = edit(Rope.Empty, "hello")
        val second = edit(first.after, " world")
        history.record(first)
        history.record(second)
        assertSame(second, history.undo())
        assertSame(second, history.redo())
        assertSame(second, history.undo())
        history.record(edit(first.after, " again"))
        assertFalse(history.canRedo)
        assertEquals("hello again", history.undo()!!.after.toString())
        history.clear()
        assertFalse(history.canUndo)
    }
    @Test fun entryBudgetEvictsOldHistory() {
        val history = EditHistory()
        var rope: Rope = Rope.Empty
        repeat(230) {
            val next = edit(rope, "a")
            history.record(next)
            rope = next.after
        }
        var count = 0
        while (history.undo() != null) count++
        assertEquals(Limits.MAX_HISTORY_ENTRIES, count)
    }
}
