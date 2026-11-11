package ai.pitcrew.app.ui.components

import ai.pitcrew.app.ui.theme.PcShape
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import java.time.LocalTime

private val ITEM_HEIGHT = 80.dp
private const val VISIBLE_ITEMS = 5
private const val MINUTE_STEP = 5
private val PERIODS = listOf("오전", "오후")
// 시계처럼 12부터 시작해 오전 12시(자정)·오후 12시(정오)가 각 구간 맨 앞에 오게 한다
private val HOURS = listOf("12") + (1..11).map { "$it" }
private val MINUTES = (0 until 60 step MINUTE_STEP).map { "%02d".format(it) }

/** "확인"을 눌러야 반영한다. */
@Composable
fun TimePickerDialog(selected: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val start = roundDownToStep(selected)
    var period by rememberSaveable { mutableIntStateOf(if (start.hour < 12) 0 else 1) }
    var hourIndex by rememberSaveable { mutableIntStateOf(start.hour % 12) }
    var minuteIndex by rememberSaveable { mutableIntStateOf(start.minute / MINUTE_STEP) }
    val chosen = toTime(period, hourIndex, minuteIndex)
    val colors = PcTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(colors.surface, PcShape.large).padding(PcSpacing.xl).width(560.dp),
            verticalArrangement = Arrangement.spacedBy(PcSpacing.l),
        ) {
            Text(timeLabel(chosen), style = PcTheme.type.headline, color = colors.textPrimary)
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(ITEM_HEIGHT).background(colors.buttonBasic, PcShape.small))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    Wheel(PERIODS, period, { period = it }, width = 140.dp)
                    Wheel(HOURS, hourIndex, { hourIndex = it }, width = 140.dp)
                    Wheel(MINUTES, minuteIndex, { minuteIndex = it }, width = 140.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
                PcButton("취소", onDismiss, kind = ButtonKind.BASIC, size = ButtonSize.MEDIUM, modifier = Modifier.weight(1f))
                PcButton("확인", { onConfirm(chosen) }, kind = ButtonKind.FILLED, size = ButtonSize.MEDIUM, modifier = Modifier.weight(2f))
            }
        }
    }
}

@Composable
fun Wheel(items: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit, width: Dp) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val scope = rememberCoroutineScope()
    val itemPx = with(androidx.compose.ui.platform.LocalDensity.current) { ITEM_HEIGHT.toPx() }
    val center by remember {
        derivedStateOf {
            (state.firstVisibleItemIndex + if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0).coerceIn(0, items.lastIndex)
        }
    }
    LaunchedEffect(state.isScrollInProgress) {
        if (!state.isScrollInProgress && center != selectedIndex) onSelect(center)
    }
    val colors = PcTheme.colors
    LazyColumn(
        state = state,
        flingBehavior = rememberSnapFlingBehavior(state),
        // 처음·끝 값도 가운데 줄에 올 수 있게 위아래로 두 칸씩 비운다
        contentPadding = PaddingValues(vertical = ITEM_HEIGHT * (VISIBLE_ITEMS / 2)),
        modifier = Modifier.width(width).height(ITEM_HEIGHT * VISIBLE_ITEMS),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        itemsIndexed(items) { index, label ->
            Box(
                Modifier.height(ITEM_HEIGHT).fillMaxWidth().pressable(onClick = { scope.launch { state.animateScrollToItem(index) } }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = if (index == center) PcTheme.type.title else PcTheme.type.bodyLarge,
                    color = if (index == center) colors.textPrimary else colors.textTertiary,
                )
            }
        }
    }
}

internal fun roundDownToStep(time: LocalTime): LocalTime = time.withMinute(time.minute / MINUTE_STEP * MINUTE_STEP).withSecond(0).withNano(0)

internal fun toTime(period: Int, hourIndex: Int, minuteIndex: Int): LocalTime =
    LocalTime.of(hourIndex + if (period == 1) 12 else 0, minuteIndex * MINUTE_STEP)

fun timeLabel(time: LocalTime): String {
    val period = if (time.hour < 12) "오전" else "오후"
    val h12 = (time.hour % 12).let { if (it == 0) 12 else it }
    return if (time.minute == 0) "$period ${h12}시" else "$period ${h12}시 ${time.minute}분"
}
