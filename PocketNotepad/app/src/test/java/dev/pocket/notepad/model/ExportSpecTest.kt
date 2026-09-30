package dev.pocket.notepad.model

import org.junit.Assert.*
import org.junit.Test

class ExportSpecTest {
    @Test fun choosesMimeByExtension() {
        assertTrue(ExportSpec("Report.PDF").isPdf)
        assertEquals("application/pdf", ExportSpec("Report.PDF").mimeType)
        assertEquals("text/x-python", ExportSpec("script.py").mimeType)
        assertEquals("application/octet-stream", ExportSpec("notes.custom").mimeType)
        assertEquals("application/octet-stream", ExportSpec(".env").mimeType)
        assertNull(ExportSpec(".env").validationError())
        assertNull(ExportSpec("README").validationError())
    }
    @Test fun rejectsPathsAndControlCharacters() {
        assertNotNull(ExportSpec("../a.txt").validationError())
        assertNotNull(ExportSpec("x\\y.txt").validationError())
        assertNotNull(ExportSpec("bad\nname").validationError())
        assertNotNull(ExportSpec(" ").validationError())
        assertNotNull(ExportSpec(".").validationError())
        assertNotNull(ExportSpec("a".repeat(201)).validationError())
    }
}
