package ai.pitcrew.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalPcColors = staticCompositionLocalOf { DarkPcColors }

object PcTheme {
    val colors: PcColors
        @Composable @ReadOnlyComposable get() = LocalPcColors.current
    val type: PcTypography get() = PcTypography
}

/** Material은 텍스트 필드 등 기본 컴포넌트 색만 맞춘다. */
@Composable
fun PitCrewTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkPcColors else LightPcColors
    val material = if (darkTheme) {
        darkColorScheme(
            primary = colors.buttonFilled,
            onPrimary = colors.onButtonFilled,
            background = colors.background,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            onBackground = colors.textPrimary,
            error = colors.negative,
        )
    } else {
        lightColorScheme(
            primary = colors.buttonFilled,
            onPrimary = colors.onButtonFilled,
            background = colors.background,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            onBackground = colors.textPrimary,
            error = colors.negative,
        )
    }
    CompositionLocalProvider(LocalPcColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
