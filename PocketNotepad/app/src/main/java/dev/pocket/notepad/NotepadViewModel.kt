package dev.pocket.notepad

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.pocket.notepad.data.FileExporter
import dev.pocket.notepad.glyph.GlyphSupport
import dev.pocket.notepad.data.NotepadRepository
import dev.pocket.notepad.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.CharacterCodingException
import java.util.UUID

@OptIn(FlowPreview::class)
class NotepadViewModel(application: Application, private val savedState: SavedStateHandle) :
    AndroidViewModel(application) {
    private val repository = NotepadRepository(application)
    private val exporter = FileExporter(application)
    private val preferences = application.getSharedPreferences("editor", Context.MODE_PRIVATE)
    private val history = EditHistory()
    private val session = UUID.randomUUID().toString()
    private val _ui = MutableStateFlow(EditorUiState())
    val ui = _ui.asStateFlow()
    private val _notes = MutableStateFlow<List<NoteMeta>>(emptyList())
    val notes = _notes.asStateFlow()
    private val _trash = MutableStateFlow<List<NoteMeta>>(emptyList())
    val trash = _trash.asStateFlow()
    private val messageChannel = Channel<String>(Channel.BUFFERED)
    val messages = messageChannel.receiveAsFlow()
    private val saveRequests = MutableStateFlow<Draft?>(null)
    private var generation = 0L
    private var savedRope: Rope? = null
    private var initialized = false
    private var saveFailureReported = false
    var selection = Selection()
        private set
    val blocked: Boolean get() = _ui.value.busy || _ui.value.pendingExport != null

    init {
        viewModelScope.launch {
            saveRequests.filterNotNull().debounce(700).collectLatest { persist(it) }
        }
        viewModelScope.launch {
            try {
                GlyphSupport.init(getApplication())
                GlyphSupport.setEnabled(preferences.getBoolean("glyph", true))
                val options = withContext(Dispatchers.IO) {
                    val mode = runCatching {
                        ThemeMode.valueOf(preferences.getString("theme", "SYSTEM") ?: "SYSTEM")
                    }.getOrDefault(ThemeMode.SYSTEM)
                    object {
                        val theme = mode
                        val lineNumbers = preferences.getBoolean("line_numbers", false)
                        val autoName = preferences.getBoolean("auto_name", true)
                    }
                }
                _ui.update {
                    it.copy(theme = options.theme, lineNumbers = options.lineNumbers,
                        autoName = options.autoName,
                        // Observable now: the TopBar toggle reads this state, so
                        // tapping it visibly flips. Previously a plain getter
                        // re-read the pref but nothing recomposed — the switch
                        // looked permanently off no matter how often you tapped.
                        glyph = preferences.getBoolean("glyph", true) && GlyphSupport.available,
                        glyphAvailable = GlyphSupport.available)
                }
                val pending = restorePending()
                exporter.cleanOrphans(pending?.stageName)
                if (pending != null && exporter.exists(pending.stageName)) {
                    // A staged export survives process recreation; reopen its editor.
                    val note = pending.noteId.takeIf { it.isNotEmpty() }?.let { id ->
                        runCatching { repository.openNote(id) }.getOrNull()
                    }
                    if (note != null) {
                        savedRope = note.root
                        _ui.update { it.copy(root = note.root, title = note.title, noteId = note.noteId,
                            screen = Screen.EDITOR, revision = it.revision + 1, patch = null)
                        }
                        selection = note.selection.bounded(note.root.length)
                    }
                    _ui.update { it.copy(pendingExport = pending) }
                } else clearPendingKeys()
                refreshNotes()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Library could not load: ${friendly(failure)}")
            } finally {
                initialized = true
                _ui.update { it.copy(busy = false, activity = "") }
            }
        }
    }

    fun notify(message: String) { messageChannel.trySend(message) }
    fun select(start: Int, end: Int) {
        selection = Selection(start, end).bounded(_ui.value.root.length)
        // Mirror the caret's place in the note to the Glyph in real time.
        val total = _ui.value.root.length
        if (total > 0) {
            val percent = (end * 100 / total).coerceIn(0, 100)
            GlyphSupport.onScrollProgress(percent)
        }
    }

    /** Called synchronously by the native editor; only inserted text is copied. */
    fun nativeEdit(start: Int, removed: Int, inserted: CharSequence, beforeSelection: Selection): Long {
        val rope = Rope.of(inserted)
        commit(start, start + removed, rope, beforeSelection)
        return _ui.value.revision
    }

    private fun commit(start: Int, end: Int, inserted: Rope, beforeSelection: Selection = selection) {
        if (start == end && inserted.isEmpty()) return
        val state = _ui.value
        require(state.root.length - (end - start) + inserted.length <= Limits.MAX_CHARACTERS) { Limits.SIZE_MESSAGE }
        val removed = state.root.subSequence(start, end)
        val next = state.root.replace(start, end, inserted)
        val afterSelection = Selection(start + inserted.length)
        history.record(Edit(state.root, next, start, removed, inserted, beforeSelection, afterSelection))
        selection = afterSelection
        val newTitle = if (state.autoName && !state.titleIsManual) autoTitle(next) else state.title
        _ui.value = state.copy(root = next, title = newTitle, revision = state.revision + 1,
            patch = EditorPatch(state.revision, start, end - start, inserted, afterSelection),
            canUndo = history.canUndo, canRedo = history.canRedo)
        requestSave()
    }

    /** The document's first non-blank line becomes its name while auto-name is on. */
    private fun autoTitle(root: Rope): String {
        if (root.isEmpty()) return "Untitled"
        val size = minOf(300, root.length)
        val buffer = CharArray(size)
        root.copyTo(buffer, 0, 0, size)
        val line = String(buffer).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.isEmpty()) "Untitled" else line.take(60)
    }

    fun undo() {
        if (blocked) return
        val edit = history.undo() ?: return
        val state = _ui.value
        selection = edit.beforeSelection.bounded(edit.before.length)
        _ui.value = state.copy(root = edit.before, revision = state.revision + 1,
            title = if (state.autoName && !state.titleIsManual) autoTitle(edit.before) else state.title,
            patch = EditorPatch(state.revision, edit.start, edit.inserted.length, edit.removed, selection),
            canUndo = history.canUndo, canRedo = history.canRedo)
        requestSave()
    }
    fun redo() {
        if (blocked) return
        val edit = history.redo() ?: return
        val state = _ui.value
        selection = edit.afterSelection.bounded(edit.after.length)
        _ui.value = state.copy(root = edit.after, revision = state.revision + 1,
            title = if (state.autoName && !state.titleIsManual) autoTitle(edit.after) else state.title,
            patch = EditorPatch(state.revision, edit.start, edit.removed.length, edit.inserted, selection),
            canUndo = history.canUndo, canRedo = history.canRedo)
        requestSave()
    }
    fun clear() {
        if (!blocked) commit(0, _ui.value.root.length, Rope.Empty)
    }

    /**
     * Inserts a Markdown building block at the caret, replacing any selection.
     * The app is a plain-text editor on purpose (no renderer), so these insert
     * Markdown SOURCE — the same bytes a .md file would contain — for a file
     * that is (or becomes) .md.
     */
    fun insertMarkdown(snippet: String) {
        if (blocked) return
        val chosen = selection.bounded(_ui.value.root.length)
        val start = minOf(chosen.start, chosen.end)
        val end = maxOf(chosen.start, chosen.end)
        if (_ui.value.root.length - (end - start) + snippet.length > Limits.MAX_CHARACTERS) {
            notify(Limits.SIZE_MESSAGE)
            return
        }
        commit(start, end, Rope.of(snippet), chosen)
    }

    fun newNote() {
        if (blocked) return
        flushPendingSave()
        val id = repository.nextId()
        savedRope = Rope.Empty
        selection = Selection()
        history.clear()
        _ui.value = _ui.value.copy(root = Rope.Empty, title = "Untitled", noteId = id,
            titleIsManual = false,
            revision = _ui.value.revision + 1, patch = null, canUndo = false, canRedo = false,
            screen = Screen.EDITOR, busy = false, activity = "")
        // No save here: the note materializes on disk only when it first gets
        // content (commit -> requestSave). Leaving a blank note stores nothing.
    }

    fun openNote(id: String) {
        if (blocked) return
        flushPendingSave()
        _ui.update { it.copy(busy = true, activity = "Opening note") }
        viewModelScope.launch {
            try {
                val draft = repository.openNote(id)
                savedRope = draft.root
                selection = draft.selection.bounded(draft.root.length)
                history.clear()
                _ui.value = _ui.value.copy(root = draft.root, title = draft.title, noteId = draft.noteId,
                    titleIsManual = false,
                    revision = _ui.value.revision + 1, patch = null, canUndo = false, canRedo = false,
                    screen = Screen.EDITOR)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Note could not open: ${friendly(failure)}")
            } finally { _ui.update { it.copy(busy = false, activity = "") } }
        }
    }

    /** Leaves the editor for the library; saves are debounced, so flush first. */
    fun backToLibrary() {
        flushPendingSave()
        viewModelScope.launch {
            try { refreshNotes() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Library refresh failed: ${friendly(failure)}")
            }
        }
        _ui.update { it.copy(screen = Screen.LIBRARY) }
    }

    private fun refreshNotes() {
        viewModelScope.launch { _notes.value = repository.listNotes() }
    }

    fun deleteNote(id: String) {
        if (blocked) return
        viewModelScope.launch {
            try {
                repository.deleteNote(id)
                GlyphSupport.blink()
                if (_ui.value.noteId == id) {
                    // The editor was showing this note; leave it to the library.
                    savedRope = null
                    _ui.update { it.copy(screen = Screen.LIBRARY) }
                }
                refreshNotes()
                refreshTrash()
                notify("Moved to trash. Kept for ${Limits.TRASH_RETENTION_DAYS} days.")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Delete failed: ${friendly(failure)}")
            }
        }
    }

    fun openTrash() {
        if (blocked) return
        flushPendingSave()
        _ui.update { it.copy(screen = Screen.TRASH) }
        refreshTrash()
    }
    private fun refreshTrash() {
        viewModelScope.launch {
            try { _trash.value = repository.listTrash() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Trash could not load: ${friendly(failure)}")
            }
        }
    }
    fun restoreNote(id: String) {
        if (blocked) return
        viewModelScope.launch {
            try {
                repository.restoreNote(id)
                refreshNotes()
                refreshTrash()
                notify("Note restored to the library.")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Restore failed: ${friendly(failure)}")
            }
        }
    }
    fun deleteForever(id: String) {
        if (blocked) return
        viewModelScope.launch {
            try {
                repository.deleteForever(id)
                refreshTrash()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Could not erase the note: ${friendly(failure)}")
            }
        }
    }
    fun emptyTrash() {
        if (blocked) return
        viewModelScope.launch {
            try {
                repository.emptyTrash()
                refreshTrash()
                notify("Trash emptied.")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Trash could not be emptied: ${friendly(failure)}")
            }
        }
    }

    /**
     * Shares the document as an actual file (not a text blob): stages it in
     * app-private storage, then hands a read-only content URI to the system
     * chooser. extension: "txt", "md", or "pdf".
     */
    fun shareFile(extension: String) {
        if (blocked) return
        val source = _ui.value
        if (source.root.isEmpty()) { notify("There is nothing to share yet."); return }
        val spec = ExportSpec(suggestedFileName(extension))
        spec.validationError()?.let { notify(it); return }
        _ui.update { it.copy(busy = true,
            activity = if (spec.isPdf) "Preparing PDF share" else "Preparing file share") }
        viewModelScope.launch {
            var shareDir: java.io.File? = null
            try {
                GlyphSupport.onShareProgress(10)
                val file = exporter.stageForShare(source.root, spec) { page ->
                    _ui.update { it.copy(activity = "Preparing PDF, page $page") }
                }
                shareDir = file.parentFile
                GlyphSupport.onShareProgress(70)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    getApplication(), "dev.pocket.notepad.fileprovider", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = spec.mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, source.title)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // Required: started from the Application context, so the
                // chooser needs NEW_TASK, and the grant rides on both intents.
                val chooser = Intent.createChooser(send, "Share file").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                getApplication<Application>().startActivity(chooser)
                GlyphSupport.onShareDone()
                // Recipients hold a read grant; the staged copy survives until
                // the next startup sweep (cleanOrphans deletes share dirs too).
                shareDir = null
            } catch (failure: ActivityNotFoundException) {
                notify("No app on this device can receive a file.")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Share failed: ${friendly(failure)}")
            } finally {
                if (shareDir != null) withContext(Dispatchers.IO) { shareDir.deleteRecursively() }
                _ui.update { it.copy(busy = false, activity = "") }
            }
        }
    }

    fun importFile(uri: Uri) {
        if (blocked) return
        _ui.update { it.copy(busy = true, activity = "Importing UTF-8 text") }
        viewModelScope.launch {
            try {
                val draft = repository.importAsNote(uri)
                newNote()
                val id = _ui.value.noteId
                savedRope = draft.root
                selection = draft.selection.bounded(draft.root.length)
                history.clear()
                _ui.value = _ui.value.copy(root = draft.root,
                    title = if (_ui.value.autoName) autoTitle(draft.root) else draft.title,
                    noteId = id, revision = _ui.value.revision + 1, patch = null)
                requestSave()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Import failed: ${friendly(failure)}")
            } finally { _ui.update { it.copy(busy = false, activity = "") } }
        }
    }

    fun paste() {
        if (blocked) return
        val clipboard = getApplication<Application>().getSystemService(ClipboardManager::class.java)
            ?: run { notify("The clipboard service is unavailable."); return }
        val text = try {
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text else null
        } catch (failure: SecurityException) {
            notify("Clipboard access was denied.")
            return
        }
        if (text == null || text.isEmpty()) { notify("The clipboard has no plain text."); return }
        val chosen = selection.bounded(_ui.value.root.length)
        val start = minOf(chosen.start, chosen.end)
        val end = maxOf(chosen.start, chosen.end)
        if (_ui.value.root.length - (end - start) + text.length > Limits.MAX_CHARACTERS) {
            notify(Limits.SIZE_MESSAGE)
            return
        }
        _ui.update { it.copy(busy = true, activity = "Preparing paste") }
        viewModelScope.launch {
            try {
                val inserted = withContext(Dispatchers.Default) {
                    require(text.none { it == 0.toChar() }) { "The clipboard contains binary NUL characters." }
                    Rope.of(text)
                }
                commit(start, end, inserted, chosen)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Paste failed: ${friendly(failure)}")
            } finally { _ui.update { it.copy(busy = false, activity = "") } }
        }
    }

    /** Suggested Save As name for the dialog's initial state. */
    fun suggestedFileName(extension: String): String {
        val base = _ui.value.title.trim().ifBlank { "Untitled" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "-").take(60)
        return "$base.$extension"
    }
    val autoNameEnabled: Boolean get() = _ui.value.autoName
    /** Applied when the auto-name suggestion passes validation; otherwise kept. */
    private fun applySuggestedTitle(fileName: String) {
        val base = fileName.substringBeforeLast('.').trim()
        if (base.isNotEmpty()) _ui.update { it.copy(title = base.take(200)) }
    }

    fun prepareExport(spec: ExportSpec) {
        if (blocked) return
        spec.validationError()?.let { notify(it); return }
        val source = _ui.value
        _ui.update { it.copy(busy = true, activity = if (spec.isPdf) "Preparing PDF" else "Preparing UTF-8 file") }
        viewModelScope.launch {
            try {
                // Flush storage before another activity takes focus.
                persist(makeDraft())
                val name = exporter.stage(source.root, spec) { page ->
                    _ui.update { it.copy(activity = "Preparing PDF, page $page") }
                }
                val pending = PendingExport(name, spec, source.revision, session, noteId = source.noteId)
                storePending(pending)
                _ui.update { it.copy(pendingExport = pending) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Export failed: ${friendly(failure)}")
            } finally { _ui.update { it.copy(busy = false, activity = "") } }
        }
    }
    fun pickerLaunched() {
        val pending = _ui.value.pendingExport ?: return
        val next = pending.copy(pickerLaunched = true)
        storePending(next)
        _ui.update { it.copy(pendingExport = next) }
    }
    fun pickerFailed(message: String) {
        notify(message)
        finishExport(null)
    }
    fun finishExport(uri: Uri?) {
        // A result may arrive before asynchronous note loading has completed.
        viewModelScope.launch {
            ui.firstReady()
            val pending = _ui.value.pendingExport ?: run {
                if (uri != null) notify("The prepared export could not be recovered. The selected file may be empty; start Save As again.")
                return@launch
            }
            _ui.update { it.copy(busy = true, activity = if (uri == null) "Canceling export" else "Writing file") }
            try {
                if (uri != null) {
                    val actualName = exporter.publish(pending, uri)
                    val state = _ui.value
                    if (!pending.spec.isPdf && pending.session == session && pending.sourceRevision == state.revision) {
                        savedRope = state.root
                        if (state.autoName) applySuggestedTitle(pending.spec.fileName)
                        requestSave()
                    }
                    if (actualName != null && actualName != pending.spec.fileName) {
                        notify("Saved as $actualName. The storage provider changed the requested name.")
                    } else if (actualName == null) {
                        notify("File saved. This provider did not return a display name; verify the extension in Files.")
                    } else notify("Saved $actualName")
                    GlyphSupport.blink()
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                notify("Save failed: ${friendly(failure)} The destination may contain a partial file; your note text is safe in the library.")
            } finally {
                clearPendingKeys()
                _ui.update { it.copy(pendingExport = null, busy = false, activity = "") }
                exporter.discard(pending.stageName)
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.StateFlow<EditorUiState>.firstReady() {
        this.first { !it.busy }
    }

    fun setTheme(mode: ThemeMode) {
        _ui.update { it.copy(theme = mode) }
        preferences.edit().putString("theme", mode.name).apply()
    }
    /** Nothing Glyph master switch, persisted and reflected in observable state. */
    fun setGlyphEnabled(on: Boolean) {
        preferences.edit().putBoolean("glyph", on).apply()
        GlyphSupport.setEnabled(on)
        _ui.update { it.copy(glyph = on && it.glyphAvailable) }
    }

    fun setLineNumbers(show: Boolean) {
        _ui.update { it.copy(lineNumbers = show) }
        preferences.edit().putBoolean("line_numbers", show).apply()
    }
    fun setAutoName(on: Boolean) {
        _ui.update { state ->
            // Re-enabling releases the manual lock; disabling keeps the name
            // (so a renamed note stays renamed while auto-name is off).
            val title = if (on) autoTitle(state.root) else state.title
            state.copy(autoName = on, title = title, titleIsManual = !on)
        }
        preferences.edit().putBoolean("auto_name", on).apply()
        requestSave()
    }
    /** Manual rename from the title tap; locks auto-name for this note while
     *  it stays open. Flipping the auto-name switch releases the lock. */
    fun renameNote(name: String) {
        val trimmed = name.trim().take(200)
        if (trimmed.isEmpty() || blocked) return
        _ui.update { it.copy(title = trimmed, titleIsManual = true,
            revision = it.revision + 1) }
        // Persist the rename NOW, not via the 700 ms debounce: the library
        // lists from disk, so a rename followed quickly by Back (or a process
        // stop before the debounce fired) used to be lost and the old title
        // reappeared. A pending debounced draft is dropped first so it can
        // never overwrite this newer generation with the stale title.
        val draft = makeDraft()
        saveRequests.value = null
        viewModelScope.launch { persist(draft) }
    }
    private fun makeDraft(): Draft {
        val state = _ui.value
        return Draft(state.root, state.title, selection, state.noteId, ++generation, state.revision)
    }
    private fun requestSave() {
        if (initialized && _ui.value.noteId.isNotEmpty()) saveRequests.value = makeDraft()
    }
    /** Cancels a pending debounced write and performs it now. */
    private fun flushPendingSave() {
        val pending = saveRequests.value
        saveRequests.value = null
        if (pending != null) viewModelScope.launch { persist(pending) }
    }
    fun flushForStop() {
        if (!initialized) return
        flushPendingSave()
    }
    private suspend fun persist(draft: Draft) {
        if (draft.noteId.isEmpty()) return
        try {
            GlyphSupport.onSaveProgress(10)
            repository.saveNote(draft)
            savedRope = draft.root
            saveFailureReported = false
            GlyphSupport.onSaveDone()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (!saveFailureReported) {
                saveFailureReported = true
                notify("Local storage could not be updated. Export the text to protect it.")
            }
        }
    }
    private fun storePending(p: PendingExport) {
        savedState["stage"] = p.stageName
        savedState["filename"] = p.spec.fileName
        savedState["revision"] = p.sourceRevision
        savedState["session"] = p.session
        savedState["launched"] = p.pickerLaunched
        savedState["noteId"] = p.noteId
    }
    private fun restorePending(): PendingExport? {
        val stage = savedState.get<String>("stage") ?: return null
        val fileName = savedState.get<String>("filename") ?: return null
        return PendingExport(stage, ExportSpec(fileName), savedState["revision"] ?: -1L,
            savedState["session"] ?: "", savedState["launched"] ?: false,
            savedState.get<String>("noteId") ?: "")
    }
    private fun clearPendingKeys() {
        listOf("stage", "filename", "revision", "session", "launched", "noteId")
            .forEach { savedState.remove<Any>(it) }
    }
    private fun friendly(failure: Exception): String = when (failure) {
        is CharacterCodingException -> "Only valid UTF-8 text is supported. Convert the source encoding and try again."
        is SecurityException -> "The storage provider denied access. Select a writable location."
        else -> failure.message?.take(240) ?: "The operation could not be completed."
    }
}
