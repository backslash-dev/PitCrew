package ai.pitcrew.app.data

import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.ErrandStep
import ai.pitcrew.app.domain.StepState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed interface StepResult {
    data object Done : StepResult
    data class Failed(val reason: String?) : StepResult
}

/** 승인 이후 단계 하나를 실제로 수행한다. [stepIndex]는 승인 단계를 0으로 센 순서다. */
fun interface StepExecutor {
    suspend fun execute(errand: Errand, stepIndex: Int): StepResult
}

/**
 * 작업의 상태 흐름(제안 → 승인 → 단계 진행 → 완료·실패)을 관리한다. 각 단계의 실제 처리는 [executor]가 맡는다.
 * 단계 사이 지연은 화면에서 진행이 보이게 하는 값이며, 전체가 Gleo 응답 예산(5초) 안에 끝나야 한다.
 */
class ErrandRunner(
    private val executor: StepExecutor,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val stepDelayMillis: Long = 0,
) : ErrandService {
    private val _errands = MutableStateFlow<List<Errand>>(emptyList())
    override val errands: StateFlow<List<Errand>> = _errands.asStateFlow()
    private val mutex = Mutex()

    override fun propose(detail: ErrandDetail): Errand {
        val errand = Errand(
            id = UUID.randomUUID().toString(),
            createdAt = now(),
            state = ErrandState.AWAITING_APPROVAL,
            detail = detail,
            steps = listOf(ErrandStep(APPROVAL_STEP, StepState.CURRENT)) + plannedSteps(detail).map { ErrandStep(it, StepState.PENDING) },
        )
        // 같은 대상에 대한 대기 중 제안은 최신 계산으로 대체한다(Gleo 재질의·ETA 변화)
        _errands.update { list -> listOf(errand) + list.filterNot { it.state == ErrandState.AWAITING_APPROVAL && it.sameTarget(detail) } }
        return errand
    }

    override suspend fun approve(id: String, channel: ApprovalChannel): Errand {
        mutex.withLock {
            val current = find(id) ?: throw NoSuchElementException("errand $id")
            if (current.state != ErrandState.AWAITING_APPROVAL) return current
            update(id) { errand ->
                errand.copy(
                    state = ErrandState.IN_PROGRESS,
                    approvedVia = channel,
                    steps = errand.steps.mapIndexed { index, step ->
                        when (index) {
                            0 -> step.copy(title = if (channel == ApprovalChannel.VOICE) "음성으로 승인" else "화면에서 승인", state = StepState.DONE, at = now())
                            1 -> step.copy(state = StepState.CURRENT)
                            else -> step
                        }
                    },
                )
            }
        }
        val errand = find(id)!!
        for (stepIndex in 1 until errand.steps.size) {
            if (stepDelayMillis > 0) delay(stepDelayMillis)
            val result = runCatching { executor.execute(errand, stepIndex) }
                .getOrElse { e -> if (e is CancellationException) throw e else StepResult.Failed(e.message) }
            if (result is StepResult.Failed) {
                update(id) { e ->
                    e.copy(
                        state = ErrandState.FAILED,
                        finishedAt = now(),
                        failureReason = result.reason,
                        steps = e.steps.mapIndexed { i, s -> if (i == stepIndex) s.copy(state = StepState.FAILED, at = now()) else s },
                    )
                }
                return find(id)!!
            }
            update(id) { e ->
                e.copy(steps = e.steps.mapIndexed { i, s ->
                    when (i) {
                        stepIndex -> s.copy(state = StepState.DONE, at = now())
                        stepIndex + 1 -> s.copy(state = StepState.CURRENT)
                        else -> s
                    }
                })
            }
        }
        update(id) { it.copy(state = ErrandState.COMPLETED, finishedAt = now()) }
        return find(id)!!
    }

    override fun decline(id: String) = update(id) { errand ->
        if (errand.state != ErrandState.AWAITING_APPROVAL) errand
        else errand.copy(
            state = ErrandState.DECLINED,
            finishedAt = now(),
            steps = errand.steps.mapIndexed { i, s -> if (i == 0) s.copy(title = "거절함", state = StepState.FAILED, at = now()) else s },
        )
    }

    override fun markArrivalAnnounced(id: String) = update(id) { it.copy(arrivalAnnouncedAt = now()) }

    private fun find(id: String) = _errands.value.firstOrNull { it.id == id }

    private fun update(id: String, transform: (Errand) -> Errand) =
        _errands.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun Errand.sameTarget(other: ErrandDetail): Boolean = when (val mine = detail) {
        is ErrandDetail.LateArrival -> other is ErrandDetail.LateArrival &&
            (mine.reservationId ?: mine.appointment.toString()) == (other.reservationId ?: other.appointment.toString())
        is ErrandDetail.Pickup -> other is ErrandDetail.Pickup
    }

    companion object {
        const val APPROVAL_STEP = "승인 대기"
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        fun plannedSteps(detail: ErrandDetail): List<String> = when (detail) {
            is ErrandDetail.LateArrival -> listOfNotNull(
                "예약 시간 변경 요청",
                "변경 확정 · ${detail.proposedTime.format(HHMM)}",
                if (detail.companions.isEmpty()) null else "일행 ${detail.companions.size}명에게 도착 시간 알림",
            )
            is ErrandDetail.Pickup -> listOf(
                "${detail.item} 주문 접수",
                "픽업 준비 확정 · ${detail.readyAt.format(HHMM)}",
                "경유지 추가 · ${detail.store.displayName}",
            )
        }
    }
}
