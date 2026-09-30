package dev.pocket.notepad.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.pocket.notepad.model.NoteMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Library/archive landing screen: every saved note, newest first. */
@Composable
fun LibraryScreen(
    notes: List<NoteMeta>,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onImport: () -> Unit,
    onTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val date = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT) }
    val numbers = remember { java.text.NumberFormat.getIntegerInstance() }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("NOTEING PAD", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Canvas(Modifier.size(5.dp)) { drawRect(Color(0xFFFF0000)) }
                }
                Text("LIBRARY", style = MaterialTheme.typography.titleLarge)
            }
            SquareButton(EditorSymbol.TRASH, "Trash", true, onTrash)
            Spacer(Modifier.width(6.dp))
            SquareButton(EditorSymbol.OPEN, "Import a file", true, onImport)
            Spacer(Modifier.width(6.dp))
            SquareButton("NEW", EditorSymbol.NEW, true, onNew)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        if (notes.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("NO NOTES YET", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Press NEW to start writing, or import a text file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(notes, key = { it.id }) { note ->
                    SwipeToTrashRow(
                        onOpen = { onOpen(note.id) },
                        onTrash = { onDelete(note.id) }
                    ) {
                        NoteRowContent(note, date.format(Date(note.updatedAt)),
                            numbers.format(note.characters))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                }
                item {
                    // Hint sits beneath the last note, as requested.
                    Text("SWIPE LEFT TO MOVE A NOTE TO TRASH",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Gmail-style swipe: dragging left slides the row away and uncovers a solid
 * red panel with the trash glyph; releasing past the threshold commits.
 * No per-row delete button by design.
 */
@Composable
private fun SwipeToTrashRow(
    onOpen: () -> Unit,
    onTrash: () -> Unit,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val threshold = with(density) { 88.dp.toPx() }
    var offsetX by remember { mutableFloatStateOf(0f) }
    val animated by animateFloatAsState(
        targetValue = offsetX,
        animationSpec = tween(durationMillis = if (abs(offsetX) > 0.5f) 0 else 160),
        label = "swipe"
    )

    Box(Modifier.fillMaxWidth().clipToBounds()) {
        // Red action panel behind the row, revealed as the row slides left.
        Box(
            Modifier.fillMaxSize().background(Color(0xFFD92C35)),
            contentAlignment = Alignment.CenterEnd
        ) {
            Box(Modifier.padding(end = 28.dp).size(24.dp)) {
                CompositionLocalProvider(
                    androidx.compose.material3.LocalContentColor provides Color.White
                ) { EditorIcon(EditorSymbol.TRASH, "Move to trash") }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(animated.roundToInt(), 0) }
                .background(MaterialTheme.colorScheme.background)
                .clickable(enabled = abs(animated) < 1f) { onOpen() }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (offsetX < -threshold) { offsetX = 0f; onTrash() }
                            else offsetX = 0f
                        },
                        onDragCancel = { offsetX = 0f },
                        onHorizontalDrag = { _, drag ->
                            // Left-only, 1:1 up to the commit point; only the
                            // overshoot past -threshold is rubber-banded. The
                            // old version scaled the whole drag by 0.25, so
                            // reaching the threshold took ~4x the finger travel
                            // and a normal flick almost never committed.
                            val next = (offsetX + drag).coerceAtMost(0f)
                            offsetX = if (next >= -threshold) next
                            else (-threshold + (next + threshold) * 0.25f)
                                .coerceAtLeast(-threshold * 1.5f)
                        }
                    )
                }
        ) { content() }
    }
}

@Composable
private fun NoteRowContent(note: NoteMeta, updated: String, chars: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(note.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            if (note.preview.isNotBlank() && note.preview != note.title) {
                Text(note.preview, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text("$updated  /  $chars CHARS", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
