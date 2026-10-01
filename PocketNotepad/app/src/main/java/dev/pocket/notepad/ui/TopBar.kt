package dev.pocket.notepad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pocket.notepad.model.EditorUiState
import dev.pocket.notepad.model.Limits
import dev.pocket.notepad.model.ThemeMode

// Nothing-style chrome (skill: nothing-design): uppercase micro-labels, square
// outlined controls, mono metadata. Notes autosave, so there is no dirty marker.
@Composable
fun TopBar(
    state: EditorUiState, onBack: () -> Unit, onTitleTap: () -> Unit,
    onPaste: () -> Unit, onShare: () -> Unit,
    onUndo: () -> Unit, onRedo: () -> Unit,
    onExport: () -> Unit, onTheme: (ThemeMode) -> Unit,
    onLineNumbers: (Boolean) -> Unit, onAutoName: (Boolean) -> Unit,
    onMarkdown: () -> Unit,
    glyphOn: Boolean, glyphAvailable: Boolean, onToggleGlyph: (Boolean) -> Unit,
    frameStats: Pair<Int, Int>? = null
) {
    var menu by remember { mutableStateOf(false) }
    val available = !state.busy && state.pendingExport == null
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            SquareButton(EditorSymbol.BACK, "Back to library", true, onBack)
            Spacer(Modifier.width(10.dp))
            // Tap the title to rename — affordance undiscoverable on purpose;
            // the header stays quiet.
            Column(Modifier.weight(1f).clipToBounds()
                    .clickable(onClick = onTitleTap)) {
                Text("NOTEING PAD",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge)
            }
            // "Save as" is the primary action: filled block, inverted text.
            SquareButton(onClick = onExport, enabled = available, filled = true) {
                EditorIcon(EditorSymbol.EXPORT, "Save As or export")
                Spacer(Modifier.width(8.dp))
                Text("SAVE AS", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(8.dp))
            Box {
                SquareButton(onClick = { menu = true }, enabled = true, filled = false) {
                    EditorIcon(EditorSymbol.MORE, "Editor settings")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    Text("APPEARANCE", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) },
                            leadingIcon = { SelectionSquare(selected = state.theme == mode) },
                            onClick = { onTheme(mode); menu = false })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Line numbers") },
                        trailingIcon = { MechanicalToggle(on = state.lineNumbers) },
                        onClick = { onLineNumbers(!state.lineNumbers) })
                    DropdownMenuItem(text = { Text("Auto file name") },
                        trailingIcon = { MechanicalToggle(on = state.autoName) },
                        onClick = { onAutoName(!state.autoName) })
                    HorizontalDivider()
                    // Nothing Glyph master switch (Phone 2a strips, 3/4a matrix).
                    // Off-menu entirely on devices without Glyph hardware, so
                    // users never see a dead switch; otherwise state-backed.
                    if (glyphAvailable) DropdownMenuItem(text = { Text("Glyph lights") },
                        trailingIcon = { MechanicalToggle(on = glyphOn) },
                        onClick = { onToggleGlyph(!glyphOn) })
                    HorizontalDivider()
                    // Diagnostics only — tuning dials are gone.
                    DropdownMenuItem(enabled = false,
                        text = { Text(if (frameStats == null)
                                "LAST SCROLL: FLICK TO MEASURE"
                            else "LAST SCROLL: ${frameStats.first} FPS" +
                                (if (frameStats.second > 0) "  ·  ${frameStats.second} JANKY"
                                 else "  ·  CLEAN"),
                            style = MaterialTheme.typography.labelMedium) },
                        onClick = {})
                    HorizontalDivider()
                    // Build stamp: proof of which APK is actually installed.
                    DropdownMenuItem(enabled = false,
                        text = { Text("BUILD " + dev.pocket.notepad.BuildConfig.VERSION_NAME +
                                " (" + dev.pocket.notepad.BuildConfig.VERSION_CODE + ")",
                            style = MaterialTheme.typography.labelMedium) },
                        onClick = {})
                }
            }
        }
        // Static action row — no scrolling, exactly the five actions the user
        // chose: paste, share, md, undo, redo. (Clear moved out of the row.)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SquareButton("PASTE", EditorSymbol.PASTE, available, onPaste)
            SquareButton("SHARE", EditorSymbol.SHARE, available, onShare)
            SquareButton("MD", EditorSymbol.ADD, available, onMarkdown)
            SquareButton(EditorSymbol.UNDO, "Undo", available && state.canUndo, onUndo)
            SquareButton(EditorSymbol.REDO, "Redo", available && state.canRedo, onRedo)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)
    }
}
