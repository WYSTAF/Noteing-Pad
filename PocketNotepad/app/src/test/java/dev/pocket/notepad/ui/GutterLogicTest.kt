package dev.pocket.notepad.ui

import dev.pocket.notepad.model.Rope
import org.junit.Assert.*
import org.junit.Test

/**
 * Simulates a wrapped document: logical line A (offsets 0..19) occupies visual
 * rows [0,10) [10,20); newline at 19; logical line B (20..39) rows [20,30)
 * [30,40); newline at 39. `starts`/`ends` mirror what Layout would report.
 */
class GutterLogicTest {
    private val text = "a".repeat(19) + "\n" + "b".repeat(19) + "\n" + "c"
    private val root = Rope.of(text)
    // visible window: all five rows
    private val starts = intArrayOf(0, 10, 20, 30, 40)
    private val ends = intArrayOf(10, 20, 30, 40, 41)

    @Test fun selectionOnSecondWrappedRowActivatesItsLogicalLine() {
        val active = GutterLogic.activate(root, starts, ends, 12, 15)
        assertEquals(listOf(true, false, false, false, false), active.toList())
    }

    @Test fun selectionAcrossRowsOfSameLineActivatesOnce() {
        val active = GutterLogic.activate(root, starts, ends, 10, 18)
        assertTrue(active[0])
    }

    @Test fun caretAnywhereInLineActivatesWholeLine() {
        for (offset in listOf(3, 10, 15, 18)) {
            val active = GutterLogic.activate(root, starts, ends, offset, offset)
            assertTrue("offset $offset", active[0])
        }
    }

    @Test fun selectionInSecondLogicalLineDoesNotTouchFirst() {
        val active = GutterLogic.activate(root, starts, ends, 32, 36)
        assertEquals(listOf(false, false, true, false, false), active.toList())
    }

    @Test fun selectionSpanningTwoLinesActivatesBoth() {
        val active = GutterLogic.activate(root, starts, ends, 15, 25)
        assertTrue(active[0]); assertTrue(active[2]); assertFalse(active[4])
    }

    @Test fun caretOnTrailingSingleRowLineActivatesIt() {
        val active = GutterLogic.activate(root, starts, ends, 40, 40)
        assertTrue(active[4])
    }

    @Test fun caretAtDocumentEndActivatesLastLine() {
        val active = GutterLogic.activate(root, starts, ends, text.length, text.length)
        assertTrue(active[4])
    }
}
