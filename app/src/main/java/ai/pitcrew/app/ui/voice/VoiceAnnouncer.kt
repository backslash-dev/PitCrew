package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.errand.PickupArrival
import ai.pitcrew.app.platform.NavigationClient
import ai.pitcrew.app.platform.Speaker
import ai.pitcrew.app.ui.errand.outcomeLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Gleo로 시킨 일은 Gleo가 결과를 직접 말하므로 다시 말하지 않는다. */
class VoiceAnnouncer(
    private val errands: ErrandService,
    private val navigation: NavigationClient,
    private val voice: Speaker,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private val spokenOutcomes = mutableSetOf<String>()

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            launch { pollWaypointsWhileWaiting() }
            combine(errands.errands, navigation.waypoints) { list, waypoints -> list to waypoints }
                .collect { (list, waypoints) ->
                    list.filter { it.shouldAnnounceOutcome() && spokenOutcomes.add("${it.id}/${it.state}") }
                        .forEach { errand -> errand.outcomeLine()?.let(voice::speak) }
                    PickupArrival.due(list, waypoints).forEach { errand ->
                        errands.markArrivalAnnounced(errand.id)
                        voice.speak(PickupArrival.message(errand))
                    }
                }
        }
    }

    // 경유지 남은 시간은 요청해야 갱신되므로, 안내할 픽업이 있을 때만 주기적으로 묻는다
    private suspend fun CoroutineScope.pollWaypointsWhileWaiting() {
        while (isActive) {
            if (PickupArrival.waiting(errands.errands.value).isNotEmpty()) navigation.refreshWaypoints()
            delay(POLL_MILLIS)
        }
    }

    private fun Errand.shouldAnnounceOutcome(): Boolean = when (state) {
        ErrandState.COMPLETED, ErrandState.FAILED -> approvedVia == ApprovalChannel.TOUCH || (approvedVia != null && origin == ErrandOrigin.SUGGESTED)
        ErrandState.CANCELLED -> undoneVia == ApprovalChannel.TOUCH
        else -> false
    }

    private companion object {
        const val POLL_MILLIS = 20_000L
    }
}
