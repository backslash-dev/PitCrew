package ai.pitcrew.app.ui.components

import ai.pitcrew.app.ui.theme.PcShape
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

private val WEEKDAYS = listOf("일", "월", "화", "수", "목", "금", "토")
private val CELL = 80.dp

/** 한 달 달력에서 날짜를 고른다. [range] 밖의 날짜는 고를 수 없다. 고르면 바로 닫는다(차량 입력 최소화). */
@Composable
fun DatePickerDialog(
    selected: LocalDate,
    range: ClosedRange<LocalDate>,
    today: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(selected).toString()) }
    val month = YearMonth.parse(monthText)
    val colors = PcTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.background(colors.surface, PcShape.large).padding(PcSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(PcSpacing.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonthArrow(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "이전 달", enabled = month > YearMonth.from(range.start)) {
                    monthText = month.minusMonths(1).toString()
                }
                Text(
                    "${month.year}년 ${month.monthValue}월",
                    style = PcTheme.type.title,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(CELL * 5),
                )
                MonthArrow(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "다음 달", enabled = month < YearMonth.from(range.endInclusive)) {
                    monthText = month.plusMonths(1).toString()
                }
            }
            Row {
                WEEKDAYS.forEach {
                    Text(it, style = PcTheme.type.label, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.width(CELL))
                }
            }
            monthWeeks(month).forEach { week ->
                Row {
                    week.forEach { date ->
                        if (date == null) {
                            Spacer(Modifier.size(CELL))
                        } else {
                            DayCell(date, selected = date == selected, today = date == today, enabled = date in range, onClick = { onSelect(date) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(PcSpacing.xs))
            PcButton("닫기", onDismiss, kind = ButtonKind.BASIC, size = ButtonSize.MEDIUM, modifier = Modifier.width(CELL * 7))
        }
    }
}

@Composable
private fun MonthArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(CELL).pressable(enabled = enabled, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) PcTheme.colors.textPrimary else PcTheme.colors.textTertiary, modifier = Modifier.size(40.dp))
    }
}

@Composable
private fun DayCell(date: LocalDate, selected: Boolean, today: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = PcTheme.colors
    Box(Modifier.size(CELL).padding(4.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(CELL - 8.dp)
                .semantics { this.selected = selected }
                .pressable(enabled = enabled, role = Role.Button, onClick = onClick)
                .background(if (selected) colors.buttonFilled else colors.surface, PcShape.small),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                date.dayOfMonth.toString(),
                style = if (today) PcTheme.type.bodyStrong.copy(fontWeight = FontWeight.Bold) else PcTheme.type.body,
                color = when {
                    selected -> colors.onButtonFilled
                    !enabled -> colors.textTertiary
                    else -> colors.textPrimary
                },
            )
        }
    }
}

/** 일요일부터 시작하는 주 단위 칸. 이번 달이 아닌 칸은 null. */
internal fun monthWeeks(month: YearMonth): List<List<LocalDate?>> {
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value % DayOfWeek.entries.size
    val days: List<LocalDate?> = List(leading) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
    return days.chunked(7).map { week -> week + List(7 - week.size) { null } }
}
