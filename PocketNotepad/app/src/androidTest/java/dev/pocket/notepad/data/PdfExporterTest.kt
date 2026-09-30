package dev.pocket.notepad.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocket.notepad.model.Rope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfExporterTest {
    private fun output(): File = File.createTempFile("pdf-test-", ".pdf",
        InstrumentationRegistry.getInstrumentation().targetContext.cacheDir)

    @Test fun emptyDocumentIsAValidSinglePagePdf() = runBlocking {
        val file = output()
        try {
            PdfExporter.export(Rope.Empty, file, "Empty.pdf") {}
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { assertEquals(1, it.pageCount) }
            }
        } finally { file.delete() }
    }
    @Test fun longTextPaginatesAndPdfRendererCanOpenEveryPage() = runBlocking {
        val file = output()
        try {
            val text = buildString {
                repeat(180) { append("Line $it\tمرحبا\r\n") }
                append("unbroken".repeat(2500))
                append(String(Character.toChars(0x1F680)))
            }
            var lastPage = 0
            PdfExporter.export(Rope.of(text), file, "Long.pdf") { lastPage = it }
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    assertTrue(renderer.pageCount > 1)
                    assertEquals(lastPage, renderer.pageCount)
                    repeat(renderer.pageCount) { index ->
                        renderer.openPage(index).use { page ->
                            assertEquals(595, page.width)
                            assertEquals(842, page.height)
                        }
                    }
                }
            }
            val header = file.inputStream().use { input ->
                val bytes = ByteArray(5)
                java.io.DataInputStream(input).readFully(bytes)
                bytes.toString(Charsets.US_ASCII)
            }
            assertEquals("%PDF-", header)
        } finally { file.delete() }
    }
}
