package ai.pitcrew.app.ui.reservations

import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.ui.components.pressable
import ai.pitcrew.app.ui.format.dayLabel
import ai.pitcrew.app.ui.format.hhmm
import ai.pitcrew.app.ui.theme.PcShape
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

fun Reservation.companionLine(contacts: List<Contact>): String {
    val names = companionIds.mapNotNull { id -> contacts.firstOrNull { it.id == id }?.name }
    return buildString {
        append("${partySize}명")
        if (names.isNotEmpty()) append(" · 알림 ${names.joinToString(", ")}") else append(" · 알림 받을 일행 없음")
    }
}

@Composable
fun ReservationRow(reservation: Reservation, contacts: List<Contact>, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val colors = PcTheme.colors
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val base = if (onClick != null) modifier.pressable(interactionSource = source, onClick = onClick) else modifier
    Row(
        modifier = base
            .fillMaxWidth()
            .background(if (pressed) colors.surfaceHigh else colors.surface, PcShape.medium)
            .padding(horizontal = PcSpacing.l, vertical = PcSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PcSpacing.l),
    ) {
        Column(Modifier.width(132.dp)) {
            dayLabel(reservation.time.toLocalDate(), LocalDate.now())?.let {
                Text(it, style = PcTheme.type.labelSmall, color = colors.textSecondary)
            }
            Text(reservation.time.hhmm(), style = PcTheme.type.title, color = colors.textPrimary)
        }
        Column(Modifier.weight(1f)) {
            Text(reservation.placeName, style = PcTheme.type.bodyStrong, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reservation.companionLine(contacts), style = PcTheme.type.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
