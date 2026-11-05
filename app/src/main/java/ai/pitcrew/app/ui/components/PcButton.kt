package ai.pitcrew.app.ui.components

import ai.pitcrew.app.ui.theme.PcShape
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ButtonKind { FILLED, BASIC, ATTENTION }

/** Pleos 기본 버튼 크기: Large 100/32, Medium 80/24, Small 64/16. */
enum class ButtonSize(val height: Dp, val shape: RoundedCornerShape, val horizontalPadding: Dp, val iconSize: Dp) {
    LARGE(100.dp, PcShape.large, 40.dp, 36.dp),
    MEDIUM(80.dp, PcShape.medium, 32.dp, 32.dp),
    SMALL(64.dp, PcShape.small, 24.dp, 28.dp),
}

@Composable
fun PcButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.BASIC,
    size: ButtonSize = ButtonSize.MEDIUM,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val colors = PcTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val (container, content) = when (kind) {
        ButtonKind.FILLED -> (if (pressed) colors.buttonFilledPressed else colors.buttonFilled) to colors.onButtonFilled
        ButtonKind.BASIC -> (if (pressed) colors.buttonBasicPressed else colors.buttonBasic) to colors.textPrimary
        ButtonKind.ATTENTION -> colors.attention.copy(alpha = if (pressed) 0.85f else 1f) to Color(0xFF1A1200)
    }
    val textStyle: TextStyle = if (size == ButtonSize.SMALL) PcTheme.type.label else PcTheme.type.bodyStrong
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.4f)
            .height(size.height)
            .pressable(enabled = enabled, interactionSource = source, onClick = onClick)
            .background(container, size.shape)
            .padding(horizontal = size.horizontalPadding),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(size.iconSize))
        Text(text, style = textStyle, color = content, maxLines = 1)
    }
}

@Composable
fun PcIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: ButtonSize = ButtonSize.MEDIUM,
) {
    val colors = PcTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(size.height)
            .pressable(interactionSource = source, onClick = onClick)
            .background(if (pressed) colors.buttonBasicPressed else colors.buttonBasic, size.shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = colors.textPrimary, modifier = Modifier.size(size.iconSize))
    }
}
