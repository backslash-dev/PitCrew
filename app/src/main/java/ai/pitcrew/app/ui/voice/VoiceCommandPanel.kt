package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.ui.components.ButtonKind
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.components.PcCard
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun VoiceCommandPanel(state: VoiceCommandState, onCancel: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (state == VoiceCommandState.Idle) return
    val colors = PcTheme.colors
    PcCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PcSpacing.l)) {
            Icon(Icons.Rounded.Mic, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(48.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (state) {
                    is VoiceCommandState.Listening -> {
                        Text("듣고 있어요", style = PcTheme.type.label, color = colors.textSecondary)
                        Text(
                            state.partial.ifBlank { "지각 대응이나 픽업 주문을 말해 주세요" },
                            style = PcTheme.type.title,
                            color = if (state.partial.isBlank()) colors.textTertiary else colors.textPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    is VoiceCommandState.Confirming -> {
                        Text("“${state.heard}”", style = PcTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(state.question, style = PcTheme.type.titleSmall, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    is VoiceCommandState.Working -> {
                        Text("“${state.heard}”", style = PcTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("확인하는 중이에요", style = PcTheme.type.title, color = colors.textPrimary)
                    }
                    is VoiceCommandState.Done -> {
                        state.heard?.let {
                            Text("“$it”", style = PcTheme.type.label, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(state.message, style = PcTheme.type.titleSmall, color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    VoiceCommandState.Idle -> Unit
                }
            }
            when (state) {
                is VoiceCommandState.Listening, is VoiceCommandState.Working, is VoiceCommandState.Confirming ->
                    PcButton("취소", onCancel, kind = ButtonKind.BASIC, size = ButtonSize.MEDIUM)
                is VoiceCommandState.Done -> PcButton("닫기", onDismiss, kind = ButtonKind.BASIC, size = ButtonSize.MEDIUM)
                VoiceCommandState.Idle -> Unit
            }
        }
    }
}
