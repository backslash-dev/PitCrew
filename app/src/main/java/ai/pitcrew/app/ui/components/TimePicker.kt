package ai.pitcrew.app.ui.components

import ai.pitcrew.app.ui.theme.PcShape
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.LocalTime

private val CELL = 96.dp
private val MINUTES = (0..50 step 10).toList()

/**
 * 오전·오후, 시, 분을 한 화면에서 바로 고른다. 분은 예약 단위에 맞춰 10분 간격이다.
 * 고른 값은 바로 반영되고 "확인"으로 닫는다.
 */
@Composable
fun TimePickerDialog(selected: LocalTime, onConfirm: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    var hour by rememberSaveable { mutableIntStateOf(selected.hour) }
    var minute by rememberSaveable { mutableIntStateOf(selected.minute) }
    val colors = PcTheme.colors
    val afternoon = hour >= 12
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(colors.surface, PcShape.large).padding(PcSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PcSpacing.m),
        ) {
            Text(timeLabel(LocalTime.of(hour, minute)), style = PcTheme.type.headline, color = colors.textPrimary)
            SegmentedTabs(
                listOf("오전", "오후"),
                if (afternoon) 1 else 0,
                onSelect = { index -> hour = hour % 12 + if (index == 1) 12 else 0 },
            )
            FieldLabel("시")
            (0 until 2).forEach { row ->
                Row {
                    (1..6).map { row * 6 + it }.forEach { h12 ->
                        val h24 = h12 % 12 + if (afternoon) 12 else 0
                        Choice("$h12", selected = h24 == hour) { hour = h24 }
                    }
                }
            }
            FieldLabel("분")
            Row {
                MINUTES.forEach { m -> Choice("%02d".format(m), selected = m == minute) { minute = m } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
                PcButton("취소", onDismiss, kind = ButtonKind.BASIC, size = ButtonSize.MEDIUM, modifier = Modifier.width(CELL * 2))
                PcButton("확인", { onConfirm(LocalTime.of(hour, minute)) }, kind = ButtonKind.FILLED, size = ButtonSize.MEDIUM, modifier = Modifier.width(CELL * 4 - PcSpacing.m))
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = PcTheme.colors
    Box(Modifier.size(CELL).padding(4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(CELL - 8.dp)
                .semantics { this.selected = selected }
                .pressable(onClick = onClick)
                .background(if (selected) colors.buttonFilled else colors.buttonBasic, PcShape.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = PcTheme.type.bodyStrong, color = if (selected) colors.onButtonFilled else colors.textPrimary)
        }
    }
}

/** "오후 6시 30분"처럼 읽기 쉬운 시각. 정각이면 분을 뺀다. */
fun timeLabel(time: LocalTime): String {
    val period = if (time.hour < 12) "오전" else "오후"
    val h12 = (time.hour % 12).let { if (it == 0) 12 else it }
    return if (time.minute == 0) "$period ${h12}시" else "$period ${h12}시 ${time.minute}분"
}
