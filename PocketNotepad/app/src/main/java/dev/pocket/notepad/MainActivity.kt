package dev.pocket.notepad

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pocket.notepad.ui.EditorScreen
import dev.pocket.notepad.ui.theme.NotepadTheme
import dev.pocket.notepad.ui.theme.useDarkTheme

class MainActivity : ComponentActivity() {
    private val model: NotepadViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by model.ui.collectAsStateWithLifecycle()
            val dark = useDarkTheme(state.theme)
            SideEffect {
                // Monochrome bars: pure white on light, pure black (OLED) on dark.
                val bars = if (dark) SystemBarStyle.dark(Color.BLACK)
                    else SystemBarStyle.light(Color.WHITE, Color.WHITE)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            NotepadTheme(dark) { EditorScreen(model) }
        }
    }
    override fun onStop() {
        model.flushForStop()
        super.onStop()
    }
}
