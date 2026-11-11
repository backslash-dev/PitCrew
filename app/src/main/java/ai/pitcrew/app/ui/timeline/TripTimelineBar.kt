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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private val DOT = 16.dp
private val END_DOT = 20.dp
private val TRACK = 40.dp
private val LINE = 4.dp

/** [compact]는 주행 화면용으로, 시각은 화면이 따로 크게 보이므로 선과 문장만 남긴다. */
@Composable
fun TripTimelineBar(
    timeline: TripTimeline,
    modifier: Modifier = Modifier,
    destinationName: String? = null,
    meta: String? = null,
    compact: Boolean = false,
) {
    val colors = PcTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        if (!compact) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                EndTime("지금", timeline.now.hhmm(), Alignment.Start, Modifier.weight(1f))
                EndTime(destinationName?.let { "$it 도착" } ?: "도착", timeline.arrival.hhmm(), Alignment.End)
            }
        }
        Track(timeline)
        val headline = timeline.headline()
        val expandable = timeline.stops.size > 1 || timeline.laterCount > 0
        if (headline != null || meta != null) {
            Row(
                Modifier.fillMaxWidth().then(if (expandable && !compact) Modifier.pressable(onClick = { expanded = !expanded }) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    headline?.text.orEmpty(),
                    style = if (headline?.late == true) PcTheme.type.bodyStrong else PcTheme.type.body,
                    color = if (headline?.late == true) colors.negative else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (expandable && !compact) {
                    Icon(
                        if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "지점 목록 접기" else "지점 목록 펼치기",
                        tint = colors.textSecondary,
                        modifier = Modifier.size(32.dp),
                    )
                }
                meta?.let { Text(it, style = PcTheme.type.body, color = colors.textSecondary, maxLines = 1, modifier = Modifier.padding(start = PcSpacing.l)) }
            }
        }
        if (expanded && !compact) StopList(timeline)
    }
}

@Composable
private fun EndTime(caption: String, time: String, align: Alignment.Horizontal, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = align) {
        Text(caption, style = PcTheme.type.body, color = PcTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(time, style = PcTheme.type.headline, color = PcTheme.colors.textPrimary, maxLines = 1)
    }
}

@Composable
private fun Track(timeline: TripTimeline) {
    val colors = PcTheme.colors
    Box(Modifier.fillMaxWidth().height(TRACK), contentAlignment = Alignment.CenterStart) {
        val solidEnd = if (timeline.zoomed) TripTimeline.ZOOM_SPAN else 1f
        Box(Modifier.fillMaxWidth(solidEnd).height(LINE).background(colors.divider, CircleShape))
        if (timeline.zoomed) DottedTail(TripTimeline.ZOOM_SPAN)
        AtFraction(0f) { Box(Modifier.size(END_DOT).background(colors.textPrimary, CircleShape)) }
        AtFraction(1f) {
            Box(Modifier.size(END_DOT).background(colors.background, CircleShape).border(4.dp, colors.textPrimary, CircleShape))
        }
        timeline.marks.forEach { mark ->
            AtFraction(mark.fraction) {
                Box(Modifier.size(DOT).background(if (mark.lateCount > 0) colors.negative else colors.textSecondary, CircleShape))
            }
        }
    }
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
private fun StopList(timeline: TripTimeline) {
    val colors = PcTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = PcSpacing.s), verticalArrangement = Arrangement.spacedBy(PcSpacing.xs)) {
        timeline.stops.forEach { stop ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stop.time.hhmm(), style = PcTheme.type.body, color = if (stop.late) colors.negative else colors.textSecondary, modifier = Modifier.width(96.dp))
                Text(stop.listName(), style = PcTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
                if (stop.late) Text("${stop.lateMinutes}분 늦음", style = PcTheme.type.bodyStrong, color = colors.negative)
            }
        }
        if (timeline.laterCount > 0) {
            Text("도착 뒤 ${timeline.laterCount}곳 더", style = PcTheme.type.body, color = colors.textTertiary)
        }
    }
}

private fun Stop.listName(): String = when (kind) {
    StopKind.APPOINTMENT -> name
    StopKind.PICKUP -> "$name 픽업"
    StopKind.CHARGE -> "$name 충전"
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
