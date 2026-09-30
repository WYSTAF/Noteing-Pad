package dev.pocket.notepad.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the v1.7.0 launch crash: the icon path parser only
 * matched M/L/C/Z, so a relative 'v' or 'h' was swallowed as a stray number,
 * the token stream desynchronised, and it threw IndexOutOfBoundsException on
 * the first icon drawn.
 *
 * These tests run on the JVM against parseSegments(), which has no Android
 * dependency — android.graphics.Path is a stub in unit tests and throws on any
 * call, so the geometry is verified before it ever reaches a Canvas.
 */
class IconPathParsingTest {

    @Test
    fun relativeCommandsAreRecognisedNotSwallowed() {
        // The exact shape that used to break: "v599.5" after a moveto.
        val segs = parseSegments("M162,212.25v599.5c0,55.23,44.77,100,100,100", strict = true)
        assertEquals(3, segs.size)
        assertEquals('M', segs[0].command)
        assertEquals('V', segs[1].command)
        assertEquals(811.75f, segs[1].args[0], 0.01f)   // 212.25 + 599.5
        assertEquals('C', segs[2].command)
        assertEquals(6, segs[2].args.size)
    }

    @Test
    fun relativeHorizontalsAccumulate() {
        val segs = parseSegments("M10,10h20h30", strict = true)
        assertEquals(3, segs.size)
        assertEquals(30f, segs[1].args[0], 0.01f)
        assertEquals(60f, segs[2].args[0], 0.01f)
    }

    @Test
    fun everyIconSourceParsesCompletelyWithoutTruncation() {
        for (symbol in EditorSymbol.entries) {
            val sources = sourcesFor(symbol)
            for ((index, source) in sources.withIndex()) {
                val segs = parseSegments(source, strict = true)
                assertTrue("$symbol slot $index produced no geometry", segs.isNotEmpty())
                assertTrue("$symbol slot $index must close or end at a point",
                    segs.last().command == 'Z' || segs.last().command == 'C' ||
                        segs.last().command == 'L' || segs.last().command == 'V' ||
                        segs.last().command == 'H')
            }
        }
    }

    @Test
    fun truncatedInputStopsCleanlyInsteadOfThrowing() {
        // Total-by-construction: a partial command ends the parse, no throw.
        for (bad in listOf("M10", "M10,10v", "M10,10c1,2,3", "Z", "M1,1L2", "v5", "h")) {
            parseSegments(bad)   // must not throw
        }
    }

    @Test
    fun closingPathReturnsCursorToSubpathStart() {
        // A 'Z' must reset the cursor, so a following relative move starts from
        // the subpath origin rather than the last point.
        val segs = parseSegments("M100,100h50Z h10", strict = true)
        val afterClose = segs.last { it.command == 'H' && it.args[0] == 110f }
        assertEquals(110f, afterClose.args[0], 0.01f)
    }
}
