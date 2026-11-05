package ai.pitcrew.app.ui.settings

import ai.pitcrew.app.BuildConfig
import ai.pitcrew.app.errand.ChargePlanner
import ai.pitcrew.app.ui.components.ListRow
import ai.pitcrew.app.ui.components.ScreenHeader
import ai.pitcrew.app.ui.components.SectionHeader
import ai.pitcrew.app.ui.components.TextAction
import ai.pitcrew.app.ui.home.VoiceHintCard
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(proactive: Boolean, onToggleProactive: () -> Unit, onReplayOnboarding: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val chevron: @Composable () -> Unit = {
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = PcTheme.colors.textTertiary)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        ScreenHeader("설정")
        Column(
            Modifier.weight(1f).widthIn(max = 1200.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PcSpacing.s),
        ) {
            SectionHeader("말하는 방법")
            VoiceHintCard(title = null)
            Spacer(Modifier.height(PcSpacing.m))
            SectionHeader("PitCrew가 먼저 묻기")
            ListRow(
                if (proactive) "켜짐" else "꺼짐",
                supporting = "예약에 늦을 때, 배터리가 ${ChargePlanner.LOW_PERCENT}% 아래일 때, 자주 픽업하던 시간일 때 음성으로 먼저 물어봐요",
                leading = Icons.Rounded.RecordVoiceOver,
                trailing = { TextAction(if (proactive) "끄기" else "켜기", onToggleProactive) },
                onClick = onToggleProactive,
            )
            Spacer(Modifier.height(PcSpacing.m))
            SectionHeader("앱")
            ListRow("사용 안내 다시 보기", supporting = "권한과 음성 사용법을 다시 확인해요", leading = Icons.Rounded.MenuBook, trailing = chevron, onClick = onReplayOnboarding)
            ListRow("차량 연결 진단", supporting = "주행 상태·내비·위치·음성 SDK 연결 상태", leading = Icons.Rounded.Tune, trailing = chevron, onClick = onOpenDiagnostics)
            ListRow("버전", supporting = "${BuildConfig.VERSION_NAME} · 예약·주문·줄서기는 시연용 연동으로 동작해요", leading = Icons.Rounded.Info)
            Spacer(Modifier.height(PcSpacing.l))
            Text(
                "주행 중에는 화면 대신 Gleo 음성으로만 승인할 수 있어요. 주차하면 화면에서도 승인할 수 있어요.",
                style = PcTheme.type.bodySmall,
                color = PcTheme.colors.textTertiary,
            )
            Spacer(Modifier.height(PcSpacing.l))
        }
    }
}
