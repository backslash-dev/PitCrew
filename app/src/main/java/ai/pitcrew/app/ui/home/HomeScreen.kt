package ai.pitcrew.app.ui.home

import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.errand.LateArrivalPlanner
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.platform.DrivingState
import ai.pitcrew.app.platform.InteractionMode
import ai.pitcrew.app.ui.components.ButtonKind
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.voice.VoiceCommandPanel
import ai.pitcrew.app.ui.voice.VoiceCommandState
import ai.pitcrew.app.ui.components.Hairline
import ai.pitcrew.app.ui.components.ListRow
import ai.pitcrew.app.ui.components.PcCard
import ai.pitcrew.app.ui.components.SectionHeader
import ai.pitcrew.app.ui.components.TextAction
import ai.pitcrew.app.ui.errand.ErrandCard
import ai.pitcrew.app.ui.errand.outcomeLine
import ai.pitcrew.app.ui.errand.shortKind
import ai.pitcrew.app.ui.errand.title
import ai.pitcrew.app.ui.timeline.TripTimeline
import ai.pitcrew.app.ui.timeline.TripTimelineBar
import ai.pitcrew.app.ui.format.distanceLabel
import ai.pitcrew.app.ui.format.hhmm
import ai.pitcrew.app.ui.format.minutesLabel
import ai.pitcrew.app.ui.reservations.ReservationRow
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import ai.pitcrew.app.ui.theme.WindowWidth
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime

data class HomeState(
    val driving: DrivingState,
    val destination: Destination?,
    val errands: List<Errand>,
    val reservations: List<Reservation>,
    val contacts: List<Contact>,
    val now: LocalDateTime,
    val voice: VoiceCommandState,
    val listening: Boolean,
)

class HomeActions(
    val openErrand: (String) -> Unit,
    val approve: (String) -> Unit,
    val decline: (String) -> Unit,
    val openReservation: (String) -> Unit,
    val openReservations: () -> Unit,
    val openHistory: () -> Unit,
    val startVoice: () -> Unit,
    val cancelVoice: () -> Unit,
    val dismissVoice: () -> Unit,
    val undo: (String) -> Unit,
)

@Composable
fun HomeScreen(state: HomeState, actions: HomeActions, width: WindowWidth) {
    val trip: @Composable (Modifier) -> Unit = { TripPanel(state, it) }
    val work: @Composable (Modifier) -> Unit = { WorkPanel(state, actions, it) }
    val today: @Composable (Modifier) -> Unit = { TodayPanel(state, actions, it) }

    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        Row(Modifier.fillMaxWidth().height(100.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("오늘의 이동", style = PcTheme.type.titleLarge, color = PcTheme.colors.textPrimary, modifier = Modifier.weight(1f))
            // 앱 음성 입력은 주차 중 화면에서만 연다. 주행 중에는 Gleo로 말한다
            if (state.driving.interactionMode == InteractionMode.TOUCH && state.voice == VoiceCommandState.Idle) {
                PcButton("말하기", actions.startVoice, kind = ButtonKind.BASIC, size = ButtonSize.MEDIUM, icon = Icons.Rounded.Mic)
            }
        }
        VoiceCommandPanel(
            state.voice,
            onCancel = actions.cancelVoice,
            onDismiss = actions.dismissVoice,
            modifier = Modifier.padding(bottom = if (state.voice == VoiceCommandState.Idle) 0.dp else PcSpacing.l),
        )
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(PcSpacing.l)) {
            trip(Modifier.fillMaxWidth())
            if (width == WindowWidth.COMPACT) {
                work(Modifier.fillMaxWidth())
                today(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(PcSpacing.l)) {
                    work(Modifier.weight(1.3f))
                    today(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(PcSpacing.l))
    }
}

@Composable
private fun TripPanel(state: HomeState, modifier: Modifier) {
    val colors = PcTheme.colors
    PcCard(modifier) {
        val destination = state.destination
        if (destination == null) {
            Text("경로 없음", style = PcTheme.type.title, color = colors.textPrimary)
            Spacer(Modifier.height(PcSpacing.xs))
            Text(
                "내비게이션에서 목적지를 정하면 도착 시간을 기준으로 지각 대응과 픽업 시간을 계산해요.",
                style = PcTheme.type.body,
                color = colors.textSecondary,
            )
            return@PcCard
        }
        val arrival = LateArrivalPlanner.arrivalAt(state.now, destination.remainTimeSeconds)
        TripTimelineBar(
            TripTimeline.build(state.now, arrival, state.reservations, state.errands),
            destinationName = destination.name,
            meta = "${minutesLabel(destination.remainTimeSeconds / 60L)} · ${distanceLabel(destination.remainDistanceMeters)}",
        )
    }
}

@Composable
private fun WorkPanel(state: HomeState, actions: HomeActions, modifier: Modifier) {
    // 답할 제안은 한 번에 하나만 보인다
    val awaiting = state.errands.filter { it.state == ErrandState.AWAITING_APPROVAL }
    val active = listOfNotNull(awaiting.firstOrNull()) + state.errands.filter { it.state == ErrandState.IN_PROGRESS }
    val recent = state.errands.filterNot { it.isActive }.take(2)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
        SectionHeader("처리할 일")
        if (active.isEmpty()) {
            VoiceHintCard()
        } else {
            if (awaiting.size > 1) {
                Text(
                    "먼저 답할 일 1/${awaiting.size} · 다음: ${awaiting.drop(1).joinToString(", ") { it.shortKind() }}",
                    style = PcTheme.type.label,
                    color = PcTheme.colors.textSecondary,
                )
            }
            active.forEach { errand ->
                ErrandCard(
                    errand,
                    state.driving.interactionMode,
                    onOpen = { actions.openErrand(errand.id) },
                    onApprove = { actions.approve(errand.id) },
                    onDecline = { actions.decline(errand.id) },
                    listening = state.listening,
                )
            }
        }
        if (recent.isNotEmpty()) {
            SectionHeader("최근", trailing = { TextAction("전체 기록", actions.openHistory) })
            recent.forEach { errand ->
                val undoable = errand.canUndo(state.now) && state.driving.interactionMode == InteractionMode.TOUCH
                ListRow(
                    errand.title(),
                    // 시각은 기록 화면에 있으므로 결과만 보여 줄바꿈이 어색하게 끊기지 않게 한다
                    supporting = errand.outcomeLine().orEmpty(),
                    leading = when (errand.state) {
                        ErrandState.COMPLETED -> Icons.Rounded.CheckCircle
                        ErrandState.CANCELLED -> Icons.AutoMirrored.Rounded.Undo
                        else -> Icons.Rounded.ErrorOutline
                    },
                    trailing = if (undoable) ({ TextAction("되돌리기", { actions.undo(errand.id) }) }) else null,
                    onClick = { actions.openErrand(errand.id) },
                )
            }
        }
    }
}

@Composable
fun VoiceHintCard(modifier: Modifier = Modifier, title: String? = "지금 처리할 일이 없어요") {
    val colors = PcTheme.colors
    PcCard(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(title, style = PcTheme.type.titleSmall, color = colors.textPrimary)
            Spacer(Modifier.height(PcSpacing.xs))
        }
        Text("Gleo를 부른 뒤 이렇게 말해 보세요.", style = PcTheme.type.body, color = colors.textSecondary)
        Spacer(Modifier.height(PcSpacing.l))
        VOICE_EXAMPLES.forEachIndexed { index, phrase ->
            if (index > 0) {
                Spacer(Modifier.height(PcSpacing.m))
                Hairline()
                Spacer(Modifier.height(PcSpacing.m))
            }
            Text("“$phrase”", style = PcTheme.type.bodyLarge, color = colors.textPrimary)
        }
    }
}

val VOICE_EXAMPLES = listOf(
    "피트크루 앱으로 7시 예약 지각 처리해줘",
    "가는 길에 아메리카노 픽업 주문해줘",
    "피트크루 앱으로 방금 거 취소해줘",
)

@Composable
private fun TodayPanel(state: HomeState, actions: HomeActions, modifier: Modifier) {
    val upcoming = state.reservations.filter { !it.time.isBefore(state.now.minusHours(1)) }.take(3)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
        SectionHeader("다가오는 예약", trailing = { TextAction("관리", actions.openReservations) })
        if (upcoming.isEmpty()) {
            Text("남은 예약이 없어요.", style = PcTheme.type.body, color = PcTheme.colors.textSecondary)
        }
        upcoming.forEach { reservation ->
            ReservationRow(reservation, state.contacts, onClick = { actions.openReservation(reservation.id) })
        }
    }
}
