package dev.pocket.notepad.model

object Limits {
    const val MAX_CHARACTERS = 2 * 1024 * 1024
    const val MAX_HISTORY_UNITS = 4 * 1024 * 1024
    const val MAX_HISTORY_ENTRIES = 200
    const val MAX_PDF_PAGES = 250
    // Sharing sends real files through FileProvider now — no Binder text cap.
    const val TRASH_RETENTION_DAYS = 30
    const val SIZE_MESSAGE = "This editor accepts up to 2,097,152 UTF-16 units. Nothing was truncated."
}
data class Selection(val start: Int = 0, val end: Int = start) {
    fun bounded(length: Int) = Selection(start.coerceIn(0, length), end.coerceIn(0, length))
}
data class Edit(
    val before: Rope, val after: Rope, val start: Int,
    val removed: Rope, val inserted: Rope,
    val beforeSelection: Selection, val afterSelection: Selection
) {
    val cost: Int get() = removed.length + inserted.length
}
/** Budget is conservative changed-text size, not an exact heap measurement. */
class EditHistory {
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()
    private var cost = 0
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    fun record(edit: Edit) {
        redo.forEach { cost -= it.cost }
        redo.clear()
        undo.addLast(edit)
        cost += edit.cost
        while (undo.size + redo.size > Limits.MAX_HISTORY_ENTRIES ||
            (cost > Limits.MAX_HISTORY_UNITS && undo.size > 1)) {
            cost -= undo.removeFirst().cost
        }
    }
    fun undo(): Edit? = if (undo.isEmpty()) null else undo.removeLast().also { redo.addLast(it) }
    fun redo(): Edit? = if (redo.isEmpty()) null else redo.removeLast().also { undo.addLast(it) }
    fun clear() { undo.clear(); redo.clear(); cost = 0 }
}
data class EditorPatch(
    val fromRevision: Long, val start: Int, val removedLength: Int,
    val inserted: Rope, val selection: Selection
)
enum class ThemeMode(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }
data class ExportSpec(val fileName: String) {
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
    val isPdf: Boolean get() = extension == "pdf"
    val mimeType: String get() = when (extension) {
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "csv" -> "text/csv"
        "js", "mjs" -> "text/javascript"
        "py" -> "text/x-python"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }
    fun validationError(): String? = when {
        fileName.isBlank() -> "Enter a file name."
        fileName != fileName.trim() -> "Remove leading or trailing spaces."
        fileName.length > 200 -> "Use a name of 200 characters or fewer."
        fileName == "." || fileName == ".." -> "Choose a different file name."
        fileName.any { it == '/' || it == '\\' || it.isISOControl() } -> "Use a file name, not a folder path."
        else -> null
    }
}
enum class Screen { LIBRARY, EDITOR, TRASH }
/** One row of the library list; header-only read, content stays on disk. */
data class NoteMeta(
    val id: String, val title: String, val updatedAt: Long,
    val characters: Int, val preview: String,
    /** Deletion instant for trash rows; 0 for library rows. */
    val deletedAt: Long = 0
)
data class Draft(
    val root: Rope, val title: String, val selection: Selection,
    val noteId: String = "", val generation: Long = 0, val revision: Long = 0
)
data class PendingExport(
    val stageName: String, val spec: ExportSpec, val sourceRevision: Long,
    val session: String, val pickerLaunched: Boolean = false,
    val noteId: String = ""
)
data class EditorUiState(
    val root: Rope = Rope.Empty, val revision: Long = 0, val patch: EditorPatch? = null,
    val title: String = "Untitled", val noteId: String = "",
    /** A manual rename sticks for this note; auto-name only fills untitled notes. */
    val titleIsManual: Boolean = false,
    val busy: Boolean = true, val activity: String = "Loading library",
    val canUndo: Boolean = false, val canRedo: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM, val lineNumbers: Boolean = false,
    val autoName: Boolean = true, val pendingExport: PendingExport? = null,
    /** Nothing Glyph master switch + whether this device actually has Glyphs. */
    val glyph: Boolean = false, val glyphAvailable: Boolean = false,
    val screen: Screen = Screen.LIBRARY
) {
    /** Logical lines in the document (LF count + 1 for a non-empty body). */
    val totalLines: Int get() = if (root.isEmpty()) 0 else root.newlines + 1
}
