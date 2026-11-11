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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class RailItem(val route: String, val label: String, val icon: ImageVector, val badge: Int = 0)

@Composable
fun NavRail(items: List<RailItem>, selectedRoute: String?, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = PcTheme.colors
    Column(
        modifier = modifier.fillMaxHeight().width(148.dp).background(colors.background).padding(vertical = PcSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PcSpacing.xs),
    ) {
        Text("PitCrew", style = PcTheme.type.label, color = colors.textSecondary, modifier = Modifier.padding(bottom = PcSpacing.l))
        items.forEach { item ->
            val selected = item.route == selectedRoute
            val source = remember { MutableInteractionSource() }
            val pressed by source.collectIsPressedAsState()
            Column(
                modifier = Modifier
                    .size(width = 124.dp, height = 112.dp)
                    .semantics { this.selected = selected }
                    .pressable(role = Role.Tab, interactionSource = source) { onSelect(item.route) }
                    .background(
                        when {
                            selected -> colors.surfaceHigh
                            pressed -> colors.buttonBasic
                            else -> colors.background
                        },
                        PcShape.medium,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box {
                    Icon(
                        item.icon,
                        contentDescription = null,
                        tint = if (selected) colors.textPrimary else colors.textSecondary,
                        modifier = Modifier.size(40.dp),
                    )
                    if (item.badge > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(start = 32.dp).size(14.dp).background(colors.attention, PcShape.pill),
                        )
                    }
                }
                Text(
                    item.label,
                    style = PcTheme.type.labelSmall,
                    color = if (selected) colors.textPrimary else colors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().height(100.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PcSpacing.m),
    ) {
        if (onBack != null) PcIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "뒤로", onBack)
        Text(title, style = PcTheme.type.titleLarge, color = PcTheme.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
        actions()
    }
}

@Composable
fun SegmentedTabs(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = PcTheme.colors
    Row(
        modifier = modifier.height(80.dp).background(colors.buttonBasic, PcShape.medium).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(200.dp)
                    .semantics { this.selected = selected }
                    .pressable(role = Role.Tab) { onSelect(index) }
                    .background(if (selected) colors.surfaceHigh else colors.buttonBasic.copy(alpha = 0f), PcShape.small),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = PcTheme.type.bodyStrong, color = if (selected) colors.textPrimary else colors.textSecondary)
            }
        }
    }
}

/** 키보드 대신 큰 증감 버튼으로 값을 바꾼다(차량 입력 최소화 원칙). */
@Composable
fun Stepper(
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    decreaseLabel: String = "−",
    increaseLabel: String = "+",
    valueWidth: Dp = 220.dp,
    decreaseEnabled: Boolean = true,
    increaseEnabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PcSpacing.l),
    ) {
        PcButton(decreaseLabel, onDecrease, size = ButtonSize.MEDIUM, modifier = Modifier.width(140.dp), enabled = decreaseEnabled)
        Text(
            value,
            style = PcTheme.type.headline,
            color = PcTheme.colors.textPrimary,
            modifier = Modifier.width(valueWidth),
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        PcButton(increaseLabel, onIncrease, size = ButtonSize.MEDIUM, modifier = Modifier.width(140.dp), enabled = increaseEnabled)
    }
}

@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = PcTheme.type.label, color = PcTheme.colors.textSecondary, modifier = modifier.padding(bottom = PcSpacing.xs))
}

@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.height(64.dp).pressable(onClick = onClick).padding(horizontal = PcSpacing.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PcTheme.type.label, color = PcTheme.colors.textSecondary)
    }
}

@Composable
fun SelectChip(label: String, selected: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = PcTheme.colors
    Row(
        modifier = modifier
            .height(72.dp)
            .semantics { this.selected = selected }
            .pressable(role = Role.Checkbox, onClick = onToggle)
            .background(if (selected) colors.buttonFilled else colors.buttonBasic, PcShape.pill)
            .padding(horizontal = PcSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PcSpacing.xs),
    ) {
        if (selected) {
            Icon(Icons.Rounded.Check, null, tint = colors.onButtonFilled, modifier = Modifier.size(28.dp))
        }
        Text(label, style = PcTheme.type.bodyStrong, color = if (selected) colors.onButtonFilled else colors.textPrimary)
    }
}
