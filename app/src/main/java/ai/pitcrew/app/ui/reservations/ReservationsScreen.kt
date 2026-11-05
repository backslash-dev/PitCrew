package ai.pitcrew.app.ui.reservations

import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.ui.components.ButtonKind
import ai.pitcrew.app.ui.components.ButtonSize
import ai.pitcrew.app.ui.components.EmptyState
import ai.pitcrew.app.ui.components.ListRow
import ai.pitcrew.app.ui.components.PcButton
import ai.pitcrew.app.ui.components.ScreenHeader
import ai.pitcrew.app.ui.components.SegmentedTabs
import ai.pitcrew.app.ui.components.TextAction
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import ai.pitcrew.app.ui.theme.PcSpacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.EventAvailable
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun ReservationsScreen(
    reservations: List<Reservation>,
    contacts: List<Contact>,
    syncError: String?,
    onRetry: () -> Unit,
    onOpenReservation: (String) -> Unit,
    onNewReservation: () -> Unit,
    onOpenContact: (String) -> Unit,
    onNewContact: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        ScreenHeader("예약과 일행", actions = {
            PcButton(
                if (tab == 0) "예약 추가" else "일행 추가",
                if (tab == 0) onNewReservation else onNewContact,
                kind = ButtonKind.BASIC,
                size = ButtonSize.MEDIUM,
                icon = Icons.Rounded.Add,
            )
        })
        SegmentedTabs(listOf("예약", "일행"), tab, { tab = it })
        if (syncError != null) {
            Row(Modifier.padding(top = PcSpacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PcSpacing.m)) {
                Text(syncError, style = PcTheme.type.body, color = PcTheme.colors.negative, modifier = Modifier.weight(1f, fill = false))
                TextAction("다시 시도", onRetry)
            }
        }
        Spacer(Modifier.height(PcSpacing.l))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PcSpacing.s)) {
            if (tab == 0) {
                if (reservations.isEmpty()) {
                    item { EmptyState("예약이 없어요", "예약을 추가하면 늦을 때 시간 변경과 일행 알림을 대신 처리해요.", icon = Icons.Rounded.EventAvailable) }
                }
                items(reservations, key = { it.id }) { reservation ->
                    ReservationRow(reservation, contacts, onClick = { onOpenReservation(reservation.id) })
                }
            } else {
                if (contacts.isEmpty()) {
                    item { EmptyState("등록된 일행이 없어요", "늦을 때 도착 시간을 알려줄 사람을 추가하세요.", icon = Icons.Rounded.Group) }
                }
                items(contacts, key = { it.id }) { contact ->
                    ListRow(contact.name, supporting = contact.phone, leading = Icons.Rounded.Person, onClick = { onOpenContact(contact.id) })
                }
            }
            item { Spacer(Modifier.height(PcSpacing.l)) }
        }
    }
}
