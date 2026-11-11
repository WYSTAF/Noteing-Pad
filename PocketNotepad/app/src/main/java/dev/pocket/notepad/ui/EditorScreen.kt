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
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Color
import dev.pocket.notepad.NotepadViewModel
import dev.pocket.notepad.data.SafFiles
import dev.pocket.notepad.model.Limits
import dev.pocket.notepad.model.MarkdownAction
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
    var pendingErase by rememberSaveable { mutableStateOf<String?>(null) }
    var renameDialog by rememberSaveable { mutableStateOf(false) }
    var shareFormatDialog by rememberSaveable { mutableStateOf(false) }
    var markdownBarOpen by rememberSaveable { mutableStateOf(false) }
    var markdownPreview by rememberSaveable { mutableStateOf(false) }
    // Last completed scroll gesture: (fps, dropped frames). Not saved — resets
    // on recomposition so a stale number can't mislead the next experiment.
    var frameStats by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var confirmEmptyTrash by rememberSaveable { mutableStateOf(false) }
    var pendingImportUri by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(state.screen, state.noteId) {
        if (state.screen != Screen.EDITOR) markdownPreview = false
    }
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
    // Physical Back: preview returns to editor; editor or trash return to library.
    BackHandler(enabled = state.screen != Screen.LIBRARY && !model.blocked) {
        if (state.screen == Screen.EDITOR && markdownPreview) markdownPreview = false
        else model.backToLibrary()
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
                    model::deleteNote, requestImport, model::openTrash,
                    Modifier.fillMaxWidth().weight(1f))
                Screen.TRASH -> TrashScreen(trash, model::backToLibrary, model::restoreNote,
                    { pendingErase = it }, { confirmEmptyTrash = true },
                    Modifier.fillMaxWidth().weight(1f))
                Screen.EDITOR -> {
                    TopBar(state, {
                            if (markdownPreview) markdownPreview = false
                            else model.backToLibrary()
                        }, { renameDialog = true },
                        model::paste, { if (!model.blocked) shareFormatDialog = true },
                        model::undo, model::redo, requestExport,
                        model::setTheme, model::setLineNumbers, model::setAutoName,
                        {
                            if (markdownPreview && markdownBarOpen) {
                                markdownPreview = false
                            } else {
                                markdownBarOpen = !markdownBarOpen
                            }
                        },
                        markdownBarOpen,
                        state.glyph, state.glyphStatus, model::setGlyphEnabled, frameStats)
                    if (markdownBarOpen) {
                        MarkdownToolbar(
                            previewMode = markdownPreview,
                            onTogglePreview = { markdownPreview = !markdownPreview },
                            onAction = { action ->
                                if (markdownPreview) markdownPreview = false
                                model.applyMarkdown(action)
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                    }
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
                    if (markdownPreview) {
                        MarkdownPreview(
                            source = state.root.toString(),
                            onToggleTask = model::toggleMarkdownTask,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    } else {
                        // Hardware-keyboard shortcuts: Ctrl+S export, Ctrl+N new note,
                        // Ctrl+O import. Autosave makes an in-editor new note lossless.
                        NativeEditor(model, state, Modifier.fillMaxWidth().weight(1f), requestExport,
                            model::newNote, requestImport,
                            onScrollStats = { fps, long -> frameStats = fps to long })
                    }
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
    if (shareFormatDialog) AlertDialog(
        onDismissRequest = { shareFormatDialog = false },
        title = { Text("SHARE FORMAT", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The note is sent as a real file, not pasted text. Choose the format:",
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    SquareButton(onClick = { shareFormatDialog = false; model.shareFile("txt") },
                        enabled = true, filled = false) { Text("TXT", style = MaterialTheme.typography.labelSmall) }
                    SquareButton(onClick = { shareFormatDialog = false; model.shareFile("md") },
                        enabled = true, filled = false) { Text("MD", style = MaterialTheme.typography.labelSmall) }
                    SquareButton(onClick = { shareFormatDialog = false; model.shareFile("pdf") },
                        enabled = true, filled = false) { Text("PDF", style = MaterialTheme.typography.labelSmall) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { shareFormatDialog = false }) { Text("CANCEL") } }
    )
    if (renameDialog) RenameDialog(state.title, { renameDialog = false }) { name ->
        renameDialog = false
        model.renameNote(name)
    }
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
}

/**
 * Quick inline Markdown formatting & preview bar (Nothing-style):
 * Left-pinned PREVIEW / EDIT switch + edge-to-edge smart formatting bar.
 * Wraps selections cleanly, toggles prefixes, and places the caret inside markers.
 */
@Composable
fun MarkdownToolbar(
    previewMode: Boolean,
    onTogglePreview: () -> Unit,
    onAction: (MarkdownAction) -> Unit
) {
    val items = listOf(
        "B" to MarkdownAction.BOLD,
        "I" to MarkdownAction.ITALIC,
        "S" to MarkdownAction.STRIKE,
        "H1" to MarkdownAction.H1,
        "H2" to MarkdownAction.H2,
        "H3" to MarkdownAction.H3,
        "•" to MarkdownAction.BULLET,
        "1." to MarkdownAction.NUMBERED,
        "[ ]" to MarkdownAction.CHECKLIST,
        "\"" to MarkdownAction.QUOTE,
        "</>" to MarkdownAction.CODE,
        "ALIGN L" to MarkdownAction.ALIGN_LEFT,
        "CENTER" to MarkdownAction.ALIGN_CENTER,
        "ALIGN R" to MarkdownAction.ALIGN_RIGHT,
        "LINK" to MarkdownAction.LINK,
        "TABLE" to MarkdownAction.TABLE,
        "---" to MarkdownAction.DIVIDER
    )
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(scheme.surface),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .background(if (previewMode) Color(0xFFD92C35) else scheme.onBackground)
                .clickable(onClick = onTogglePreview)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (previewMode) "EDIT" else "PREVIEW",
                style = MaterialTheme.typography.labelSmall,
                color = if (previewMode) Color.White else scheme.background,
                maxLines = 1,
                softWrap = false
            )
        }
        VerticalDivider(color = scheme.outlineVariant, thickness = 1.dp)
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { (label, action) ->
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .border(1.dp, scheme.outlineVariant)
                        .clickable { onAction(action) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                }
            }
        }
    }
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
