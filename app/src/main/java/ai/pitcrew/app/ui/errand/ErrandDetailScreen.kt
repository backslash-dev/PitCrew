package ai.pitcrew.app.ui.errand

import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Store
import ai.pitcrew.app.platform.InteractionMode
import ai.pitcrew.app.ui.components.EmptyState
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
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalTime

@Composable
fun ErrandDetailScreen(
    errand: Errand?,
    mode: InteractionMode,
    width: WindowWidth,
    onBack: () -> Unit,
    onApprove: (String, ApprovalChannel) -> Unit,
    onDecline: (String) -> Unit,
    storeOptions: List<StoreOption> = emptyList(),
    onSwitchStore: (Store) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        ScreenHeader(errand?.kindLabel() ?: "작업", onBack = onBack)
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
                    errand.outcomeLine()?.let {
                        Spacer(Modifier.height(PcSpacing.l))
                        Text(
                            it,
                            style = PcTheme.type.bodyStrong,
                            color = if (errand.state == ErrandState.FAILED) PcTheme.colors.negative else PcTheme.colors.textPrimary,
                        )
                    }
                }
            }
            if (width == WindowWidth.COMPACT) {
                figures()
                Spacer(Modifier.height(PcSpacing.l))
                timeline()
                Companions(errand)
                OtherStores(errand, mode, storeOptions, onSwitchStore)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(PcSpacing.l)) {
                    Column(Modifier.weight(1f)) {
                        figures()
                        Companions(errand)
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

/** 상세 화면에서 다른 매장으로 바꿀 때 보여줄 후보. 픽업 시각은 화면에서 미리 계산해 넘긴다. */
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
