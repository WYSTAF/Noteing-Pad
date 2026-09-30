package dev.pocket.notepad.data

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import dev.pocket.notepad.model.ExportSpec
import java.io.IOException

object SafFiles {
    fun openIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    fun createIntent(spec: ExportSpec): Intent {
        require(spec.validationError() == null) { spec.validationError().orEmpty() }
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = spec.mimeType
            putExtra(Intent.EXTRA_TITLE, spec.fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }
    fun displayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    fun input(resolver: ContentResolver, uri: Uri) = resolver.openInputStream(uri)
        ?: throw IOException("The storage provider could not open this file.")
    fun output(resolver: ContentResolver, uri: Uri) = resolver.openOutputStream(uri, "wt")
        ?: throw IOException("The storage provider could not create an output stream.")
}
