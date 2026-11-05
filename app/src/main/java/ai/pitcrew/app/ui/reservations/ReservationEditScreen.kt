package ai.pitcrew.app.ui.reservations

import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.ui.components.ButtonKind
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.FieldLabel
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.components.PcTextField
import ai.pitcrew.app.ui.components.ScreenHeader
import ai.pitcrew.app.ui.components.SelectChip
import ai.pitcrew.app.ui.components.Stepper
import ai.pitcrew.app.ui.format.dayLabel
import ai.pitcrew.app.ui.format.hhmm
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDateTime
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
    val initialTime = reservation?.time ?: now.plusHours(1).truncatedTo(ChronoUnit.HOURS)
    var place by rememberSaveable { mutableStateOf(reservation?.placeName.orEmpty()) }
    var timeText by rememberSaveable { mutableStateOf(initialTime.toString()) }
    var partySize by rememberSaveable { mutableIntStateOf(reservation?.partySize ?: 2) }
    var selected by rememberSaveable { mutableStateOf(reservation?.companionIds.orEmpty()) }
    var showError by rememberSaveable { mutableStateOf(false) }
    val time = LocalDateTime.parse(timeText)

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
                FieldLabel(listOfNotNull("시간 (10분 단위)", dayLabel(time.toLocalDate(), now.toLocalDate())).joinToString(" · "))
                Stepper(
                    time.hhmm(),
                    onDecrease = { timeText = time.minusMinutes(10).toString() },
                    onIncrease = { timeText = time.plusMinutes(10).toString() },
                    decreaseLabel = "−10분",
                    increaseLabel = "+10분",
                )
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
                    if (place.isBlank()) {
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
