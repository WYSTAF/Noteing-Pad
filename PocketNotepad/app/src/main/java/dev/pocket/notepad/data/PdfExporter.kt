package dev.pocket.notepad.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils
import dev.pocket.notepad.model.Limits
import dev.pocket.notepad.model.Rope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File

/** A4, 40pt margins, bounded 8192-unit layout windows, no full-document layout. */
object PdfExporter {
    suspend fun export(root: Rope, target: File, title: String, onPage: (Int) -> Unit) =
        withContext(Dispatchers.Default) {
            val document = PdfDocument()
            var page: PdfDocument.Page? = null
            var pageNumber = 0
            var y = 64f
            val ink = Color.rgb(28, 32, 26)
            val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.MONOSPACE
                textSize = 10f
                color = ink
            }
            val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT
                textSize = 9f
                color = Color.rgb(96, 104, 91)
            }
            val contentWidth = 515
            val bottom = 790f
            fun nextPage() {
                page?.let { document.finishPage(it) }
                page = null
                require(pageNumber < Limits.MAX_PDF_PAGES) {
                    "PDF exceeds ${Limits.MAX_PDF_PAGES} pages. Export as text or split the document."
                }
                pageNumber++
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
                val canvas = page!!.canvas
                val titleText = TextUtils.ellipsize(title, labelPaint, contentWidth.toFloat(), TextUtils.TruncateAt.END)
                canvas.drawText(titleText.toString(), 40f, 36f, labelPaint)
                canvas.drawText(pageNumber.toString(), 40f, 818f, labelPaint)
                y = 64f
                onPage(pageNumber)
            }
            val job = currentCoroutineContext()
            try {
                nextPage()
                NormalizedReader(root.reader().buffered()).use { reader ->
                    val pending = StringBuilder(8200)
                    var eof = false
                    while (true) {
                        job.ensureActive()
                        while (pending.length < 8192 && !eof) {
                            val value = reader.next()
                            if (value < 0) eof = true else pending.append(value.toChar())
                        }
                        // A layout window must not end between a surrogate pair.
                        if (!eof && pending.isNotEmpty() && pending.last().isHighSurrogate()) {
                            val value = reader.next()
                            if (value < 0) eof = true else pending.append(value.toChar())
                        }
                        val window = pending.toString()
                        val layout = StaticLayout.Builder.obtain(window, 0, window.length, textPaint, contentWidth)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                            .setIncludePad(false)
                            .setLineSpacing(3f, 1f)
                            .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
                            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                            .build()
                        // Keep the last potentially incomplete visual line for the next window.
                        val renderCount = if (eof) layout.lineCount else layout.lineCount - 1
                        check(renderCount > 0) { "The PDF layout could not make progress." }
                        var line = 0
                        while (line < renderCount) {
                            job.ensureActive()
                            val top = layout.getLineTop(line)
                            var end = line
                            while (end < renderCount &&
                                y + layout.getLineBottom(end) - top <= bottom) end++
                            if (end == line) {
                                check(y != 64f) { "A text line is taller than a PDF page." }
                                nextPage()
                                continue
                            }
                            val height = layout.getLineBottom(end - 1) - top
                            val canvas = page!!.canvas
                            val saved = canvas.save()
                            try {
                                canvas.clipRect(40f, y, 555f, y + height)
                                canvas.translate(40f, y - top)
                                layout.draw(canvas)
                            } finally {
                                canvas.restoreToCount(saved)
                            }
                            y += height
                            line = end
                        }
                        if (eof) break
                        val consumed = layout.getLineStart(renderCount)
                        check(consumed > 0)
                        pending.delete(0, consumed)
                    }
                }
                page?.let { document.finishPage(it) }
                page = null
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    target.outputStream().buffered().use { document.writeTo(it) }
                }
            } finally {
                try { page?.let { document.finishPage(it) } }
                finally { document.close() }
            }
        }

    /** Normalizes line endings and expands tab stops only for PDF, never for text export. */
    private class NormalizedReader(private val input: BufferedReader) : AutoCloseable {
        private var skipLf = false
        private var column = 0
        private var spaces = 0
        private var first = true
        fun next(): Int {
            if (spaces > 0) { spaces--; column++; return ' '.code }
            while (true) {
                val value = input.read()
                if (value < 0) return -1
                if (first) {
                    first = false
                    if (value == 0xFEFF) continue
                }
                if (skipLf) {
                    skipLf = false
                    if (value == '\n'.code) continue
                }
                when (value) {
                    '\r'.code -> { skipLf = true; column = 0; return '\n'.code }
                    '\n'.code -> { column = 0; return value }
                    '\t'.code -> {
                        spaces = 4 - column % 4 - 1
                        column++
                        return ' '.code
                    }
                    else -> { column++; return value }
                }
            }
        }
        override fun close() = input.close()
    }
}
