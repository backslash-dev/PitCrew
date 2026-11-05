package ai.pitcrew.app.ui.driving

import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.errand.LateArrivalPlanner
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.ui.errand.outcomeLine
import ai.pitcrew.app.ui.errand.progressLine
import ai.pitcrew.app.ui.errand.title
import ai.pitcrew.app.ui.errand.voiceApprovalHint
import ai.pitcrew.app.ui.format.hhmm
import ai.pitcrew.app.ui.format.minutesLabel
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import java.time.Duration
import java.time.LocalDateTime

/**
 * 주행 중 화면. 1~2초 안에 읽을 핵심만 두고 조작 요소는 두지 않는다.
 * 움직이는 요소도 없다(주의분산 가이드).
 */
@Composable
fun DrivingScreen(destination: Destination?, errands: List<Errand>, now: LocalDateTime, listening: Boolean) {
    val colors = PcTheme.colors
    val focus = drivingFocus(errands, now)
    Column(
        Modifier.fillMaxSize().background(colors.background).padding(horizontal = 64.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(Modifier.height(PcSpacing.xs))

        Column {
            if (destination != null) {
                Text(LateArrivalPlanner.arrivalAt(now, destination.remainTimeSeconds).hhmm(), style = PcTheme.type.display.copy(fontSize = PcTheme.type.display.fontSize * 1.5f), color = colors.textPrimary)
                Text(
                    "${destination.name} 도착 · ${minutesLabel(destination.remainTimeSeconds / 60L)} 남음",
                    style = PcTheme.type.title,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text("경로 없음", style = PcTheme.type.headline, color = colors.textPrimary)
            }
            if (focus != null) {
                Spacer(Modifier.height(PcSpacing.xxl))
                Text(focus.title(), style = PcTheme.type.titleLarge, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                (focus.progressLine() ?: focus.outcomeLine())?.let {
                    Text(it, style = PcTheme.type.titleSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
            Icon(Icons.Rounded.Mic, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(40.dp))
            Text(
                drivingHint(focus, now, listening),
                style = PcTheme.type.bodyLarge,
                color = colors.textSecondary,
            )
        }
    }
}

internal fun drivingHint(focus: Errand?, now: LocalDateTime, listening: Boolean): String = when {
    focus == null -> "Gleo를 부르고 \"피트크루 앱으로…\"라고 말하세요"
    focus.state == ErrandState.AWAITING_APPROVAL && focus.origin == ErrandOrigin.SUGGESTED -> focus.voiceApprovalHint(listening)
    focus.state == ErrandState.AWAITING_APPROVAL -> "Gleo에게 \"응\" 또는 \"아니\"라고 답하세요"
    focus.canUndo(now) -> "되돌리려면 Gleo에게 \"피트크루 앱으로 방금 거 취소해\""
    else -> "Gleo를 부르고 \"피트크루 앱으로…\"라고 말하세요"
}

/** 승인 대기 > 진행 중 > 10분 안에 끝난 작업 순으로 하나만 보여준다. */
internal fun drivingFocus(errands: List<Errand>, now: LocalDateTime): Errand? =
    errands.firstOrNull { it.state == ErrandState.AWAITING_APPROVAL }
        ?: errands.firstOrNull { it.state == ErrandState.IN_PROGRESS }
        ?: errands.firstOrNull { e -> e.finishedAt?.let { Duration.between(it, now).toMinutes() < 10 } == true }
