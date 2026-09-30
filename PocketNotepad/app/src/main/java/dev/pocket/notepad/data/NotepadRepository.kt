package dev.pocket.notepad.data

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import dev.pocket.notepad.model.Draft
import dev.pocket.notepad.model.Limits
import dev.pocket.notepad.model.NoteMeta
import dev.pocket.notepad.model.Selection
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Multi-note storage in app-private filesDir/notes. Each note is one AtomicFile:
 * version, title, update time, selection, a short preview, character count, then
 * the rope's UTF-8 body. The library list reads only the header, so listing
 * never materializes document bodies.
 *
 * Deleting a note MOVES it to filesDir/trash as `<deletedAtMillis>_<id>.bin`;
 * entries older than Limits.TRASH_RETENTION_DAYS are purged whenever the
 * trash is listed. The file format itself is unchanged, so restore is a rename.
 */
class NotepadRepository(context: Context) {
    private val app = context.applicationContext
    private val notesDirectory = File(app.filesDir, "notes")
    private val trashDirectory = File(app.filesDir, "trash")
    private val legacyDraft = AtomicFile(File(app.filesDir, "current-draft.bin"))
    private val ioMutex = Mutex()

    suspend fun listNotes(): List<NoteMeta> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            migrateLegacyDraft()
            purgePreExistingEmptyNotes()
            // Sweeps the 30-day expiries on every library load, so purge does
            // not depend on the trash screen ever being opened.
            purgeExpiredLocked()
            notesDirectory.listFiles()?.mapNotNull { file ->
                if (!file.name.endsWith(".bin")) return@mapNotNull null
                runCatching { readHeader(AtomicFile(file)) }.getOrNull()
            }?.sortedByDescending { it.updatedAt }.orEmpty()
        }
    }

    suspend fun openNote(id: String): Draft = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val file = noteFile(id)
            DataInputStream(AtomicFile(file).openRead().buffered()).use { input ->
                require(input.readInt() == VERSION) { "This note uses an unsupported format." }
                val title = input.readUTF()
                input.readLong() // updatedAt; the caller stamps a fresh one on save
                val selection = Selection(input.readInt(), input.readInt())
                input.readUTF() // preview; body follows
                input.readInt() // characters
                val job = currentCoroutineContext()
                val root = TextCodec.read(input, rejectPdf = false) { job.ensureActive() }
                Draft(root, title, selection.bounded(root.length), noteId = id)
            }
        }
    }

    suspend fun saveNote(draft: Draft) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            if (draft.noteId in trashed) return@withLock
            // Blank-note rule: an empty note that has never been written is
            // never created. New→Back spam cannot litter the library; once a
            // note exists, emptying it remains a real, persisted edit.
            if (draft.root.isEmpty() && !noteFile(draft.noteId).exists()) return@withLock
            if (draft.generation < lastGeneration(draft.noteId)) return@withLock
            notesDirectory.mkdirs()
            val note = AtomicFile(noteFile(draft.noteId))
            val raw = note.startWrite()
            try {
                val header = DataOutputStream(raw)
                header.writeInt(VERSION)
                header.writeUTF(draft.title.take(200))
                header.writeLong(System.currentTimeMillis())
                header.writeInt(draft.selection.start)
                header.writeInt(draft.selection.end)
                header.writeUTF(previewOf(draft))
                header.writeInt(draft.root.characters)
                val job = currentCoroutineContext()
                TextCodec.write(draft.root, NonClosingOutputStream(raw)) { job.ensureActive() }
                job.ensureActive()
                note.finishWrite(raw)
                generations[draft.noteId] = draft.generation
            } catch (failure: Throwable) {
                note.failWrite(raw)
                throw failure
            }
        }
    }

    suspend fun deleteNote(id: String) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val file = noteFile(id)
            if (file.isFile) {
                trashDirectory.mkdirs()
                val target = File(trashDirectory, "${System.currentTimeMillis()}_$id.bin")
                if (!file.renameTo(target)) throw IOException("The note could not be moved to trash.")
            }
            // Tombstone: a debounced autosave that fires after this delete must
            // not resurrect the note. Cleared on restore.
            trashed.add(id)
            generations.remove(id)
            Unit
        }
    }

    /** Expired entries are swept first, so the list never shows a doomed note. */
    suspend fun listTrash(): List<NoteMeta> = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            purgeExpiredLocked()
            trashDirectory.listFiles()?.mapNotNull { file ->
                val match = TRASH_NAME.matchEntire(file.name) ?: return@mapNotNull null
                val meta = runCatching { readHeader(AtomicFile(file)) }.getOrNull()
                    ?: return@mapNotNull null
                meta.copy(id = match.groupValues[2], deletedAt = match.groupValues[1].toLong())
            }?.sortedByDescending { it.deletedAt }.orEmpty()
        }
    }

    suspend fun restoreNote(id: String) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val file = trashFileFor(id)
                ?: throw IOException("That note is no longer in the trash.")
            val target = noteFile(id)
            notesDirectory.mkdirs()
            require(!target.exists()) { "A note with this id is already in the library." }
            if (!file.renameTo(target)) throw IOException("The note could not be restored.")
            trashed.remove(id)
            Unit
        }
    }

    suspend fun deleteForever(id: String) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val file = trashFileFor(id) ?: return@withLock
            if (!file.delete()) throw IOException("The note could not be erased.")
            Unit
        }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val failures = trashDirectory.listFiles()?.count { !it.delete() } ?: 0
            if (failures > 0) throw IOException("$failures trash entries could not be erased.")
            Unit
        }
    }

    /**
     * One-time cleanup (marker file = one time PER INSTALL, not per process):
     * empty notes saved before the blank-note rule existed move to trash.
     * Notes a user later clears deliberately stay — emptying an existing
     * note is a real edit. Idempotent, so a mid-sweep crash just retries.
     * Must run under ioMutex.
     */
    private fun purgePreExistingEmptyNotes() {
        if (emptyPurgeMarker.exists()) return
        val now = System.currentTimeMillis()
        notesDirectory.listFiles()?.forEach { file ->
            if (!file.name.endsWith(".bin")) return@forEach
            val meta = runCatching { readHeader(AtomicFile(file)) }.getOrNull() ?: return@forEach
            if (meta.characters > 0) return@forEach
            val id = file.nameWithoutExtension
            runCatching {
                trashDirectory.mkdirs()
                if (file.renameTo(File(trashDirectory, "${now}_$id.bin"))) trashed.add(id)
            }
        }
        runCatching { emptyPurgeMarker.createNewFile() }
    }

    /** Must run under ioMutex. */
    private fun purgeExpiredLocked(now: Long = System.currentTimeMillis()) {
        val cutoff = now - Limits.TRASH_RETENTION_DAYS * 24L * 60 * 60 * 1000
        trashDirectory.listFiles()?.forEach { file ->
            val match = TRASH_NAME.matchEntire(file.name) ?: return@forEach
            val deletedAt = match.groupValues[1].toLongOrNull() ?: return@forEach
            if (deletedAt < cutoff) runCatching { file.delete() }
        }
    }

    private fun trashFileFor(id: String): File? {
        require(id.matches(ID_PATTERN)) { "Invalid note id." }
        return trashDirectory.listFiles()?.firstOrNull { it.name.endsWith("_$id.bin") }
    }

    /** SAF open stays available; the picked file's content becomes a new note. */
    suspend fun importAsNote(uri: Uri): Draft = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        val name = runCatching { SafFiles.displayName(app.contentResolver, uri) }.getOrNull()
            ?.substringBeforeLast('.')?.take(60)?.takeIf { it.isNotBlank() }
        val root = SafFiles.input(app.contentResolver, uri).use { input ->
            TextCodec.read(input) { job.ensureActive() }
        }
        Draft(root, name ?: "Imported note", Selection(), newNoteId())
    }
    fun nextId(): String = newNoteId()

    private fun noteFile(id: String): File {
        require(id.matches(ID_PATTERN)) { "Invalid note id." }
        return File(notesDirectory, "$id.bin")
    }

    private fun readHeader(file: AtomicFile): NoteMeta? =
        DataInputStream(file.openRead().buffered()).use { input ->
            if (input.readInt() != VERSION) return null
            val title = input.readUTF()
            val updatedAt = input.readLong()
            input.readInt(); input.readInt() // selection not needed for the list
            val preview = input.readUTF()
            val characters = input.readInt()
            NoteMeta(File(file.baseFile.name).nameWithoutExtension, title, updatedAt, characters, preview)
        }

    private fun previewOf(draft: Draft): String {
        val buffer = CharArray(minOf(300, draft.root.length))
        draft.root.copyTo(buffer, 0, 0, buffer.size)
        return String(buffer).lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.take(140).orEmpty()
    }

    /** One-time move from the old single-draft file into the library. */
    private fun migrateLegacyDraft() {
        if (migrationTried) return
        migrationTried = true
        if (notesDirectory.listFiles()?.any { it.name.endsWith(".bin") } == true) return
        var recovered: Draft? = null
        runCatching {
            if (!legacyDraft.baseFile.exists() &&
                !File(legacyDraft.baseFile.path + ".bak").exists()) return@runCatching
            DataInputStream(legacyDraft.openRead().buffered()).use { input ->
                if (input.readInt() != 1) return@runCatching
                val title = input.readUTF().substringBeforeLast('.').take(60).ifBlank { "Recovered note" }
                input.readBoolean() // legacy dirty flag; the migrated note is saved content
                val selection = Selection(input.readInt(), input.readInt())
                val root = TextCodec.read(input, rejectPdf = false)
                recovered = Draft(root, title, selection, newNoteId())
            }
        }
        val draft = recovered ?: return
        runCatching {
            notesDirectory.mkdirs()
            val note = AtomicFile(noteFile(draft.noteId))
            val raw = note.startWrite()
            val header = DataOutputStream(raw)
            header.writeInt(VERSION)
            header.writeUTF(draft.title)
            header.writeLong(System.currentTimeMillis())
            header.writeInt(draft.selection.start); header.writeInt(draft.selection.end)
            header.writeUTF(previewOf(draft))
            header.writeInt(draft.root.characters)
            TextCodec.write(draft.root, NonClosingOutputStream(raw))
            note.finishWrite(raw)
            runCatching { legacyDraft.delete() }
        }
    }

    private fun newNoteId(): String =
        UUID.randomUUID().toString().replace("-", "").take(16)

    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
    }

    private val generations = HashMap<String, Long>()
    private val trashed = HashSet<String>()
    private var migrationTried = false
    private val emptyPurgeMarker = File(app.filesDir, ".empties-purged")
    private fun lastGeneration(id: String) = generations[id] ?: -1L

    companion object {
        private const val VERSION = 2
        private val ID_PATTERN = Regex("[a-f0-9]{16}")
        private val TRASH_NAME = Regex("(\\d+)_([a-f0-9]{16})\\.bin")
    }
}
