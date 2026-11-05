package ai.pitcrew.app.ui.components

import ai.pitcrew.app.ui.theme.PcColors
import ai.pitcrew.app.ui.theme.PcShape
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 상태 색 의미: 앰버=사용자 승인 필요, 파랑=진행 중, 초록=완료, 빨강=실패. 그 외는 무채색. */
enum class Tone { NEUTRAL, ATTENTION, POSITIVE, ACTIVE, NEGATIVE }

fun Tone.color(colors: PcColors): Color = when (this) {
    Tone.NEUTRAL -> colors.textSecondary
    Tone.ATTENTION -> colors.attention
    Tone.POSITIVE -> colors.positive
    Tone.ACTIVE -> colors.active
    Tone.NEGATIVE -> colors.negative
}

fun Tone.container(colors: PcColors): Color = when (this) {
    Tone.NEUTRAL -> colors.buttonBasic
    Tone.ATTENTION -> colors.attentionContainer
    Tone.POSITIVE -> colors.positiveContainer
    Tone.ACTIVE -> colors.activeContainer
    Tone.NEGATIVE -> colors.negativeContainer
}

@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val colors = PcTheme.colors
    Row(
        modifier = modifier
            .background(tone.container(colors), PcShape.pill)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(tone.color(colors), CircleShape))
        Text(text, style = PcTheme.type.label, color = if (tone == Tone.NEUTRAL) colors.textSecondary else tone.color(colors))
    }
}
