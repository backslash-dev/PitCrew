package ai.pitcrew.app.ui.diagnostics

import ai.pitcrew.app.gleo.GleoActionDispatcher
import ai.pitcrew.app.gleo.GleoStatus
import ai.pitcrew.app.platform.PlatformServices
import ai.pitcrew.app.platform.SdkStatus
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.components.PcCard
import ai.pitcrew.app.ui.components.ScreenHeader
import ai.pitcrew.app.ui.components.StatusBadge
import ai.pitcrew.app.ui.components.Tone
import ai.pitcrew.app.ui.format.distanceLabel
import ai.pitcrew.app.ui.format.minutesLabel
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DiagnosticsScreen(platform: PlatformServices, gleo: GleoActionDispatcher, onBack: () -> Unit) {
    val drivingStatus by platform.drivingState.status.collectAsStateWithLifecycle()
    val driving by platform.drivingState.state.collectAsStateWithLifecycle()
    val batteryStatus by platform.battery.status.collectAsStateWithLifecycle()
    val stateOfCharge by platform.battery.stateOfChargePercent.collectAsStateWithLifecycle()
    val naviStatus by platform.navigation.status.collectAsStateWithLifecycle()
    val destination by platform.navigation.destination.collectAsStateWithLifecycle()
    val locationStatus by platform.location.status.collectAsStateWithLifecycle()
    val location by platform.location.location.collectAsStateWithLifecycle()
    val sttStatus by platform.voiceInput.status.collectAsStateWithLifecycle()
    val listening by platform.voiceInput.listening.collectAsStateWithLifecycle()
    val speech by platform.voiceInput.result.collectAsStateWithLifecycle()
    val sttError by platform.voiceInput.lastError.collectAsStateWithLifecycle()
    val ttsStatus by platform.voiceOutput.status.collectAsStateWithLifecycle()
    val speaking by platform.voiceOutput.speaking.collectAsStateWithLifecycle()
    val gleoExchange by gleo.lastExchange.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        ScreenHeader("차량 연결 진단", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PcSpacing.s)) {
            SdkRow("주행 상태", "CarProperty · CarUxRestrictions", drivingStatus,
                "기어 ${driving.gear} · 주행 규제 ${driving.requiresDistractionOptimization ?: "모름"} · ${driving.interactionMode}")
            SdkRow("배터리", "Vehicle SDK", batteryStatus, stateOfCharge?.let { "잔량 $it%" } ?: "값 없음")
            SdkRow("목적지", "NaviHelper", naviStatus,
                destination?.let { "${it.name} · ${minutesLabel(it.remainTimeSeconds / 60L)} · ${distanceLabel(it.remainDistanceMeters)}" } ?: "경로 없음",
                actionLabel = "다시 조회", onAction = platform.navigation::refreshDestination)
            SdkRow("현재 위치", "FusedLocation", locationStatus,
                location?.let { "%.5f, %.5f".format(it.latitude, it.longitude) } ?: "수신 대기")
            SdkRow("음성 인식", "SpeechToText", sttStatus,
                when {
                    listening -> "듣는 중 ${speech?.text.orEmpty()}"
                    sttError != null -> "오류 $sttError"
                    speech != null -> "“${speech?.text}”"
                    else -> "대기"
                },
                actionLabel = if (listening) "중지" else "듣기",
                onAction = { if (listening) platform.voiceInput.stop() else platform.voiceInput.listen() })
            SdkRow("음성 안내", "TextToSpeech", ttsStatus, if (speaking) "재생 중" else "대기",
                actionLabel = "재생", onAction = { platform.voiceOutput.speak("피트크루 준비됐어요.") })
            SdkRow("최근 Gleo 호출", "Gleo Interface",
                gleoExchange?.response?.takeIf { it.status == GleoStatus.FAIL }?.let { SdkStatus.Failed(it.status.wire) } ?: SdkStatus.Ready,
                gleoExchange?.let { "${it.function} ${it.params} → ${it.response.status.wire}: ${it.response.message}" } ?: "호출 없음")
            Spacer(Modifier.height(PcSpacing.l))
        }
    }
}

@Composable
private fun SdkRow(title: String, source: String, status: SdkStatus, detail: String, actionLabel: String? = null, onAction: () -> Unit = {}) {
    PcCard(Modifier.fillMaxWidth(), padding = PcSpacing.l) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
                    Text(title, style = PcTheme.type.titleSmall, color = PcTheme.colors.textPrimary)
                    Text(source, style = PcTheme.type.label, color = PcTheme.colors.textTertiary)
                }
                Spacer(Modifier.height(PcSpacing.xxs))
                Text(detail, style = PcTheme.type.bodySmall, color = PcTheme.colors.textSecondary)
            }
            when (status) {
                SdkStatus.Connecting -> StatusBadge("연결 중", Tone.NEUTRAL)
                SdkStatus.Ready -> StatusBadge("정상", Tone.ACTIVE)
                is SdkStatus.Failed -> StatusBadge("실패 · ${status.reason}", Tone.NEGATIVE)
            }
            if (actionLabel != null) {
                Spacer(Modifier.height(PcSpacing.m))
                PcButton(actionLabel, onAction, size = ButtonSize.SMALL, enabled = status == SdkStatus.Ready, modifier = Modifier.padding(start = PcSpacing.m))
            }
        }
    }
}
