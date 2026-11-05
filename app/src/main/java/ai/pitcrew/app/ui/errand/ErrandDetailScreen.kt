package ai.pitcrew.app.ui.errand

import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.platform.InteractionMode
import ai.pitcrew.app.ui.components.EmptyState
import ai.pitcrew.app.ui.components.Hairline
import ai.pitcrew.app.ui.components.KeyFigure
import ai.pitcrew.app.ui.components.ListRow
import ai.pitcrew.app.ui.components.PcCard
import ai.pitcrew.app.ui.components.ScreenHeader
import ai.pitcrew.app.ui.components.SectionHeader
import ai.pitcrew.app.ui.components.StatusBadge
import ai.pitcrew.app.ui.components.Tone
import ai.pitcrew.app.ui.format.hhmm
import ai.pitcrew.app.ui.format.minutesLabel
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import ai.pitcrew.app.ui.theme.WindowWidth
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
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ErrandDetailScreen(
    errand: Errand?,
    mode: InteractionMode,
    width: WindowWidth,
    onBack: () -> Unit,
    onApprove: (String, ApprovalChannel) -> Unit,
    onDecline: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        ScreenHeader(errand?.kindLabel() ?: "작업", onBack = onBack, actions = {
            errand?.let { StatusBadge(it.statusLabel(), it.statusTone()) }
        })
        if (errand == null) {
            EmptyState("작업을 찾을 수 없어요", "이미 정리되었을 수 있어요.", icon = Icons.Rounded.SearchOff)
            return@Column
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = PcSpacing.xl)) {
            Text(errand.title(), style = PcTheme.type.headline, color = PcTheme.colors.textPrimary)
            Spacer(Modifier.height(PcSpacing.xs))
            Text(errand.summary(), style = PcTheme.type.bodyLarge, color = PcTheme.colors.textSecondary)
            Spacer(Modifier.height(PcSpacing.xl))

            val figures: @Composable () -> Unit = { Figures(errand) }
            val timeline: @Composable () -> Unit = {
                PcCard(Modifier.fillMaxWidth()) {
                    SectionHeader("진행")
                    Spacer(Modifier.height(PcSpacing.s))
                    StepTimeline(errand)
                    errand.failureReason?.let {
                        Spacer(Modifier.height(PcSpacing.m))
                        Text(it, style = PcTheme.type.body, color = PcTheme.colors.negative)
                    }
                }
            }
            if (width == WindowWidth.COMPACT) {
                figures()
                Spacer(Modifier.height(PcSpacing.l))
                timeline()
                Companions(errand)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(PcSpacing.l)) {
                    Column(Modifier.weight(1f)) {
                        figures()
                        Companions(errand)
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
            )
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
                    KeyFigure("늦는 시간", minutesLabel(d.delayMinutes), Modifier.widthIn(min = 160.dp), tone = Tone.ATTENTION)
                    KeyFigure("변경할 시간", d.proposedTime.hhmm(), Modifier.widthIn(min = 160.dp))
                }
                is ErrandDetail.Pickup -> {
                    KeyFigure("픽업", d.readyAt.hhmm(), Modifier.widthIn(min = 160.dp))
                    KeyFigure("우회", "+${d.store.detourMinutes}분", Modifier.widthIn(min = 160.dp))
                    KeyFigure("조리", "${d.store.prepMinutes}분", Modifier.widthIn(min = 160.dp))
                }
            }
        }
    }
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
