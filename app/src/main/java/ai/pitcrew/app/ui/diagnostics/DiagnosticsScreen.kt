package ai.pitcrew.app.ui.diagnostics

import ai.pitcrew.app.gleo.GleoActionDispatcher
import ai.pitcrew.app.gleo.GleoStatus
import ai.pitcrew.app.platform.PlatformServices
import ai.pitcrew.app.platform.SdkStatus
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun DiagnosticsScreen(platform: PlatformServices, gleo: GleoActionDispatcher) {
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

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("PitCrew SDK 진단", style = MaterialTheme.typography.headlineMedium)

            SdkCard(
                title = "CarProperty · 주행 상태",
                status = drivingStatus,
                detail = "기어 ${driving.gear} · DO 필요 ${driving.requiresDistractionOptimization ?: "미연결"} · 입력 ${driving.interactionMode}",
            )
            SdkCard(
                title = "Vehicle · 배터리",
                status = batteryStatus,
                detail = stateOfCharge?.let { "잔량 $it%" } ?: "값 없음",
            )
            SdkCard(
                title = "NaviHelper · 목적지",
                status = naviStatus,
                detail = destination?.let { "${it.name} · ${it.remainTimeSeconds / 60}분 · ${it.remainDistanceMeters}m" } ?: "경로 없음",
                actionLabel = "목적지 조회",
                onAction = platform.navigation::refreshDestination,
            )
            SdkCard(
                title = "FusedLocation · 현재 위치",
                status = locationStatus,
                detail = location?.let { "%.5f, %.5f · %.0f°".format(it.latitude, it.longitude, it.heading) } ?: "수신 대기",
            )
            SdkCard(
                title = "SpeechToText",
                status = sttStatus,
                detail = when {
                    listening -> "듣는 중… ${speech?.text.orEmpty()}"
                    sttError != null -> "오류 $sttError"
                    speech != null -> "\"${speech?.text}\""
                    else -> "대기"
                },
                actionLabel = if (listening) "중지" else "음성 인식",
                onAction = { if (listening) platform.voiceInput.stop() else platform.voiceInput.listen() },
            )
            SdkCard(
                title = "TextToSpeech",
                status = ttsStatus,
                detail = if (speaking) "재생 중" else "대기",
                actionLabel = "안내 재생",
                onAction = { platform.voiceOutput.speak("피트크루 준비됐어요.") },
            )
            SdkCard(
                title = "Gleo · 최근 호출",
                status = gleoExchange?.response
                    ?.takeIf { it.status == GleoStatus.FAIL }
                    ?.let { SdkStatus.Failed(it.status.wire) }
                    ?: SdkStatus.Ready,
                detail = gleoExchange?.let { "${it.function} ${it.params}\n→ ${it.response.status.wire}: ${it.response.message}" } ?: "호출 없음",
            )
        }
    }
}

@Composable
private fun SdkCard(
    title: String,
    status: SdkStatus,
    detail: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(status.label(), color = status.color(), style = MaterialTheme.typography.bodyLarge)
                Text(detail, style = MaterialTheme.typography.bodyLarge)
            }
            if (actionLabel != null) {
                Button(onClick = onAction, enabled = status == SdkStatus.Ready, modifier = Modifier.width(200.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

private fun SdkStatus.label(): String = when (this) {
    SdkStatus.Connecting -> "연결 중"
    SdkStatus.Ready -> "정상"
    is SdkStatus.Failed -> "실패: $reason"
}

@Composable
private fun SdkStatus.color(): Color = when (this) {
    SdkStatus.Connecting -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    SdkStatus.Ready -> Color(0xFF4CD964)
    is SdkStatus.Failed -> MaterialTheme.colorScheme.error
}
