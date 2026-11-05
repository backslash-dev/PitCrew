package ai.pitcrew.app.ui.history

import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.ui.components.EmptyState
import ai.pitcrew.app.ui.components.PcCard
import ai.pitcrew.app.ui.components.ScreenHeader
import ai.pitcrew.app.ui.components.StatusBadge
import ai.pitcrew.app.ui.errand.kindLabel
import ai.pitcrew.app.ui.errand.statusLabel
import ai.pitcrew.app.ui.errand.statusTone
import ai.pitcrew.app.ui.errand.summary
import ai.pitcrew.app.ui.errand.title
import ai.pitcrew.app.ui.format.relativeLabel
import ai.pitcrew.app.ui.theme.PcSpacing
import ai.pitcrew.app.ui.theme.PcTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import java.time.LocalDateTime

@Composable
fun HistoryScreen(errands: List<Errand>, now: LocalDateTime, onOpen: (String) -> Unit) {
    val finished = errands.filterNot { it.isActive }
    Column(Modifier.fillMaxSize().padding(horizontal = PcSpacing.screen)) {
        ScreenHeader("기록")
        if (finished.isEmpty()) {
            EmptyState("아직 기록이 없어요", "처리한 지각 대응과 픽업 주문이 여기에 남아요.", icon = Icons.Rounded.History)
            return@Column
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PcSpacing.s)) {
            items(finished, key = { it.id }) { errand ->
                PcCard(Modifier.fillMaxWidth(), onClick = { onOpen(errand.id) }, padding = PcSpacing.l) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${errand.kindLabel()} · ${relativeLabel(errand.finishedAt ?: errand.createdAt, now)}",
                            style = PcTheme.type.label,
                            color = PcTheme.colors.textSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        StatusBadge(errand.statusLabel(), errand.statusTone())
                    }
                    Spacer(Modifier.height(PcSpacing.xs))
                    Text(errand.title(), style = PcTheme.type.titleSmall, color = PcTheme.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(errand.summary(), style = PcTheme.type.bodySmall, color = PcTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            item { Spacer(Modifier.height(PcSpacing.l)) }
        }
    }
}
