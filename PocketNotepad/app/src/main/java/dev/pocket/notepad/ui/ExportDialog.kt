package dev.pocket.notepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocket.notepad.model.ExportSpec
import dev.pocket.notepad.ui.theme.SpaceMono

@Composable
fun ExportDialog(initialName: String, autoName: Boolean,
                 onDismiss: () -> Unit, onExport: (ExportSpec) -> Unit) {
    var fileName by rememberSaveable { mutableStateOf(initialName) }
    val spec = ExportSpec(fileName)
    val error = spec.validationError()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SAVE A COPY", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text(if (autoName) "FILE NAME  (FROM FIRST LINE)" else "FILE NAME / EXTENSION",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    // Square mono field with a hard 1dp border; no fill, no rounding.
                    BasicTextField(
                        value = fileName, onValueChange = { fileName = it },
                        singleLine = true, textStyle = TextStyle(
                            fontFamily = SpaceMono, fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(Color(0xFFFF0000)),
                        modifier = Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline)
                            .background(Color.Transparent).padding(horizontal = 12.dp, vertical = 12.dp)
                    )
                    if (error != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(error, style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFFF0000))
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("txt", "md", "pdf", "py", "js", "json", "html", "css", "csv")) { extension ->
                        val selected = spec.extension == extension
                        Text(".$extension", style = MaterialTheme.typography.labelMedium,
                            color = if (selected) MaterialTheme.colorScheme.background
                                    else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                                .border(1.dp, MaterialTheme.colorScheme.outline)
                                .clickable {
                                    val base = fileName.substringBeforeLast('.', fileName).ifBlank { "Untitled" }
                                    fileName = "$base.$extension"
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp))
                    }
                }
                Text(if (spec.isPdf)
                    "A4 pages with selectable text, 40 pt margins, and page numbers. Maximum 250 pages."
                    else "Plain UTF-8 text. Any extension is allowed; the content is not converted into another file format.",
                    style = MaterialTheme.typography.bodySmall)
                Text("NEXT, CHOOSE A LOCATION IN ANDROID FILES. PROVIDERS CAN ADJUST FILE NAMES.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(spec) }, enabled = error == null) { Text("CHOOSE LOCATION") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}
