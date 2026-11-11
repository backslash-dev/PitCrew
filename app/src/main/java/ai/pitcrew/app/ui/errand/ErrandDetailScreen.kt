package ai.pitcrew.app.ui.errand

import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Store
import ai.pitcrew.app.platform.InteractionMode
import ai.pitcrew.app.ui.components.ButtonKind
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.EmptyState
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.components.Hairline
import ai.pitcrew.app.ui.components.KeyFigure
import ai.pitcrew.app.ui.components.ListRow
import ai.pitcrew.app.ui.components.PcCard
import ai.pitcrew.app.ui.components.ScreenHeader
import ai.pitcrew.app.ui.components.SectionHeader
import ai.pitcrew.app.ui.components.Tone
import ai.pitcrew.app.ui.format.hhmm
import ai.pitcrew.app.ui.format.minutesLabel
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import ai.pitcrew.app.ui.theme.WindowWidth
import ai.pitcrew.app.ui.timeline.TripTimeline
import ai.pitcrew.app.ui.timeline.TripTimelineBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
import java.time.LocalTime

@Composable
fun ErrandDetailScreen(
    errand: Errand?,
    mode: InteractionMode,
    width: WindowWidth,
    onBack: () -> Unit,
    onApprove: (String, ApprovalChannel) -> Unit,
    onDecline: (String) -> Unit,
    now: LocalDateTime,
    listening: Boolean,
    onUndo: (String) -> Unit,
    timeline: TripTimeline? = null,
    storeOptions: List<StoreOption> = emptyList(),
    onSwitchStore: (Store) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        ScreenHeader(errand?.screenTitle() ?: "작업", onBack = onBack)
        if (errand == null) {
            EmptyState("작업을 찾을 수 없어요", "이미 정리되었을 수 있어요.", icon = Icons.Rounded.SearchOff)
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = PcSpacing.xl)) {
            Text(errand.title(), style = PcTheme.type.headline, color = PcTheme.colors.textPrimary)
            Spacer(Modifier.height(PcSpacing.xs))
            Text(
                errand.outcomeLine() ?: errand.summary(),
                style = PcTheme.type.bodyLarge,
                color = if (errand.state == ErrandState.FAILED) PcTheme.colors.negative else PcTheme.colors.textSecondary,
            )
            Spacer(Modifier.height(PcSpacing.xl))
            if (timeline != null && errand.isActive) {
                PcCard(Modifier.fillMaxWidth()) { TripTimelineBar(timeline) }
                Spacer(Modifier.height(PcSpacing.l))
            }

            val figures: @Composable () -> Unit = { Figures(errand) }
            val timeline: @Composable () -> Unit = {
                PcCard(Modifier.fillMaxWidth()) {
                    SectionHeader("진행")
                    Spacer(Modifier.height(PcSpacing.s))
                    StepTimeline(errand)
                    // 되돌리기에 실패하면 작업은 완료 상태로 남으므로 실패 사유를 따로 보인다
                    if (errand.state == ErrandState.COMPLETED && errand.undoNote != null) {
                        Spacer(Modifier.height(PcSpacing.s))
                        Text("되돌리지 못했어요 · ${errand.undoNote}", style = PcTheme.type.body, color = PcTheme.colors.negative)
                    }
                }
            }
            if (width == WindowWidth.COMPACT) {
                figures()
                Spacer(Modifier.height(PcSpacing.l))
                timeline()
                Companions(errand)
                DiningPlan(errand)
                MeetupPlan(errand)
                MailDraft(errand)
                OtherStores(errand, mode, storeOptions, onSwitchStore)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(PcSpacing.l)) {
                    Column(Modifier.weight(1f)) {
                        figures()
                        Companions(errand)
                        DiningPlan(errand)
                        MeetupPlan(errand)
                        MailDraft(errand)
                        OtherStores(errand, mode, storeOptions, onSwitchStore)
                    }
                    Column(Modifier.weight(1f)) { timeline() }
                }
            }
        }
        if (errand.state == ErrandState.AWAITING_APPROVAL) {
            Hairline()
            ApprovalActions(
                mode,
                onApprove = { onApprove(errand.id, ApprovalChannel.TOUCH) },
                onDecline = { onDecline(errand.id) },
                modifier = Modifier.padding(vertical = PcSpacing.l),
                voiceHint = errand.voiceApprovalHint(listening),
                labels = errand.approvalLabels(),
            )
        } else if (errand.canUndo(now) || errand.undoing) {
            Hairline()
            UndoBar(errand, mode, onUndo = { onUndo(errand.id) }, modifier = Modifier.padding(vertical = PcSpacing.l))
        }
    }
}

@Composable
private fun UndoBar(errand: Errand, mode: InteractionMode, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PcSpacing.l)) {
        Text(
            if (mode == InteractionMode.TOUCH) "잘못 시켰다면 끝낸 지 ${Errand.UNDO_WINDOW.toMinutes()}분 안에 되돌릴 수 있어요"
            else "되돌리려면 Gleo에게 \"피트크루 앱으로 방금 거 취소해\"라고 말하세요",
            style = PcTheme.type.body,
            color = PcTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (mode == InteractionMode.TOUCH) {
            PcButton(if (errand.undoing) "되돌리는 중" else "되돌리기", onUndo, kind = ButtonKind.BASIC, size = ButtonSize.LARGE, icon = Icons.AutoMirrored.Rounded.Undo, enabled = !errand.undoing)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Figures(errand: Errand) {
    PcCard(Modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(PcSpacing.xxl),
            verticalArrangement = Arrangement.spacedBy(PcSpacing.l),
        ) {
            when (val d = errand.detail) {
                is ErrandDetail.LateArrival -> {
                    KeyFigure("약속", d.appointment.hhmm(), Modifier.widthIn(min = 160.dp))
                    KeyFigure("도착 예정", d.estimatedArrival.hhmm(), Modifier.widthIn(min = 160.dp))
                    KeyFigure("늦는 시간", minutesLabel(d.delayMinutes), Modifier.widthIn(min = 160.dp), tone = Tone.NEGATIVE)
                    KeyFigure("변경할 시간", d.proposedTime.hhmm(), Modifier.widthIn(min = 160.dp))
                }
                is ErrandDetail.Pickup -> {
                    KeyFigure("픽업", d.readyAt.hhmm(), Modifier.widthIn(min = 160.dp))
                    KeyFigure("우회", "+${d.store.detourMinutes}분", Modifier.widthIn(min = 160.dp))
                    KeyFigure("조리", "${d.store.prepMinutes}분", Modifier.widthIn(min = 160.dp))
                }
                is ErrandDetail.ChargeAndDine -> {
                    KeyFigure("배터리", d.batteryPercent?.let { "$it%" } ?: "알 수 없음", Modifier.widthIn(min = 160.dp), tone = Tone.ATTENTION)
                    KeyFigure("충전소 도착", d.arriveAt.hhmm(), Modifier.widthIn(min = 160.dp))
                    KeyFigure("충전", "약 ${d.chargeMinutes}분", Modifier.widthIn(min = 160.dp))
                    KeyFigure("식당 대기", "${d.restaurant.waitMinutes}분", Modifier.widthIn(min = 160.dp))
                }
                is ErrandDetail.Meetup -> {
                    val pending = "답장 후"
                    KeyFigure("만나는 시각", d.meetAt?.hhmm() ?: pending, Modifier.widthIn(min = 160.dp))
                    KeyFigure("나", d.userTravelMinutes?.let { "${it}분" } ?: pending, Modifier.widthIn(min = 160.dp))
                    KeyFigure(d.contact.name, d.peerTravelMinutes?.let { "${it}분" } ?: pending, Modifier.widthIn(min = 160.dp))
                }
                is ErrandDetail.MailReply -> {
                    KeyFigure("보낸 사람", d.mail.senderName, Modifier.widthIn(min = 160.dp))
                    KeyFigure("받은 시각", d.mail.receivedAt.hhmm(), Modifier.widthIn(min = 160.dp))
                }
            }
        }
    }
}

data class StoreOption(val store: Store, val readyAt: LocalTime)

@Composable
private fun OtherStores(errand: Errand, mode: InteractionMode, options: List<StoreOption>, onSwitch: (Store) -> Unit) {
    val d = errand.detail as? ErrandDetail.Pickup ?: return
    if (errand.state != ErrandState.AWAITING_APPROVAL || mode != InteractionMode.TOUCH) return
    val others = options.filter { it.store.id != d.store.id }
    if (others.isEmpty()) return
    Spacer(Modifier.height(PcSpacing.l))
    SectionHeader("다른 매장")
    Column(verticalArrangement = Arrangement.spacedBy(PcSpacing.xs)) {
        others.forEach { option ->
            ListRow(
                option.store.displayName,
                supporting = "${option.readyAt.hhmm()} 픽업 · 우회 ${option.store.detourMinutes}분",
                leading = Icons.Rounded.Storefront,
                onClick = { onSwitch(option.store) },
            )
        }
    }
}

@Composable
private fun DiningPlan(errand: Errand) {
    val d = errand.detail as? ErrandDetail.ChargeAndDine ?: return
    Spacer(Modifier.height(PcSpacing.l))
    SectionHeader("충전하는 동안")
    Column(verticalArrangement = Arrangement.spacedBy(PcSpacing.xs)) {
        ListRow(d.charger.name, supporting = "${d.charger.powerKw}kW · 빈자리 ${d.charger.availableStalls}면 · 우회 ${d.charger.detourMinutes}분", leading = Icons.Rounded.EvStation)
        ListRow(d.restaurant.name, supporting = "걸어서 ${d.restaurant.walkMinutes}분 · 대기 ${d.restaurant.waitMinutes}분 · ${d.partySize}명", leading = Icons.Rounded.Restaurant)
    }
}

@Composable
private fun MeetupPlan(errand: Errand) {
    val d = errand.detail as? ErrandDetail.Meetup ?: return
    Spacer(Modifier.height(PcSpacing.l))
    SectionHeader("어떻게 정하나")
    Column(verticalArrangement = Arrangement.spacedBy(PcSpacing.xs)) {
        when (d.mode) {
            MeetupMode.AGENT -> ListRow(d.contact.name, supporting = "${d.contact.name}님 에이전트와 위치·빈 시간을 주고받았어요", leading = Icons.Rounded.SmartToy)
            MeetupMode.SMS -> ListRow(d.contact.name, supporting = "에이전트가 없어 문자로 위치·시간을 물어요 · ${d.contact.phone}", leading = Icons.Rounded.Sms)
        }
        ListRow(
            d.place?.name ?: "답장을 받으면 정해요",
            supporting = "두 사람 중 더 오래 걸리는 쪽이 가장 짧은 곳",
            leading = Icons.Rounded.Place,
        )
    }
}

@Composable
private fun MailDraft(errand: Errand) {
    val d = errand.detail as? ErrandDetail.MailReply ?: return
    val colors = PcTheme.colors
    Spacer(Modifier.height(PcSpacing.l))
    SectionHeader("받은 메일")
    Text(d.mail.subject, style = PcTheme.type.bodyStrong, color = colors.textPrimary)
    Spacer(Modifier.height(PcSpacing.xxs))
    Text(d.mail.summary, style = PcTheme.type.body, color = colors.textSecondary)
    Spacer(Modifier.height(PcSpacing.l))
    SectionHeader("보낼 답장")
    PcCard(Modifier.fillMaxWidth()) {
        Text(d.draft, style = PcTheme.type.bodyLarge, color = colors.textPrimary)
    }
    Spacer(Modifier.height(PcSpacing.xs))
    Text("내가 한 말 · \"${d.instruction}\"", style = PcTheme.type.bodySmall, color = colors.textSecondary)
}

@Composable
private fun Companions(errand: Errand) {
    val d = errand.detail as? ErrandDetail.LateArrival ?: return
    Spacer(Modifier.height(PcSpacing.l))
    SectionHeader("알림 받을 일행")
    if (d.companions.isEmpty()) {
        Text("등록된 일행이 없어 예약 변경만 요청해요.", style = PcTheme.type.body, color = PcTheme.colors.textSecondary)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(PcSpacing.xs)) {
            d.companions.forEach { ListRow(it.name, supporting = it.phone, leading = Icons.Rounded.Person) }
        }
    }
}
