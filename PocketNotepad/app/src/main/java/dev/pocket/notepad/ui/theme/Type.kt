package dev.pocket.notepad.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.pocket.notepad.R

// Nothing-style type system (skill: nothing-design).
// Space Grotesk = UI/display, Space Mono = data/counts, Doto available for accents.
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk, FontWeight.Normal),
    Font(R.font.space_grotesk, FontWeight.Medium),
    Font(R.font.space_grotesk, FontWeight.Bold)
)
val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold)
)

private val NoHint = PlatformTextStyle(includeFontPadding = false)

val NotepadTypography = Typography(
    // Display layer: the document title is the only hero text on screen.
    titleLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em, platformStyle = NoHint),
    titleMedium = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.02.em, platformStyle = NoHint),
    // Body layer.
    bodyLarge = TextStyle(fontFamily = SpaceGrotesk, fontSize = 16.sp, lineHeight = 24.sp,
        letterSpacing = 0.01.em, platformStyle = NoHint),
    bodyMedium = TextStyle(fontFamily = SpaceGrotesk, fontSize = 14.sp, lineHeight = 21.sp,
        platformStyle = NoHint),
    bodySmall = TextStyle(fontFamily = SpaceGrotesk, fontSize = 13.sp, lineHeight = 19.sp,
        platformStyle = NoHint),
    // Metadata layer: uppercase micro-labels, mono data.
    labelLarge = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.06.em, platformStyle = NoHint),
    labelMedium = TextStyle(fontFamily = SpaceMono, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 0.02.em, platformStyle = NoHint),
    labelSmall = TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.08.em, platformStyle = NoHint)
)
