package ai.pitcrew.app.ui.components

import ai.pitcrew.app.ui.theme.PcColors
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** 상태 색 의미: 앰버=사용자 승인 필요, 파랑=진행 중, 초록=완료, 빨강=실패. 그 외는 무채색. */
enum class Tone { NEUTRAL, ATTENTION, POSITIVE, ACTIVE, NEGATIVE }

fun Tone.color(colors: PcColors): Color = when (this) {
    Tone.NEUTRAL -> colors.textSecondary
    Tone.ATTENTION -> colors.attention
    Tone.POSITIVE -> colors.positive
    Tone.ACTIVE -> colors.active
    Tone.NEGATIVE -> colors.negative
}

/** 상태는 배경 없는 글자로만 표시한다. 알약 모양 라벨은 쓰지 않는다. */
@Composable
fun StatusBadge(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val colors = PcTheme.colors
    Text(text, style = PcTheme.type.label, color = tone.color(colors), modifier = modifier, maxLines = 1)
}
