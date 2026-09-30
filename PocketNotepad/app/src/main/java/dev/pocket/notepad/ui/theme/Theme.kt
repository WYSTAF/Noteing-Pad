package dev.pocket.notepad.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import dev.pocket.notepad.model.ThemeMode

// Nothing-inspired schemes (skill: nothing-design): pure monochrome, OLED black,
// a single red accent reserved for the caret and status squares.
private val Light = lightColorScheme(
    primary = Black, onPrimary = PaperLight,
    primaryContainer = SurfaceLight, onPrimaryContainer = InkLight,
    secondary = Black, onSecondary = PaperLight,
    secondaryContainer = SelectionLight, onSecondaryContainer = InkLight,
    background = PaperLight, onBackground = InkLight,
    surface = PaperLight, onSurface = InkLight,
    surfaceVariant = SurfaceLight, onSurfaceVariant = GraySecondaryLight,
    outline = GrayTertiaryLight, outlineVariant = BorderLight,
    surfaceContainer = SurfaceLight, surfaceContainerHigh = SurfaceLight,
    surfaceContainerHighest = SurfaceLight, surfaceContainerLow = PaperLight,
    surfaceContainerLowest = PaperLight, inverseSurface = Black, inverseOnSurface = PaperLight,
    error = NothingRed, onError = PaperLight
)
private val Dark = darkColorScheme(
    primary = White, onPrimary = Void,
    primaryContainer = SurfaceNight, onPrimaryContainer = White,
    secondary = White, onSecondary = Void,
    secondaryContainer = SelectionNight, onSecondaryContainer = White,
    background = Void, onBackground = White,
    surface = Void, onSurface = White,
    surfaceVariant = SurfaceNight, onSurfaceVariant = GraySecondaryNight,
    outline = GrayTertiaryNight, outlineVariant = BorderNight,
    surfaceContainer = SurfaceNight, surfaceContainerHigh = SurfaceNight,
    surfaceContainerHighest = SurfaceNight, surfaceContainerLow = Void,
    surfaceContainerLowest = Void, inverseSurface = White, inverseOnSurface = Void,
    error = NothingRed, onError = Void
)

// Industrial geometry: nothing rounds. Zero-radius squares satisfy the
// CornerBasedShape contract while looking perfectly square.
val NothingShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp), small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp), large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp)
)

@Composable
fun useDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun NotepadTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = NotepadTypography,
        shapes = NothingShapes,
        content = content
    )
}
