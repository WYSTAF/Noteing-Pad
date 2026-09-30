package dev.pocket.notepad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocket.notepad.model.Limits
import dev.pocket.notepad.model.NoteMeta
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Trash list: deleted notes stay for 30 days, then are erased automatically. */
@Composable
fun TrashScreen(
    items: List<NoteMeta>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    onErase: (String) -> Unit,
    onEmpty: () -> Unit,
    modifier: Modifier = Modifier
) {
    val date = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }
    val dayMs = 24L * 60 * 60 * 1000
    val now = System.currentTimeMillis()
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SquareButton(EditorSymbol.BACK, "Back to library", true, onBack)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("NOTEING PAD", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("TRASH", style = MaterialTheme.typography.titleLarge)
            }
            if (items.isNotEmpty()) {
                TextButton(onClick = onEmpty) { Text("EMPTY TRASH") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("TRASH IS EMPTY", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("Deleted notes rest here for ${Limits.TRASH_RETENTION_DAYS} days, then are erased.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { note ->
                    val remaining = ceil((note.deletedAt + Limits.TRASH_RETENTION_DAYS * dayMs - now) / dayMs.toDouble())
                        .toInt().coerceAtLeast(0)
                    Row(Modifier.fillMaxWidth().clickable { onRestore(note.id) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(note.title.ifBlank { "Untitled" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text("DELETED ${date.format(Date(note.deletedAt))}  /  $remaining DAYS LEFT",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(12.dp))
                        TextButton(onClick = { onRestore(note.id) }) { Text("RESTORE") }
                        Spacer(Modifier.width(4.dp))
                        SquareButton(EditorSymbol.ERASE, "Erase forever", true, { onErase(note.id) })
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
                }
            }
        }
    }
}
