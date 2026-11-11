package ai.pitcrew.app.ui.errand

import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.StepState
import ai.pitcrew.app.platform.InteractionMode
import ai.pitcrew.app.ui.components.ButtonKind
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.components.PcCard
import ai.pitcrew.app.ui.components.Tone
import ai.pitcrew.app.ui.components.color
import ai.pitcrew.app.ui.format.hhmm
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** "다음: 충전, 픽업"처럼 짧게 부르는 이름. */
fun Errand.shortKind(): String = when (detail) {
    is ErrandDetail.LateArrival -> "지각"
    is ErrandDetail.Pickup -> "픽업"
    is ErrandDetail.ChargeAndDine -> "충전"
}

/** 일행 이름. 셋 이상이면 "김민수 외 2명"으로 줄인다. */
fun companionNames(companions: List<Contact>): String = when {
    companions.size <= 2 -> companions.joinToString(", ") { it.name }
    else -> "${companions.first().name} 외 ${companions.size - 1}명"
}

fun Errand.title(): String = when (val d = detail) {
    is ErrandDetail.LateArrival -> "${d.placeName ?: "${d.appointment.hhmm()} 약속"}에 ${d.delayMinutes}분 ${if (d.followUp) "더 " else ""}늦어요"
    is ErrandDetail.Pickup -> "${d.store.name}에서 ${d.item}"
    is ErrandDetail.ChargeAndDine -> "${d.charger.name}에서 충전하고 ${d.restaurant.name} 대기"
}

fun Errand.summary(): String = when (val d = detail) {
    is ErrandDetail.LateArrival -> buildString {
        append("예약을 ${d.proposedTime.hhmm()}에 다시 잡")
        append(if (d.companions.isEmpty()) "아요" else "고 ${companionNames(d.companions)}에게 알려요")
    }
    is ErrandDetail.Pickup -> "${d.readyAt.hhmm()}에 받아요. ${d.store.detourMinutes}분 돌아가요"
    is ErrandDetail.ChargeAndDine -> "${d.chargeMinutes}분 충전하는 동안 ${d.restaurant.name} 자리가 나요"
}

/** 진행 중일 때 지금 하는 단계. 승인 단계는 세지 않는다. */
fun Errand.progressLine(): String? {
    if (undoing) return "되돌리는 중"
    if (state != ErrandState.IN_PROGRESS) return null
    val work = steps.drop(1)
    val index = work.indexOfFirst { it.state == StepState.CURRENT }
    if (index < 0) return null
    return "${work[index].title} 중 (${index + 1}/${work.size})"
}

/** 끝난 작업의 결과 문장. 진행 중이거나 승인 전이면 null. */
fun Errand.outcomeLine(): String? = when (state) {
    ErrandState.COMPLETED -> when (val d = detail) {
        is ErrandDetail.LateArrival -> buildString {
            append("예약을 ${d.proposedTime.hhmm()}에 다시 잡")
            append(if (d.companions.isEmpty()) "았어요" else "고 ${companionNames(d.companions)}에게 알렸어요")
        }
        is ErrandDetail.Pickup -> "${d.readyAt.hhmm()}에 받도록 주문하고 경유지에 넣었어요"
        is ErrandDetail.ChargeAndDine -> "${d.restaurant.name}에 줄을 서 두고 충전소를 경유지에 넣었어요"
    }
    ErrandState.FAILED -> {
        val step = steps.firstOrNull { it.state == StepState.FAILED }?.let { "${it.title}에 실패했어요" } ?: "처리하지 못했어요"
        failureReason?.let { "$step. $it" } ?: step
    }
    ErrandState.DECLINED -> "거절해서 진행하지 않았어요"
    ErrandState.EXPIRED -> "답이 없어 진행하지 않았어요"
    ErrandState.CANCELLED -> {
        // 일부를 못 했으면(정정 안내·경유지 삭제) 한 일만 말하고 못 한 일은 안내 문장으로 붙인다
        val partial = undoNote != null
        val done = when (val d = detail) {
            is ErrandDetail.LateArrival ->
                if (d.companions.isEmpty() || partial) "예약을 원래 시간으로 되돌렸어요" else "예약을 원래 시간으로 되돌리고 일행에게 정정 안내를 보냈어요"
            is ErrandDetail.Pickup -> if (partial) "주문을 취소했어요" else "주문을 취소하고 경유지를 뺐어요"
            is ErrandDetail.ChargeAndDine -> if (partial) "줄서기를 취소했어요" else "줄서기를 취소하고 경유지를 뺐어요"
        }
        undoNote?.let { "$done. $it" } ?: done
    }
    else -> null
}

/** 주행 중 승인 방법 안내. PitCrew가 먼저 물은 일은 Gleo가 아니라 앱이 직접 답을 듣는다. */
fun Errand.voiceApprovalHint(listening: Boolean): String = when {
    origin != ErrandOrigin.SUGGESTED -> "주행 중에는 Gleo에게 \"응\"이라고 답해 승인해요"
    listening -> "듣고 있어요 · \"응\" 또는 \"아니\"라고 답하세요"
    else -> "정차하면 화면에서 승인할 수 있어요"
}

@Composable
fun ErrandCard(
    errand: Errand,
    mode: InteractionMode,
    onOpen: () -> Unit,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    listening: Boolean = false,
) {
    val colors = PcTheme.colors
    PcCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        if (errand.origin == ErrandOrigin.SUGGESTED) {
            Text(SUGGESTED_LABEL, style = PcTheme.type.label, color = colors.textSecondary)
            Spacer(Modifier.height(PcSpacing.s))
        }
        Text(errand.title(), style = PcTheme.type.title, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(PcSpacing.xs))
        val progress = errand.progressLine()
        Text(
            progress ?: errand.summary(),
            style = if (progress != null) PcTheme.type.bodyStrong else PcTheme.type.body,
            color = if (progress != null) colors.textPrimary else colors.textSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (errand.state == ErrandState.AWAITING_APPROVAL) {
            Spacer(Modifier.height(PcSpacing.l))
            ApprovalActions(mode, onApprove, onDecline, voiceHint = errand.voiceApprovalHint(listening))
        }
    }
}

/** PitCrew가 먼저 제안한 일에만 붙이는 출처 꼬리표. 시킨 일은 제목만으로 충분하다. */
const val SUGGESTED_LABEL = "PitCrew 제안"

/** 상세 화면 제목. 누가 꺼낸 일인지보다 지금 어떤 상태인지를 보인다(출처는 카드의 [SUGGESTED_LABEL]). */
fun Errand.screenTitle(): String = when (state) {
    ErrandState.AWAITING_APPROVAL -> "확인할 일"
    ErrandState.IN_PROGRESS -> "처리 중인 일"
    ErrandState.COMPLETED -> "처리한 일"
    ErrandState.FAILED -> "처리하지 못한 일"
    ErrandState.CANCELLED -> "되돌린 일"
    ErrandState.DECLINED -> "거절한 일"
    ErrandState.EXPIRED -> "넘어간 일"
}

/** 주차 중에만 탭으로 승인한다. 주행 중이거나 규제 상태를 모르면 음성 승인 안내만 보인다. */
@Composable
fun ApprovalActions(
    mode: InteractionMode,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    voiceHint: String,
) {
    if (mode == InteractionMode.TOUCH) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
            PcButton("거절", onDecline, kind = ButtonKind.BASIC, size = ButtonSize.LARGE, modifier = Modifier.weight(1f))
            PcButton("승인", onApprove, kind = ButtonKind.FILLED, size = ButtonSize.LARGE, icon = Icons.Rounded.Check, modifier = Modifier.weight(2f))
        }
    } else {
        Row(
            modifier.fillMaxWidth().height(80.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PcSpacing.s),
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = null, tint = PcTheme.colors.attention, modifier = Modifier.size(32.dp))
            Text(voiceHint, style = PcTheme.type.body, color = PcTheme.colors.textSecondary)
        }
    }
}

@Composable
fun StepTimeline(errand: Errand, modifier: Modifier = Modifier) {
    val colors = PcTheme.colors
    Column(modifier) {
        errand.steps.forEachIndexed { index, step ->
            val last = index == errand.steps.lastIndex
            val tone = when (step.state) {
                StepState.DONE -> Tone.ACTIVE
                StepState.CURRENT -> if (errand.state == ErrandState.AWAITING_APPROVAL) Tone.ATTENTION else Tone.POSITIVE
                StepState.PENDING -> Tone.NEUTRAL
                StepState.FAILED -> Tone.NEGATIVE
            }
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(48.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    StepMarker(step.state, tone)
                    if (!last) Box(Modifier.width(2.dp).weight(1f).background(colors.divider))
                }
                Row(
                    Modifier.weight(1f).padding(start = PcSpacing.m, bottom = if (last) 0.dp else PcSpacing.l),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        step.title,
                        style = if (step.state == StepState.CURRENT) PcTheme.type.bodyStrong else PcTheme.type.body,
                        color = if (step.state == StepState.PENDING) colors.textTertiary else colors.textPrimary,
                        modifier = Modifier.weight(1f).padding(top = 2.dp),
                    )
                    step.at?.let { Text(it.hhmm(), style = PcTheme.type.bodySmall, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StepMarker(state: StepState, tone: Tone) {
    val colors = PcTheme.colors
    val c = tone.color(colors)
    Box(Modifier.padding(top = 6.dp).size(32.dp), contentAlignment = Alignment.Center) {
        when (state) {
            StepState.DONE -> Box(Modifier.size(32.dp).background(c, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = colors.background, modifier = Modifier.size(22.dp))
            }
            StepState.FAILED -> Box(Modifier.size(32.dp).background(c, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, null, tint = colors.background, modifier = Modifier.size(22.dp))
            }
            StepState.CURRENT -> Box(Modifier.size(32.dp).border(3.dp, c, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).background(c, CircleShape))
            }
            StepState.PENDING -> Box(Modifier.size(24.dp).border(2.dp, colors.textTertiary, CircleShape))
        }
    }
}
