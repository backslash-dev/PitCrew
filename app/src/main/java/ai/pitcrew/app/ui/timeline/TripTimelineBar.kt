package ai.pitcrew.app.ui.timeline

import ai.pitcrew.app.ui.components.pressable
import ai.pitcrew.app.ui.format.hhmm
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EvStation
import androidx.compose.material.icons.rounded.LocalCafe
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val DOT = 24.dp
private val CLUSTER_DOT = 40.dp
private val LINE = 5.dp
private val LABEL_ROW = 44.dp

/**
 * 지금(왼쪽)부터 도착(오른쪽)까지 점과 선으로 그린다. 늦는 약속은 주황, 묶음은 개수를 적은 큰 점이고 누르면 아래에 펼친다.
 * [compact]는 주행 화면용으로, 늦는 지점과 묶음 이름표만 남긴다.
 */
@Composable
fun TripTimelineBar(timeline: TripTimeline, modifier: Modifier = Modifier, compact: Boolean = false) {
    val colors = PcTheme.colors
    var expanded by rememberSaveable { mutableStateOf<Int?>(null) }
    val labeled = timeline.marks.withIndex().filter { (_, m) -> !compact || m.cluster || m.lateCount > 0 }
    Column(modifier.fillMaxWidth()) {
        // 이름표는 위·아래로 번갈아 두어 이웃끼리 겹치지 않게 한다
        LabelRow(labeled.filterIndexed { i, _ -> i % 2 == 0 }.map { it.value })
        Box(Modifier.fillMaxWidth().height(CLUSTER_DOT), contentAlignment = Alignment.CenterStart) {
            val solidEnd = if (timeline.zoomed) TripTimeline.ZOOM_SPAN else 1f
            Box(Modifier.fillMaxWidth(solidEnd).height(LINE).background(colors.divider))
            if (timeline.zoomed) DottedTail(TripTimeline.ZOOM_SPAN)
            AtFraction(0f) { Dot(colors.textPrimary, colors.textPrimary) }
            AtFraction(1f) { Dot(colors.background, colors.textPrimary) }
            timeline.marks.forEachIndexed { index, mark ->
                AtFraction(mark.fraction) {
                    MarkDot(mark, onClick = { expanded = if (expanded == index) null else index }.takeIf { mark.cluster })
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(LABEL_ROW), verticalAlignment = Alignment.CenterVertically) {
            Text("지금 ${timeline.now.hhmm()}", style = PcTheme.type.body, color = colors.textSecondary, modifier = Modifier.weight(1f))
            if (timeline.laterCount > 0) {
                Text("이후 ${timeline.laterCount}개", style = PcTheme.type.body, color = colors.textTertiary, modifier = Modifier.padding(end = PcSpacing.l))
            }
            Text("도착 ${timeline.arrival.hhmm()}", style = PcTheme.type.body, color = colors.textSecondary)
        }
        Box(Modifier.fillMaxWidth()) {
            LabelRow(labeled.filterIndexed { i, _ -> i % 2 == 1 }.map { it.value })
        }
        expanded?.let { timeline.marks.getOrNull(it) }?.takeIf { it.cluster }?.let { ClusterList(it) }
    }
}

@Composable
private fun LabelRow(marks: List<Mark>) {
    Box(Modifier.fillMaxWidth().height(LABEL_ROW)) {
        marks.forEach { mark ->
            AtFraction(mark.fraction) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    kindIcon(mark)?.let { Icon(it, contentDescription = null, tint = PcTheme.colors.textSecondary, modifier = Modifier.size(28.dp)) }
                    Text(
                        mark.label(),
                        style = if (mark.lateCount > 0) PcTheme.type.bodyStrong else PcTheme.type.body,
                        color = if (mark.lateCount > 0) PcTheme.colors.attention else PcTheme.colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkDot(mark: Mark, onClick: (() -> Unit)?) {
    val colors = PcTheme.colors
    val late = mark.lateCount > 0
    val fill = if (late) colors.attentionContainer else colors.background
    val ring = if (late) colors.attention else colors.textSecondary
    if (!mark.cluster) {
        Dot(fill, ring)
        return
    }
    Box(
        Modifier.size(CLUSTER_DOT).then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
            .background(fill, CircleShape).border(2.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("${mark.stops.size}", style = PcTheme.type.bodyStrong, color = if (late) colors.attention else colors.textPrimary)
    }
}

@Composable
private fun Dot(fill: Color, ring: Color, size: Dp = DOT) {
    Box(Modifier.size(size).background(fill, CircleShape).border(3.dp, ring, CircleShape))
}

@Composable
private fun DottedTail(from: Float) {
    Layout(content = {
        repeat(8) { Box(Modifier.size(6.dp).background(PcTheme.colors.divider, CircleShape)) }
    }) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeables = measurables.map { it.measure(Constraints()) }
        val height = placeables.maxOf { it.height }
        layout(width, height) {
            val start = width * from
            val step = (width - start) / (placeables.size + 1)
            placeables.forEachIndexed { i, p -> p.place((start + step * (i + 1)).roundToInt() - p.width / 2, 0) }
        }
    }
}

@Composable
private fun ClusterList(mark: Mark) {
    Column(Modifier.fillMaxWidth().padding(top = PcSpacing.xs), verticalArrangement = Arrangement.spacedBy(PcSpacing.xxs)) {
        mark.stops.forEach { stop ->
            Text(
                "${stop.time.hhmm()}  ${stop.name}" + if (stop.late) "  · ${stop.lateMinutes}분 늦음" else "",
                style = PcTheme.type.body,
                color = if (stop.late) PcTheme.colors.attention else PcTheme.colors.textPrimary,
            )
        }
    }
}

private fun kindIcon(mark: Mark) = mark.stops.singleOrNull()?.let {
    when (it.kind) {
        StopKind.PICKUP -> Icons.Rounded.LocalCafe
        StopKind.CHARGE -> Icons.Rounded.EvStation
        StopKind.APPOINTMENT -> null
    }
}

/** 자식의 가운데를 너비의 [fraction] 위치에 두되, 양 끝을 넘지 않게 안쪽으로 당긴다. */
@Composable
private fun AtFraction(fraction: Float, content: @Composable () -> Unit) {
    Layout(content = content) { measurables, constraints ->
        val width = constraints.maxWidth
        val placeable = measurables.single().measure(Constraints())
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else placeable.height
        layout(width, height) {
            val x = (width * fraction - placeable.width / 2f).roundToInt().coerceIn(0, (width - placeable.width).coerceAtLeast(0))
            placeable.place(x, (height - placeable.height) / 2)
        }
    }
}
