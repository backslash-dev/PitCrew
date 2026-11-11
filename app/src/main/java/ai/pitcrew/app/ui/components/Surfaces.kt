package ai.pitcrew.app.ui.components

import ai.pitcrew.app.ui.theme.PcShape
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 그림자 없이 면의 밝기 차이로만 층을 나눈다(차량 UI의 평면 스타일). */
@Composable
fun PcCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = PcSpacing.xl,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = PcTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val base = if (onClick != null) modifier.pressable(interactionSource = source, onClick = onClick) else modifier
    Column(
        modifier = base
            .background(if (pressed) colors.surfaceHigh else colors.surface, PcShape.large)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = PcTheme.type.titleSmall, color = PcTheme.colors.textPrimary, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(PcTheme.colors.divider))
}

@Composable
fun KeyFigure(label: String, value: String, modifier: Modifier = Modifier, tone: Tone = Tone.NEUTRAL, large: Boolean = false) {
    val colors = PcTheme.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = PcTheme.type.label, color = colors.textSecondary)
        Text(
            value,
            style = if (large) PcTheme.type.headline else PcTheme.type.titleLarge,
            color = if (tone == Tone.NEUTRAL) colors.textPrimary else tone.color(colors),
            maxLines = 1,
        )
    }
}

@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = PcTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val base = if (onClick != null) modifier.pressable(interactionSource = source, onClick = onClick) else modifier
    Row(
        modifier = base
            .fillMaxWidth()
            .background(if (pressed) colors.buttonBasicPressed else colors.surface, PcShape.medium)
            .padding(horizontal = PcSpacing.l, vertical = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(PcSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(
                Modifier.size(64.dp).background(colors.buttonBasic, PcShape.small),
                contentAlignment = Alignment.Center,
            ) { Icon(leading, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(32.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = PcTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(supporting, style = PcTheme.type.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(title: String, message: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val colors = PcTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = PcSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PcSpacing.s),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(56.dp))
        Text(title, style = PcTheme.type.bodyStrong, color = colors.textPrimary)
        Text(message, style = PcTheme.type.bodySmall, color = colors.textSecondary)
    }
}
