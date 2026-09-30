package dev.pocket.notepad.data

import android.content.Context
import android.net.Uri
import dev.pocket.notepad.model.ExportSpec
import dev.pocket.notepad.model.PendingExport
import dev.pocket.notepad.model.Rope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class FileExporter(context: Context) {
    private val app = context.applicationContext
    private val directory get() = File(app.filesDir, "staged-exports")

    suspend fun stage(root: Rope, spec: ExportSpec, onPage: (Int) -> Unit): String =
        withContext(Dispatchers.IO) {
            require(spec.validationError() == null) { spec.validationError().orEmpty() }
            check(directory.isDirectory || directory.mkdirs()) { "Cannot create the export staging directory." }
            val file = File(directory, "${UUID.randomUUID()}.stage")
            try {
                if (spec.isPdf) PdfExporter.export(root, file, spec.fileName, onPage)
                else {
                    val job = currentCoroutineContext()
                    file.outputStream().buffered().use { TextCodec.write(root, it) { job.ensureActive() } }
                }
                file.name
            } catch (failure: Throwable) {
                file.delete()
                throw failure
            }
        }

    suspend fun publish(pending: PendingExport, uri: Uri): String? = withContext(Dispatchers.IO) {
        val file = stagedFile(pending.stageName)
        check(file.isFile) { "The prepared export is missing. Please start Save As again." }
        val job = currentCoroutineContext()
        SafFiles.output(app.contentResolver, uri).use { output ->
            file.inputStream().buffered().use { input ->
                val bytes = ByteArray(32 * 1024)
                while (true) {
                    job.ensureActive()
                    val count = input.read(bytes)
                    if (count < 0) break
                    output.write(bytes, 0, count)
                }
            }
            output.flush()
        }
        runCatching { SafFiles.displayName(app.contentResolver, uri) }.getOrNull()
    }
    /**
     * Share staging: a per-share subdirectory so the content URI's last path
     * segment — what recipients display as the attachment name — is the clean
     * file name rather than an opaque uuid.stage blob.
     */
    suspend fun stageForShare(root: Rope, spec: ExportSpec, onPage: (Int) -> Unit): File =
        withContext(Dispatchers.IO) {
            require(spec.validationError() == null) { spec.validationError().orEmpty() }
            check(directory.mkdirs() || directory.isDirectory) { "Cannot create the export staging directory." }
            val dir = File(directory, UUID.randomUUID().toString())
            val file = File(dir, spec.fileName)
            try {
                dir.mkdirs()
                if (spec.isPdf) PdfExporter.export(root, file, spec.fileName, onPage)
                else {
                    val job = currentCoroutineContext()
                    file.outputStream().buffered().use { TextCodec.write(root, it) { job.ensureActive() } }
                }
                file
            } catch (failure: Throwable) {
                dir.deleteRecursively()
                throw failure
            }
        }

    suspend fun exists(name: String): Boolean = withContext(Dispatchers.IO) { stagedFile(name).isFile }
    suspend fun discard(name: String) = withContext(Dispatchers.IO) { stagedFile(name).delete(); Unit }
    suspend fun cleanOrphans(keep: String?) = withContext(Dispatchers.IO) {
        directory.listFiles()?.filter { it.name != keep }?.forEach { orphan ->
            if (orphan.isDirectory) orphan.deleteRecursively() else orphan.delete()
        }
    }
    /** Public handle for share flows: the staged file to attach to a chooser. */
    fun stagedFile(name: String): File {
        require(name.matches(Regex("[a-f0-9-]{36}\\.stage"))) { "Invalid staged export name." }
        return File(directory, name)
    }
}
