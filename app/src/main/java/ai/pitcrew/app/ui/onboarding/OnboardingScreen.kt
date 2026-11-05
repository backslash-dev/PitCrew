package ai.pitcrew.app.ui.onboarding

import ai.pitcrew.app.ui.components.ButtonKind
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.components.StatusBadge
import ai.pitcrew.app.ui.components.Tone
import ai.pitcrew.app.ui.home.VOICE_EXAMPLES
import ai.pitcrew.app.ui.theme.PcShape
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private const val PAGE_COUNT = 3

@Composable
fun OnboardingScreen(locationGranted: Boolean, onRequestLocation: () -> Unit, onFinish: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val colors = PcTheme.colors
    Column(Modifier.fillMaxSize().background(colors.background).padding(horizontal = 80.dp, vertical = 64.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PitCrew", style = PcTheme.type.titleSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Text("${page + 1} / $PAGE_COUNT", style = PcTheme.type.label, color = colors.textSecondary)
        }
        Column(Modifier.weight(1f).widthIn(max = 1400.dp), verticalArrangement = Arrangement.Center) {
            when (page) {
                0 -> Page(
                    "운전하는 동안,\n차 밖의 일은 맡기세요",
                    "내비 도착 시간을 기준으로 늦을 때 예약을 바꾸고 일행에게 알려요. 가는 길에 픽업 주문도 도착 시간에 맞춰 넣어요.",
                )
                1 -> Page(
                    "현재 위치를 사용해요",
                    "경로 위의 매장을 찾고 픽업 시간을 계산하는 데 써요.",
                ) {
                    Spacer(Modifier.height(PcSpacing.xl))
                    // 버튼이 배지로 바뀌어도 제목 위치가 흔들리지 않게 높이를 고정한다
                    Box(Modifier.height(ButtonSize.LARGE.height), contentAlignment = Alignment.CenterStart) {
                        if (locationGranted) {
                            StatusBadge("위치 사용 허용됨", Tone.ACTIVE)
                        } else {
                            PcButton("위치 사용 허용", onRequestLocation, kind = ButtonKind.BASIC, size = ButtonSize.LARGE)
                        }
                    }
                }
                else -> Page(
                    "말로 시키고, 말로 승인해요",
                    "Gleo를 부른 뒤 아래처럼 말하세요. 확정이 필요한 일은 먼저 물어보고, 주행 중에는 음성으로만 승인해요.",
                ) {
                    Spacer(Modifier.height(PcSpacing.xl))
                    Column(verticalArrangement = Arrangement.spacedBy(PcSpacing.s)) {
                        VOICE_EXAMPLES.forEach { phrase ->
                            Box(Modifier.background(colors.surface, PcShape.medium).padding(horizontal = PcSpacing.l, vertical = PcSpacing.m)) {
                                Text("“$phrase”", style = PcTheme.type.bodyLarge, color = colors.textPrimary)
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(PcSpacing.xs)) {
                repeat(PAGE_COUNT) { index ->
                    Box(
                        Modifier
                            .height(8.dp)
                            .width(if (index == page) 40.dp else 16.dp)
                            .background(if (index == page) colors.textPrimary else colors.surfaceAccent, PcShape.pill),
                    )
                }
            }
            if (page > 0) {
                PcButton("이전", { page-- }, kind = ButtonKind.BASIC, size = ButtonSize.LARGE, modifier = Modifier.width(200.dp))
                Spacer(Modifier.size(PcSpacing.m))
            }
            PcButton(
                if (page == PAGE_COUNT - 1) "시작하기" else "다음",
                { if (page == PAGE_COUNT - 1) onFinish() else page++ },
                kind = ButtonKind.FILLED,
                size = ButtonSize.LARGE,
                modifier = Modifier.width(320.dp),
            )
        }
    }
}

@Composable
private fun Page(title: String, body: String, extra: @Composable () -> Unit = {}) {
    Text(title, style = PcTheme.type.display, color = PcTheme.colors.textPrimary)
    Spacer(Modifier.height(PcSpacing.l))
    Text(body, style = PcTheme.type.bodyLarge, color = PcTheme.colors.textSecondary, modifier = Modifier.widthIn(max = 1100.dp))
    extra()
}
