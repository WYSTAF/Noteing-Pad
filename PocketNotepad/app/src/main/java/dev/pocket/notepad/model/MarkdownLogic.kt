package dev.pocket.notepad.model

enum class MarkdownAction {
    BOLD,
    ITALIC,
    STRIKE,
    CODE,
    H1,
    H2,
    H3,
    BULLET,
    NUMBERED,
    CHECKLIST,
    QUOTE,
    ALIGN_LEFT,
    ALIGN_CENTER,
    ALIGN_RIGHT,
    LINK,
    TABLE,
    DIVIDER
}

data class MarkdownEdit(
    val replaceStart: Int,
    val replaceEnd: Int,
    val replacement: String,
    val nextSelection: Selection
)

enum class TextAlignMode { LEFT, CENTER, RIGHT }

sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String, val align: TextAlignMode = TextAlignMode.LEFT) : MarkdownBlock()
    data class Paragraph(val text: String, val align: TextAlignMode = TextAlignMode.LEFT) : MarkdownBlock()
    data class BulletItem(val text: String, val align: TextAlignMode = TextAlignMode.LEFT) : MarkdownBlock()
    data class NumberedItem(val number: String, val text: String, val align: TextAlignMode = TextAlignMode.LEFT) : MarkdownBlock()
    data class TaskItem(val checked: Boolean, val text: String, val lineIndex: Int, val bracketOffset: Int) : MarkdownBlock()
    data class Quote(val text: String) : MarkdownBlock()
    data class CodeBlock(val language: String, val code: String) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
    data object Divider : MarkdownBlock()
}

object MarkdownLogic {

    /**
     * Computes a localized patch & exact resulting caret/selection for any
     * Markdown action — wrapping selections, toggling existing markers, and
     * placing the caret inside empty wrappers rather than past them.
     */
    fun computeEdit(doc: CharSequence, rawSelection: Selection, action: MarkdownAction): MarkdownEdit {
        val len = doc.length
        val bounded = rawSelection.bounded(len)
        val selStart = minOf(bounded.start, bounded.end)
        val selEnd = maxOf(bounded.start, bounded.end)

        return when (action) {
            MarkdownAction.BOLD -> wrapInline(doc, selStart, selEnd, "**")
            MarkdownAction.ITALIC -> wrapInline(doc, selStart, selEnd, "*")
            MarkdownAction.STRIKE -> wrapInline(doc, selStart, selEnd, "~~")
            MarkdownAction.CODE -> {
                val selected = doc.subSequence(selStart, selEnd).toString()
                if (selStart < selEnd && !selected.contains('\n')) {
                    wrapInline(doc, selStart, selEnd, "`")
                } else if (selStart < selEnd) {
                    val block = "```\n$selected\n```"
                    val prefix = if (selStart > 0 && doc[selStart - 1] != '\n') "\n" else ""
                    val suffix = if (selEnd < len && doc[selEnd] != '\n') "\n" else ""
                    val full = "$prefix$block$suffix"
                    val innerStart = selStart + prefix.length + 4
                    MarkdownEdit(selStart, selEnd, full, Selection(innerStart, innerStart + selected.length))
                } else {
                    val prefix = if (selStart > 0 && doc[selStart - 1] != '\n') "\n" else ""
                    val snippet = "${prefix}```\n\n```"
                    val caret = selStart + prefix.length + 4
                    MarkdownEdit(selStart, selEnd, snippet, Selection(caret))
                }
            }
            MarkdownAction.H1 -> toggleLinePrefix(doc, selStart, selEnd, "# ", stripHeadings = true)
            MarkdownAction.H2 -> toggleLinePrefix(doc, selStart, selEnd, "## ", stripHeadings = true)
            MarkdownAction.H3 -> toggleLinePrefix(doc, selStart, selEnd, "### ", stripHeadings = true)
            MarkdownAction.BULLET -> prefixLines(doc, selStart, selEnd) { line, _ ->
                if (line.startsWith("- ")) line.removePrefix("- ")
                else if (line.startsWith("• ")) "- " + line.removePrefix("• ")
                else "- $line"
            }
            MarkdownAction.NUMBERED -> prefixLines(doc, selStart, selEnd) { line, idx ->
                val numMatch = Regex("^\\d+\\.\\s").find(line)
                if (numMatch != null) line.substring(numMatch.value.length)
                else "${idx + 1}. $line"
            }
            MarkdownAction.CHECKLIST -> prefixLines(doc, selStart, selEnd) { line, _ ->
                when {
                    line.startsWith("- [ ] ") -> "- [x] " + line.removePrefix("- [ ] ")
                    line.startsWith("- [x] ") || line.startsWith("- [X] ") -> line.substring(6)
                    else -> "- [ ] $line"
                }
            }
            MarkdownAction.QUOTE -> prefixLines(doc, selStart, selEnd) { line, _ ->
                if (line.startsWith("> ")) line.removePrefix("> ") else "> $line"
            }
            MarkdownAction.ALIGN_LEFT -> wrapAlignment(doc, selStart, selEnd, "left")
            MarkdownAction.ALIGN_CENTER -> wrapAlignment(doc, selStart, selEnd, "center")
            MarkdownAction.ALIGN_RIGHT -> wrapAlignment(doc, selStart, selEnd, "right")
            MarkdownAction.LINK -> {
                if (selStart < selEnd) {
                    val label = doc.subSequence(selStart, selEnd).toString()
                    val rep = "[$label](https://)"
                    val urlStart = selStart + label.length + 3
                    MarkdownEdit(selStart, selEnd, rep, Selection(urlStart, urlStart + 8))
                } else {
                    val rep = "[text](https://)"
                    MarkdownEdit(selStart, selEnd, rep, Selection(selStart + 1, selStart + 5))
                }
            }
            MarkdownAction.TABLE -> {
                val prefix = if (selStart > 0 && doc[selStart - 1] != '\n') "\n" else ""
                val table = "| Column A | Column B |\n| --- | --- |\n| Cell | Cell |\n"
                val rep = "$prefix$table"
                val firstCellStart = selStart + prefix.length + 2
                MarkdownEdit(selStart, selEnd, rep, Selection(firstCellStart, firstCellStart + 8))
            }
            MarkdownAction.DIVIDER -> {
                val prefix = if (selStart > 0 && doc[selStart - 1] != '\n') "\n" else ""
                val suffix = if (selEnd < len && doc[selEnd] != '\n') "\n" else ""
                val rep = "$prefix---\n$suffix"
                MarkdownEdit(selStart, selEnd, rep, Selection(selStart + prefix.length + 4))
            }
        }
    }

    private fun wrapInline(doc: CharSequence, selStart: Int, selEnd: Int, marker: String): MarkdownEdit {
        val mLen = marker.length
        if (selStart == selEnd) {
            // If caret is already sitting directly inside matching markers, step out cleanly
            if (selStart >= mLen && selStart + mLen <= doc.length) {
                val before = doc.subSequence(selStart - mLen, selStart).toString()
                val after = doc.subSequence(selStart, selStart + mLen).toString()
                if (before == marker && after == marker) {
                    return MarkdownEdit(selStart - mLen, selStart + mLen, "", Selection(selStart - mLen))
                }
            }
            return MarkdownEdit(selStart, selEnd, "$marker$marker", Selection(selStart + mLen))
        }
        val selected = doc.subSequence(selStart, selEnd).toString()
        // Toggle off if selection itself is wrapped
        if (selected.length >= mLen * 2 && selected.startsWith(marker) && selected.endsWith(marker)) {
            val inner = selected.substring(mLen, selected.length - mLen)
            return MarkdownEdit(selStart, selEnd, inner, Selection(selStart, selStart + inner.length))
        }
        // Toggle off if surrounding text holds the markers
        if (selStart >= mLen && selEnd + mLen <= doc.length) {
            val before = doc.subSequence(selStart - mLen, selStart).toString()
            val after = doc.subSequence(selEnd, selEnd + mLen).toString()
            if (before == marker && after == marker) {
                return MarkdownEdit(selStart - mLen, selEnd + mLen, selected, Selection(selStart - mLen, selEnd - mLen))
            }
        }
        val wrapped = "$marker$selected$marker"
        return MarkdownEdit(selStart, selEnd, wrapped, Selection(selStart + mLen, selEnd + mLen))
    }

    private fun lineBounds(doc: CharSequence, start: Int, end: Int): Pair<Int, Int> {
        var lineStart = start
        while (lineStart > 0 && doc[lineStart - 1] != '\n') lineStart--
        var lineEnd = end
        if (lineEnd > lineStart && lineEnd <= doc.length && doc[lineEnd - 1] == '\n') {
            lineEnd--
        } else {
            while (lineEnd < doc.length && doc[lineEnd] != '\n') lineEnd++
        }
        return lineStart to lineEnd
    }

    private fun toggleLinePrefix(
        doc: CharSequence,
        selStart: Int,
        selEnd: Int,
        prefix: String,
        stripHeadings: Boolean
    ): MarkdownEdit {
        val (lineStart, lineEnd) = lineBounds(doc, selStart, selEnd)
        val line = doc.subSequence(lineStart, lineEnd).toString()
        val existing = Regex("^(#{1,6}\\s+)").find(line)?.value
        val updated = when {
            existing == prefix -> line.removePrefix(prefix)
            stripHeadings && existing != null -> prefix + line.substring(existing.length)
            else -> prefix + line
        }
        val delta = updated.length - line.length
        val newCaret = (selEnd + delta).coerceIn(lineStart, lineStart + updated.length)
        return MarkdownEdit(lineStart, lineEnd, updated, Selection(newCaret))
    }

    private fun prefixLines(
        doc: CharSequence,
        selStart: Int,
        selEnd: Int,
        transform: (String, Int) -> String
    ): MarkdownEdit {
        val (lineStart, lineEnd) = lineBounds(doc, selStart, selEnd)
        val block = doc.subSequence(lineStart, lineEnd).toString()
        val lines = block.split('\n')
        val out = lines.mapIndexed { idx, l -> transform(l, idx) }.joinToString("\n")
        val nextSel = if (selStart == selEnd) {
            val delta = out.length - block.length
            val caret = (selEnd + delta).coerceIn(lineStart, lineStart + out.length)
            Selection(caret)
        } else {
            Selection(lineStart, lineStart + out.length)
        }
        return MarkdownEdit(lineStart, lineEnd, out, nextSel)
    }

    private fun wrapAlignment(doc: CharSequence, selStart: Int, selEnd: Int, align: String): MarkdownEdit {
        val startTag = "<!--align:$align-->"
        val endTag = "<!--/align-->"
        if (selStart == selEnd) {
            val (lineStart, lineEnd) = lineBounds(doc, selStart, selEnd)
            val line = doc.subSequence(lineStart, lineEnd).toString()
            return if (line.isBlank()) {
                val rep = "$startTag\n\n$endTag"
                val caret = selStart + startTag.length + 1
                MarkdownEdit(selStart, selEnd, rep, Selection(caret))
            } else {
                val rep = "$startTag\n$line\n$endTag"
                val innerStart = lineStart + startTag.length + 1
                MarkdownEdit(lineStart, lineEnd, rep, Selection(innerStart, innerStart + line.length))
            }
        }
        val (lineStart, lineEnd) = lineBounds(doc, selStart, selEnd)
        val inner = doc.subSequence(lineStart, lineEnd).toString()
        val rep = "$startTag\n$inner\n$endTag"
        val innerStart = lineStart + startTag.length + 1
        return MarkdownEdit(lineStart, lineEnd, rep, Selection(innerStart, innerStart + inner.length))
    }

    // ---- Markdown Block Parser (for Live Preview & PDF Alignment) ----------

    private val ALIGN_START = Regex("^<!--\\s*align:(left|center|right)\\s*-->$", RegexOption.IGNORE_CASE)
    private val ALIGN_END = Regex("^<!--\\s*/align\\s*-->$", RegexOption.IGNORE_CASE)
    private val HEADING_RE = Regex("^(#{1,3})\\s+(.*)$")
    private val TASK_RE = Regex("^[-*]\\s+\\[([ xX])\\]\\s+(.*)$")
    private val BULLET_RE = Regex("^(?:[-*]|•)\\s+(.*)$")
    private val NUMBER_RE = Regex("^(\\d+)\\.\\s+(.*)$")
    private val DIVIDER_RE = Regex("^(-{3,}|\\*{3,}|_{3,})$")

    fun parseBlocks(source: String): List<MarkdownBlock> {
        val blocks = ArrayList<MarkdownBlock>()
        val lines = source.split('\n')
        var align = TextAlignMode.LEFT
        var i = 0
        var offset = 0

        fun lineOffsetAt(idx: Int): Int {
            var pos = 0
            for (k in 0 until idx) pos += lines[k].length + 1
            return pos
        }

        while (i < lines.size) {
            val raw = lines[i].trimEnd('\r')
            val trimmed = raw.trim()

            // Alignment tags
            val startMatch = ALIGN_START.matchEntire(trimmed)
            if (startMatch != null) {
                align = when (startMatch.groupValues[1].lowercase()) {
                    "center" -> TextAlignMode.CENTER
                    "right" -> TextAlignMode.RIGHT
                    else -> TextAlignMode.LEFT
                }
                i++
                continue
            }
            if (ALIGN_END.matches(trimmed)) {
                align = TextAlignMode.LEFT
                i++
                continue
            }

            // Fenced code blocks
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = ArrayList<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    codeLines += lines[i].trimEnd('\r')
                    i++
                }
                if (i < lines.size) i++ // consume closing ```
                blocks += MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n"))
                continue
            }

            if (trimmed.isEmpty()) {
                i++
                continue
            }

            if (DIVIDER_RE.matches(trimmed)) {
                blocks += MarkdownBlock.Divider
                i++
                continue
            }

            val hMatch = HEADING_RE.matchEntire(trimmed)
            if (hMatch != null) {
                blocks += MarkdownBlock.Heading(hMatch.groupValues[1].length, hMatch.groupValues[2], align)
                i++
                continue
            }

            val tMatch = TASK_RE.matchEntire(trimmed)
            if (tMatch != null) {
                val lineStart = lineOffsetAt(i)
                val bracketIdx = raw.indexOf('[')
                val bracketOffset = if (bracketIdx >= 0) lineStart + bracketIdx + 1 else lineStart
                blocks += MarkdownBlock.TaskItem(
                    checked = tMatch.groupValues[1].equals("x", ignoreCase = true),
                    text = tMatch.groupValues[2],
                    lineIndex = i,
                    bracketOffset = bracketOffset
                )
                i++
                continue
            }

            val bMatch = BULLET_RE.matchEntire(trimmed)
            if (bMatch != null) {
                blocks += MarkdownBlock.BulletItem(bMatch.groupValues[1], align)
                i++
                continue
            }

            val nMatch = NUMBER_RE.matchEntire(trimmed)
            if (nMatch != null) {
                blocks += MarkdownBlock.NumberedItem(nMatch.groupValues[1], nMatch.groupValues[2], align)
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                val quoteLines = ArrayList<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoteLines += lines[i].trim().removePrefix(">").trimStart()
                    i++
                }
                blocks += MarkdownBlock.Quote(quoteLines.joinToString("\n"))
                continue
            }

            // Markdown table detection: header line with | followed by separator |---|
            if (trimmed.contains('|') && i + 1 < lines.size &&
                Regex("^\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)+\\|?$").matches(lines[i + 1].trim())
            ) {
                fun splitCells(r: String): List<String> =
                    r.trim().trim('|').split('|').map { it.trim() }
                val headers = splitCells(trimmed)
                i += 2
                val rows = ArrayList<List<String>>()
                while (i < lines.size && lines[i].trim().contains('|') && lines[i].trim().isNotEmpty()) {
                    rows += splitCells(lines[i])
                    i++
                }
                blocks += MarkdownBlock.Table(headers, rows)
                continue
            }

            // Standard paragraph (coalesce consecutive plain lines inside same alignment/paragraph)
            val paraLines = ArrayList<String>()
            while (i < lines.size) {
                val cur = lines[i].trimEnd('\r').trim()
                if (cur.isEmpty() || ALIGN_START.matches(cur) || ALIGN_END.matches(cur) ||
                    cur.startsWith("```") || DIVIDER_RE.matches(cur) ||
                    HEADING_RE.matches(cur) || TASK_RE.matches(cur) ||
                    BULLET_RE.matches(cur) || NUMBER_RE.matches(cur) || cur.startsWith(">")
                ) break
                paraLines += cur
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(paraLines.joinToString(" "), align)
            }
            offset++
        }
        return blocks
    }
}
