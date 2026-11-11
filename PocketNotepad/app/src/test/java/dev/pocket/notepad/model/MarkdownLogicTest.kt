package dev.pocket.notepad.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLogicTest {

    @Test
    fun boldWrapsSelectedTextAndTogglesOffWhenAlreadyWrapped() {
        val doc = "hello world"
        val wrap = MarkdownLogic.computeEdit(doc, Selection(6, 11), MarkdownAction.BOLD)
        assertEquals(6, wrap.replaceStart)
        assertEquals(11, wrap.replaceEnd)
        assertEquals("**world**", wrap.replacement)
        assertEquals(Selection(8, 13), wrap.nextSelection)

        val wrappedDoc = "hello **world**"
        val unwrap = MarkdownLogic.computeEdit(wrappedDoc, Selection(8, 13), MarkdownAction.BOLD)
        assertEquals(6, unwrap.replaceStart)
        assertEquals(15, unwrap.replaceEnd)
        assertEquals("world", unwrap.replacement)
        assertEquals(Selection(6, 11), unwrap.nextSelection)
    }

    @Test
    fun collapsedBoldPlacesCaretBetweenStars() {
        val edit = MarkdownLogic.computeEdit("abc", Selection(3), MarkdownAction.BOLD)
        assertEquals("****", edit.replacement)
        assertEquals(Selection(5), edit.nextSelection)
    }

    @Test
    fun headingReplacesOrTogglesAtLineStart() {
        val h1 = MarkdownLogic.computeEdit("Title line", Selection(4), MarkdownAction.H1)
        assertEquals(0, h1.replaceStart)
        assertEquals("# Title line", h1.replacement)

        val toH2 = MarkdownLogic.computeEdit("# Title line", Selection(5), MarkdownAction.H2)
        assertEquals("## Title line", toH2.replacement)

        val toggleOff = MarkdownLogic.computeEdit("## Title line", Selection(5), MarkdownAction.H2)
        assertEquals("Title line", toggleOff.replacement)
    }

    @Test
    fun alignmentBlockWrapsSelectionAndParsesToAlignedBlocks() {
        val edit = MarkdownLogic.computeEdit("Centered Title", Selection(0, 14), MarkdownAction.ALIGN_CENTER)
        assertEquals("<!--align:center-->\nCentered Title\n<!--/align-->", edit.replacement)

        val blocks = MarkdownLogic.parseBlocks(edit.replacement)
        assertEquals(1, blocks.size)
        val p = blocks[0] as MarkdownBlock.Paragraph
        assertEquals("Centered Title", p.text)
        assertEquals(TextAlignMode.CENTER, p.align)
    }

    @Test
    fun taskParsingProvidesExactBracketOffsetForLiveToggling() {
        val doc = "# Tasks\n- [ ] Buy milk\n- [x] Call Alex"
        val blocks = MarkdownLogic.parseBlocks(doc)
        assertEquals(3, blocks.size)
        val t1 = blocks[1] as MarkdownBlock.TaskItem
        val t2 = blocks[2] as MarkdownBlock.TaskItem
        assertTrue(!t1.checked)
        assertTrue(t2.checked)
        assertEquals(' ', doc[t1.bracketOffset])
        assertEquals('x', doc[t2.bracketOffset])
    }
}
