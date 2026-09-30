package dev.pocket.notepad.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

// Shared Nothing-style controls (skill: nothing-design): square outlined
// buttons, mechanical toggles, filled/hollow selection squares.

@Composable
fun SquareButton(label: String, icon: EditorSymbol, enabled: Boolean, action: () -> Unit) {
    SquareButton(onClick = action, enabled = enabled, filled = false) {
        EditorIcon(icon, label)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun SquareButton(icon: EditorSymbol, label: String, enabled: Boolean, action: () -> Unit) {
    SquareButton(onClick = action, enabled = enabled, filled = false) {
        EditorIcon(icon, label)
    }
}

/**
 * Nothing button: outlined square, or filled primary with inverted content.
 * Enabled filled buttons invert background/foreground; disabled fade out.
 */
@Composable
fun SquareButton(
    onClick: () -> Unit, enabled: Boolean, filled: Boolean, content: @Composable RowScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val ink = scheme.onBackground
    val paper = scheme.background
    val contentColor = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
        filled -> paper
        else -> ink
    }
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(if (filled && enabled) ink else Color.Transparent)
            .border(1.dp, if (enabled) ink else scheme.outlineVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .heightIn(min = 36.dp),
        content = {
            // Trailing-lambda form: the vararg overload takes content last, and
            // naming it fails overload resolution (seen once already in TopBar).
            CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
        })
}

@Composable
fun SelectionSquare(selected: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(10.dp)) {
        if (selected) drawRect(ink)
        else drawRect(ink, style = Stroke(1.5f))
    }
}

@Composable
fun MechanicalToggle(on: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Canvas(Modifier.size(width = 20.dp, height = 8.dp)) {
        val ink = scheme.onSurface
        val off = scheme.outlineVariant
        drawRect(if (on) ink else off, size = Size(9.dp.toPx(), size.height))
        drawRect(if (on) off else ink, topLeft = Offset(11.dp.toPx(), 0f),
            size = Size(9.dp.toPx(), size.height))
    }
}

/** Uppercase micro-label used above values. */
@Composable
fun MicroLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, maxLines = 1, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Square bordered note-name editor, matching the Save As field style. */
@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = name.isNotBlank() && name.trim().length <= 200
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RENAME NOTE", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                MicroLabel("NAME (SAVES TO THE LIBRARY)")
                Spacer(Modifier.height(6.dp))
                val scheme = MaterialTheme.colorScheme
                BasicTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFFF0000)),
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, scheme.outline)
                        .background(Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) onRename(name.trim()) }, enabled = valid) { Text("RENAME") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
}
