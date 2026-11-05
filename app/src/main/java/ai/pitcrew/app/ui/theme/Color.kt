package ai.pitcrew.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Pleos Connect 디자인 가이드의 basic 그레이스케일. 다크 테마는 단계가 반전된다. */
private object Basic {
    val n00 = Color(0xFFFFFFFF)
    val n50 = Color(0xFFF7F8FA)
    val n100 = Color(0xFFEDEEF2)
    val n200 = Color(0xFFE0E1E6)
    val n300 = Color(0xFFC7C9CE)
    val n400 = Color(0xFFA4A8AE)
    val n500 = Color(0xFF686A72)
    val n600 = Color(0xFF44464E)
    val n700 = Color(0xFF313236)
    val n800 = Color(0xFF1D1E21)
    val n900 = Color(0xFF131417)
}

@Immutable
data class PcColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val surfaceAccent: Color,
    val divider: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val buttonBasic: Color,
    val buttonBasicPressed: Color,
    val buttonFilled: Color,
    val buttonFilledPressed: Color,
    val onButtonFilled: Color,
    val attention: Color,
    val attentionContainer: Color,
    val positive: Color,
    val positiveContainer: Color,
    val active: Color,
    val activeContainer: Color,
    val negative: Color,
    val negativeContainer: Color,
    val isDark: Boolean,
)

val LightPcColors = PcColors(
    background = Basic.n50,
    surface = Basic.n00,
    surfaceHigh = Basic.n100,
    surfaceAccent = Basic.n200,
    divider = Basic.n200,
    textPrimary = Basic.n900,
    textSecondary = Basic.n500,
    textTertiary = Basic.n400,
    buttonBasic = Color(0x0D131417),
    buttonBasicPressed = Color(0x1A131417),
    buttonFilled = Basic.n600,
    buttonFilledPressed = Basic.n700,
    onButtonFilled = Basic.n00,
    attention = Color(0xFFC77A00),
    attentionContainer = Color(0x1FE89A00),
    positive = Color(0xFF0064FF),
    positiveContainer = Color(0x1A0064FF),
    active = Color(0xFF02A657),
    activeContainer = Color(0x1A02C265),
    negative = Color(0xFFFE3D16),
    negativeContainer = Color(0x1AFE3D16),
    isDark = false,
)

val DarkPcColors = PcColors(
    background = Basic.n900,
    surface = Basic.n800,
    surfaceHigh = Basic.n700,
    surfaceAccent = Basic.n600,
    divider = Color(0x14FFFFFF),
    textPrimary = Basic.n00,
    textSecondary = Color(0xB3FFFFFF),
    textTertiary = Color(0x73FFFFFF),
    buttonBasic = Color(0x14FFFFFF),
    buttonBasicPressed = Color(0x24FFFFFF),
    buttonFilled = Basic.n200,
    buttonFilledPressed = Basic.n400,
    onButtonFilled = Basic.n900,
    attention = Color(0xFFFFB224),
    attentionContainer = Color(0x26FFB224),
    positive = Color(0xFF0082FF),
    positiveContainer = Color(0x290082FF),
    active = Color(0xFF00E074),
    activeContainer = Color(0x2400E074),
    negative = Color(0xFFFF4C28),
    negativeContainer = Color(0x29FF4C28),
    isDark = true,
)
