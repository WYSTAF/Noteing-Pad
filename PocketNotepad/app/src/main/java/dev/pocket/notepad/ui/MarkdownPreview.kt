package dev.pocket.notepad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pocket.notepad.glyph.GlyphSupport
import dev.pocket.notepad.model.MarkdownBlock
import dev.pocket.notepad.model.MarkdownLogic
import dev.pocket.notepad.model.TextAlignMode
import dev.pocket.notepad.ui.theme.SpaceMono

@Composable
fun MarkdownPreview(
    source: String,
    onToggleTask: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val blocks = remember(source) { MarkdownLogic.parseBlocks(source) }
    val scrollState = rememberScrollState()
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        val max = scrollState.maxValue
        if (max > 0) {
            val pct = (scrollState.value * 100 / max).coerceIn(0, 100)
            GlyphSupport.onScrollProgress(pct)
        }
    }

    if (blocks.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "NOTHING TO PREVIEW YET",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        else -> MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        text = inlineAnnotated(block.text, scheme.surfaceVariant, Color(0xFFD92C35)),
                        style = style,
                        textAlign = toComposeAlign(block.align),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (block.level == 1) {
                        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
                    }
                }
                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = inlineAnnotated(block.text, scheme.surfaceVariant, Color(0xFFD92C35)),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = toComposeAlign(block.align),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is MarkdownBlock.BulletItem -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = when (block.align) {
                            TextAlignMode.CENTER -> Arrangement.Center
                            TextAlignMode.RIGHT -> Arrangement.End
                            TextAlignMode.LEFT -> Arrangement.Start
                        }
                    ) {
                        Text("•  ", style = MaterialTheme.typography.bodyLarge, color = Color(0xFFD92C35))
                        Text(
                            text = inlineAnnotated(block.text, scheme.surfaceVariant, Color(0xFFD92C35)),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = toComposeAlign(block.align)
                        )
                    }
                }
                is MarkdownBlock.NumberedItem -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${block.number}. ",
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = SpaceMono),
                            color = scheme.onSurfaceVariant
                        )
                        Text(
                            text = inlineAnnotated(block.text, scheme.surfaceVariant, Color(0xFFD92C35)),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = toComposeAlign(block.align),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                is MarkdownBlock.TaskItem -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleTask(block.bracketOffset) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .border(1.5.dp, if (block.checked) Color(0xFFD92C35) else scheme.onSurface)
                                .background(if (block.checked) Color(0xFFD92C35) else Color.Transparent),
                            contentAlignment = Alignment.Center
                        ) {
                            if (block.checked) {
                                Text("×", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = inlineAnnotated(block.text, scheme.surfaceVariant, Color(0xFFD92C35)),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                textDecoration = if (block.checked) TextDecoration.LineThrough else TextDecoration.None
                            ),
                            color = if (block.checked) scheme.onSurfaceVariant else scheme.onSurface
                        )
                    }
                }
                is MarkdownBlock.Quote -> {
                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(Color(0xFFD92C35)))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = inlineAnnotated(block.text, scheme.surfaceVariant, Color(0xFFD92C35)),
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
                is MarkdownBlock.CodeBlock -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, scheme.outlineVariant)
                            .background(scheme.surfaceVariant)
                            .padding(12.dp)
                    ) {
                        if (block.language.isNotEmpty()) {
                            Text(
                                block.language.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = SpaceMono),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
                is MarkdownBlock.Table -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, scheme.outlineVariant)
                            .horizontalScroll(rememberScrollState())
                    ) {
                        Row(Modifier.background(scheme.surfaceVariant)) {
                            block.headers.forEach { h ->
                                Text(
                                    text = h,
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    modifier = Modifier.widthIn(min = 90.dp).padding(8.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
                        block.rows.forEach { row ->
                            Row {
                                row.forEach { cell ->
                                    Text(
                                        text = cell,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.widthIn(min = 90.dp).padding(8.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
                        }
                    }
                }
                MarkdownBlock.Divider -> {
                    HorizontalDivider(color = scheme.outline, thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

private fun toComposeAlign(mode: TextAlignMode): TextAlign = when (mode) {
    TextAlignMode.LEFT -> TextAlign.Start
    TextAlignMode.CENTER -> TextAlign.Center
    TextAlignMode.RIGHT -> TextAlign.End
}

private val INLINE_TOKEN = Regex("(\\*\\*.+?\\*\\*|~~.+?~~|`.+?`|\\*.+?\\*|\\[[^\\]]+\\]\\([^)]+\\))")

private fun inlineAnnotated(text: String, codeBg: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    for (match in INLINE_TOKEN.findAll(text)) {
        if (match.range.first > cursor) {
            append(text.substring(cursor, match.range.first))
        }
        val token = match.value
        when {
            token.startsWith("**") && token.endsWith("**") && token.length > 4 -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(token.substring(2, token.length - 2))
                pop()
            }
            token.startsWith("~~") && token.endsWith("~~") && token.length > 4 -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(token.substring(2, token.length - 2))
                pop()
            }
            token.startsWith("`") && token.endsWith("`") && token.length > 2 -> {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg))
                append(token.substring(1, token.length - 1))
                pop()
            }
            token.startsWith("*") && token.endsWith("*") && token.length > 2 -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(token.substring(1, token.length - 1))
                pop()
            }
            token.startsWith("[") && token.contains("](") && token.endsWith(")") -> {
                val close = token.indexOf("](")
                val label = token.substring(1, close)
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
                append(label)
                pop()
            }
            else -> append(token)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        append(text.substring(cursor))
    }
}
