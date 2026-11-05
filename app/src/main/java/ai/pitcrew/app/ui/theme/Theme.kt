package ai.pitcrew.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val PitCrewColors = darkColorScheme(
    primary = Color(0xFFFFB300),
    onPrimary = Color(0xFF1A1200),
    background = Color(0xFF0E1013),
    surface = Color(0xFF171A1F),
    onSurface = Color(0xFFE8EAED),
    error = Color(0xFFFF5449),
)

@Composable
fun PitCrewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PitCrewColors, content = content)
}
