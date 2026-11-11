package ai.pitcrew.app.ui.reservations

import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.ui.components.ButtonKind
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.DatePickerDialog
import ai.pitcrew.app.ui.components.FieldLabel
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.components.PcTextField
import ai.pitcrew.app.ui.components.ScreenHeader
import ai.pitcrew.app.ui.components.SelectChip
import ai.pitcrew.app.ui.components.Stepper
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
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
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val MAX_PARTY = 20

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReservationEditScreen(
    reservation: Reservation?,
    contacts: List<Contact>,
    now: LocalDateTime,
    onSave: (Reservation) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
) {
    // 분 선택이 10분 단위라 새 예약도 10분 단위 시각에서 시작한다
    val initialTime = reservation?.time ?: now.plusHours(1).truncatedTo(ChronoUnit.HOURS)
    var place by rememberSaveable { mutableStateOf(reservation?.placeName.orEmpty()) }
    var timeText by rememberSaveable { mutableStateOf(initialTime.toString()) }
    var partySize by rememberSaveable { mutableIntStateOf(reservation?.partySize ?: 2) }
    var selected by rememberSaveable { mutableStateOf(reservation?.companionIds.orEmpty()) }
    var showError by rememberSaveable { mutableStateOf(false) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val time = LocalDateTime.parse(timeText)
    // 새로 잡거나 시각을 바꾼 예약이 이미 지났으면 저장하지 않는다. 기존 예약을 그대로 두고 다른 항목만 고치는 것은 허용한다
    val pastTime = time.isBefore(now.truncatedTo(ChronoUnit.MINUTES)) && time != reservation?.time

    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        ScreenHeader(if (reservation == null) "예약 추가" else "예약 수정", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(PcSpacing.xl)) {
            Column {
                FieldLabel("장소")
                PcTextField(place, { place = it; showError = false }, "식당·카페 이름", Modifier.widthIn(max = 900.dp), isError = showError && place.isBlank())
                if (showError && place.isBlank()) {
                    Text("장소를 입력하세요.", style = PcTheme.type.label, color = PcTheme.colors.negative, modifier = Modifier.padding(top = PcSpacing.xs))
                }
            }
            Column {
                FieldLabel("날짜")
                val date = time.toLocalDate()
                PcButton(
                    dateButtonLabel(date, now.toLocalDate()),
                    { pickingDate = true },
                    kind = ButtonKind.BASIC,
                    size = ButtonSize.MEDIUM,
                    icon = Icons.Rounded.CalendarMonth,
                    modifier = Modifier.widthIn(min = 420.dp),
                )
                if (pickingDate) {
                    DatePickerDialog(
                        selected = date,
                        range = dateRange(now.toLocalDate(), reservation?.time?.toLocalDate()),
                        today = now.toLocalDate(),
                        onSelect = {
                            timeText = it.atTime(time.toLocalTime()).toString()
                            showError = false
                            pickingDate = false
                        },
                        onDismiss = { pickingDate = false },
                    )
                }
            }
            Column {
                FieldLabel("시각")
                // 시와 분을 따로 바꿔 날짜가 의도치 않게 넘어가지 않게 한다
                Row(horizontalArrangement = Arrangement.spacedBy(PcSpacing.xxl), verticalAlignment = Alignment.CenterVertically) {
                    Stepper(
                        "%02d시".format(time.hour),
                        onDecrease = { timeText = time.withHour((time.hour + 23) % 24).toString(); showError = false },
                        onIncrease = { timeText = time.withHour((time.hour + 1) % 24).toString(); showError = false },
                    )
                    Stepper(
                        "%02d분".format(time.minute),
                        onDecrease = { timeText = time.withMinute((time.minute + 50) % 60).toString(); showError = false },
                        onIncrease = { timeText = time.withMinute((time.minute + 10) % 60).toString(); showError = false },
                    )
                }
                if (showError && pastTime) {
                    Text("이미 지난 시각이에요. 날짜나 시각을 바꿔 주세요.", style = PcTheme.type.label, color = PcTheme.colors.negative, modifier = Modifier.padding(top = PcSpacing.xs))
                }
            }
            Column {
                FieldLabel("인원")
                Stepper(
                    "${partySize}명",
                    onDecrease = { partySize = (partySize - 1).coerceAtLeast(1) },
                    onIncrease = { partySize = (partySize + 1).coerceAtMost(MAX_PARTY) },
                )
            }
            Column {
                FieldLabel("늦을 때 알릴 일행")
                if (contacts.isEmpty()) {
                    Text("일행 탭에서 먼저 사람을 추가하세요.", style = PcTheme.type.body, color = PcTheme.colors.textSecondary)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(PcSpacing.s), verticalArrangement = Arrangement.spacedBy(PcSpacing.s)) {
                    contacts.forEach { contact ->
                        SelectChip(contact.name, contact.id in selected, onToggle = {
                            selected = if (contact.id in selected) selected - contact.id else selected + contact.id
                        })
                    }
                }
            }
            Spacer(Modifier.height(PcSpacing.l))
        }
        Row(Modifier.fillMaxWidth().padding(vertical = PcSpacing.l), horizontalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
            if (reservation != null) {
                PcButton("삭제", { onDelete(reservation.id) }, kind = ButtonKind.BASIC, size = ButtonSize.LARGE, modifier = Modifier.weight(1f))
            }
            PcButton(
                "저장",
                {
                    if (place.isBlank() || pastTime) {
                        showError = true
                    } else {
                        onSave(
                            Reservation(
                                id = reservation?.id ?: UUID.randomUUID().toString(),
                                placeName = place.trim(),
                                time = time,
                                partySize = partySize,
                                companionIds = selected.filter { id -> contacts.any { it.id == id } },
                            ),
                        )
                    }
                },
                kind = ButtonKind.FILLED,
                size = ButtonSize.LARGE,
                modifier = Modifier.weight(2f),
            )
        }
    }
}

private const val MAX_DAYS_AHEAD = 60L

/** 오늘부터 두 달까지. 이미 지난 날짜의 기존 예약을 고칠 때는 그 날짜부터 고를 수 있다. */
internal fun dateRange(today: LocalDate, existing: LocalDate?): ClosedRange<LocalDate> =
    minOf(today, existing ?: today)..today.plusDays(MAX_DAYS_AHEAD)

/** "오늘 11월 11일 (수)", "내일 …"처럼 가까운 날은 앞에 붙인다. */
internal fun dateButtonLabel(date: LocalDate, today: LocalDate): String {
    val base = "${date.monthValue}월 ${date.dayOfMonth}일 (${date.format(WEEKDAY)})"
    return when (date) {
        today -> "오늘 $base"
        today.plusDays(1) -> "내일 $base"
        else -> base
    }
}

private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("E", Locale.KOREAN)
