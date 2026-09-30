package dev.pocket.notepad.ui

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.notepad.NotepadViewModel
import dev.pocket.notepad.data.SafFiles
import dev.pocket.notepad.model.Limits
import dev.pocket.notepad.model.Screen
import java.text.NumberFormat
import kotlinx.coroutines.flow.collect

@Composable
fun EditorScreen(model: NotepadViewModel) {
    val state by model.ui.collectAsStateWithLifecycle()
    val notes by model.notes.collectAsStateWithLifecycle()
    val trash by model.trash.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var clearDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingErase by rememberSaveable { mutableStateOf<String?>(null) }
    var renameDialog by rememberSaveable { mutableStateOf(false) }
    var shareFormatDialog by rememberSaveable { mutableStateOf(false) }
    var markdownDialog by rememberSaveable { mutableStateOf(false) }
    // Last completed scroll gesture: (fps, dropped frames). Not saved — resets
    // on recomposition so a stale number can't mislead the next experiment.
    var frameStats by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirmEmptyTrash by rememberSaveable { mutableStateOf(false) }
    var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.data?.let { uri ->
            pendingImportUri = uri.toString()
        }
    }
    val createLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        model.finishExport(if (result.resultCode == Activity.RESULT_OK) result.data?.data else null)
    }
    val requestImport: () -> Unit = {
        if (!model.blocked) {
            try { importLauncher.launch(SafFiles.openIntent()) }
            catch (failure: Exception) { model.notify("Android's file picker is unavailable on this device.") }
        }
    }
    val requestExport: () -> Unit = { if (!model.blocked) exportDialog = true }
    // Physical Back: editor or trash return to the library (flushing the
    // autosave); on the library itself the app exits, as before.
    BackHandler(enabled = state.screen != Screen.LIBRARY && !model.blocked) {
        model.backToLibrary()
    }
    LaunchedEffect(model) { model.messages.collect { snackbars.showSnackbar(it) } }
    // Queue picker results until restoration finishes, including process recreation.
    LaunchedEffect(pendingImportUri, state.busy, state.pendingExport) {
        val uri = pendingImportUri
        if (uri != null && !state.busy && state.pendingExport == null) {
            pendingImportUri = null
            model.importFile(Uri.parse(uri))
        }
    }
    val pending = state.pendingExport
    LaunchedEffect(pending, state.busy) {
        if (pending != null && !pending.pickerLaunched && !state.busy) {
            model.pickerLaunched()
            try { createLauncher.launch(SafFiles.createIntent(pending.spec)) }
            catch (failure: Exception) { model.pickerFailed("Android's Save As picker could not be opened.") }
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbars) },
        containerColor = MaterialTheme.colorScheme.background
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            when (state.screen) {
                Screen.LIBRARY -> LibraryScreen(notes, model::newNote, model::openNote,
                    { pendingDelete = it }, requestImport, model::openTrash,
                    Modifier.fillMaxWidth().weight(1f))
                Screen.TRASH -> TrashScreen(trash, model::backToLibrary, model::restoreNote,
                    { pendingErase = it }, { confirmEmptyTrash = true },
                    Modifier.fillMaxWidth().weight(1f))
                Screen.EDITOR -> {
                    TopBar(state, model::backToLibrary, { renameDialog = true },
                        model::paste, { if (!model.blocked) shareFormatDialog = true },
                        { clearDialog = true }, model::undo, model::redo, requestExport,
                        model::setTheme, model::setLineNumbers, model::setAutoName,
                        { markdownDialog = true },
                        state.glyph, state.glyphAvailable, model::setGlyphEnabled, frameStats)
                    if (state.busy) {
                        SegmentedLoader()
                        Text(state.activity.uppercase(), modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.pendingExport != null && !state.busy) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("WAITING FOR A SAVE LOCATION", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { model.finishExport(null) }) { Text("CANCEL EXPORT") }
                        }
                    }
                    // Hardware-keyboard shortcuts: Ctrl+S export, Ctrl+N new note,
                    // Ctrl+O import. Autosave makes an in-editor new note lossless.
                    NativeEditor(model, state, Modifier.fillMaxWidth().weight(1f), requestExport,
                        model::newNote, requestImport,
                        onScrollStats = { fps, long -> frameStats = fps to long })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                    val numbers = remember { NumberFormat.getIntegerInstance() }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${numbers.format(state.root.words)} WORDS  /  " +
                                "${numbers.format(state.root.characters)} CHARS  /  " +
                                "${compactCount(state.totalLines)} LINES",
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text("SAVED LOCALLY", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (exportDialog) ExportDialog(model.suggestedFileName("txt"), model.autoNameEnabled,
        { exportDialog = false }) { spec ->
        exportDialog = false
        model.prepareExport(spec)
    }
    if (pendingDelete != null) AlertDialog(
        onDismissRequest = { pendingDelete = null },
        title = { Text("Move this note to trash?") },
        text = { Text("The note leaves the library and stays in the trash for ${Limits.TRASH_RETENTION_DAYS} days before being erased. Restore it anytime from the trash screen.") },
        confirmButton = {
            TextButton(onClick = { pendingDelete?.let(model::deleteNote); pendingDelete = null }) { Text("MOVE TO TRASH") }
        },
        dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("CANCEL") } }
    )
    if (renameDialog) RenameDialog(state.title, { renameDialog = false }) { name ->
        renameDialog = false
        model.renameNote(name)
    }
    if (markdownDialog) MarkdownDialog({ markdownDialog = false }) { snippet ->
        markdownDialog = false
        model.insertMarkdown(snippet)
    }
    if (shareFormatDialog) AlertDialog(
        onDismissRequest = { shareFormatDialog = false },
        title = { Text("SHARE FORMAT", style = MaterialTheme.typography.titleMedium) },
        text = { Text("The note is sent as a real file, not pasted text. Choose the format:") },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SquareButton(onClick = { shareFormatDialog = false; model.shareFile("txt") },
                    enabled = true, filled = false) { Text("TXT", style = MaterialTheme.typography.labelSmall) }
                SquareButton(onClick = { shareFormatDialog = false; model.shareFile("md") },
                    enabled = true, filled = false) { Text("MD", style = MaterialTheme.typography.labelSmall) }
                SquareButton(onClick = { shareFormatDialog = false; model.shareFile("pdf") },
                    enabled = true, filled = false) { Text("PDF", style = MaterialTheme.typography.labelSmall) }
            }
        },
        dismissButton = { TextButton(onClick = { shareFormatDialog = false }) { Text("CANCEL") } }
    )
    if (pendingErase != null) AlertDialog(
        onDismissRequest = { pendingErase = null },
        title = { Text("Erase this note forever?") },
        text = { Text("The note is destroyed now, without the 30-day grace period. Exported copies you saved elsewhere are untouched.") },
        confirmButton = {
            TextButton(onClick = { pendingErase?.let(model::deleteForever); pendingErase = null }) { Text("ERASE") }
        },
        dismissButton = { TextButton(onClick = { pendingErase = null }) { Text("CANCEL") } }
    )
    if (confirmEmptyTrash) AlertDialog(
        onDismissRequest = { confirmEmptyTrash = false },
        title = { Text("Empty the trash?") },
        text = { Text("Every note in the trash is erased now. This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = { confirmEmptyTrash = false; model.emptyTrash() }) { Text("ERASE ALL") }
        },
        dismissButton = { TextButton(onClick = { confirmEmptyTrash = false }) { Text("CANCEL") } }
    )
    if (clearDialog) AlertDialog(
        onDismissRequest = { clearDialog = false }, title = { Text("Clear the document?") },
        text = { Text("All text will be removed. Undo can restore it until that edit leaves the history.") },
        confirmButton = { TextButton(onClick = { clearDialog = false; model.clear() }) { Text("CLEAR") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("CANCEL") } }
    )
}

/** 1..9999 verbatim, then compact k form (10000 → "10k", 10600 → "10.6k"). */
fun compactCount(value: Int): String = when {
    value < 1000 -> value.toString()
    value < 10_000 -> value.toString()
    value < 1_000_000 -> {
        val k = value / 1000
        val tenth = (value % 1000) / 100
        if (tenth == 0) "${k}k" else "${k}.${tenth}k"
    }
    else -> {
        val m = value / 1_000_000
        val tenth = (value % 1_000_000) / 100_000
        if (tenth == 0) "${m}M" else "${m}.${tenth}M"
    }
}

/**
 * Markdown insert palette. The app stays a plain-text editor (no renderer on
 * purpose), so these insert Markdown SOURCE — the exact bytes a .md file holds.
 */
@Composable
private fun MarkdownDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val blocks = listOf(
        "HEADING" to "## Heading",
        "BULLET LIST" to "- Item\n- ",
        "NUMBERED LIST" to "1. First\n2. ",
        "TASK LIST" to "- [ ] Todo\n- [x] Done\n",
        "CODE" to "```\ncode\n```",
        "QUOTE" to "> quoted text",
        "TABLE" to "| Column A | Column B |\n| --- | --- |\n| Cell | Cell |\n",
        "DIVIDER" to "---",
        "LINK" to "[label](https://)"
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("INSERT MARKDOWN", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("INSERTS MARKDOWN SOURCE AT THE CARET", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                blocks.forEach { (label, snippet) ->
                    SquareButton(onClick = { onPick(snippet) }, enabled = true, filled = false) {
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } }
    )
}

/** Nothing-style loader: seven segments pulse in sequence. */
@Composable
private fun SegmentedLoader(modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    val track = MaterialTheme.colorScheme.outlineVariant
    val transition = rememberInfiniteTransition(label = "loader")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 7f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "phase"
    )
    Row(modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(7) { index ->
            val distance = kotlin.math.abs(phase - index)
            val alpha = (1.2f - distance / 2.2f).coerceIn(0.25f, 1f)
            Canvas(Modifier.size(width = 10.dp, height = 4.dp)) {
                drawRect(if (alpha > 0.6f) ink else track, alpha = alpha)
            }
        }
    }
}
