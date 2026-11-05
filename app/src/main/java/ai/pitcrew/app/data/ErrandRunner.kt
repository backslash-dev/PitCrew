package ai.pitcrew.app.data

import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
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
    /** 성공했고, 되돌릴 때 쓸 서버 쪽 식별자를 남긴다. */
    data class Recorded(val reference: String) : StepResult
    data class Failed(val reason: String?) : StepResult
}

sealed interface UndoResult {
    /** [note]는 되돌렸지만 일부를 못 했을 때 사용자에게 알릴 문장. */
    data class Undone(val note: String? = null) : UndoResult
    data class Failed(val reason: String) : UndoResult
}

/** 승인 이후 단계 하나를 실제로 수행한다. [stepIndex]는 승인 단계를 0으로 센 순서다. */
fun interface StepExecutor {
    suspend fun execute(errand: Errand, stepIndex: Int): StepResult

    suspend fun undo(errand: Errand): UndoResult = UndoResult.Failed("되돌릴 수 없는 작업이에요")
}

/**
 * 작업의 상태 흐름(제안 → 승인 → 단계 진행 → 완료·실패 → 되돌리기)을 관리한다. 각 단계의 실제 처리는 [executor]가 맡는다.
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

    override fun propose(detail: ErrandDetail, origin: ErrandOrigin): Errand {
        val errand = Errand(
            id = UUID.randomUUID().toString(),
            createdAt = now(),
            state = ErrandState.AWAITING_APPROVAL,
            detail = detail,
            steps = listOf(ErrandStep(APPROVAL_STEP, StepState.CURRENT)) + plannedSteps(detail).map { ErrandStep(it, StepState.PENDING) },
            origin = origin,
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
        for (stepIndex in 1 until find(id)!!.steps.size) {
            if (stepDelayMillis > 0) delay(stepDelayMillis)
            // 앞 단계가 남긴 식별자를 다음 단계가 볼 수 있게 매번 최신 상태로 넘긴다
            val result = runCatching { executor.execute(find(id)!!, stepIndex) }
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
                e.copy(
                    reference = (result as? StepResult.Recorded)?.reference ?: e.reference,
                    steps = e.steps.mapIndexed { i, s ->
                        when (i) {
                            stepIndex -> s.copy(state = StepState.DONE, at = now())
                            stepIndex + 1 -> s.copy(state = StepState.CURRENT)
                            else -> s
                        }
                    },
                )
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

    override suspend fun undo(id: String, channel: ApprovalChannel): Errand {
        mutex.withLock {
            val current = find(id) ?: throw NoSuchElementException("errand $id")
            if (!current.canUndo(now())) {
                // 화면 시계가 늦어 버튼이 남아 있던 경우에도 왜 안 되는지 보이게 한다
                if (current.state == ErrandState.COMPLETED && !current.undoing) {
                    update(id) { it.copy(undoNote = "끝낸 지 ${Errand.UNDO_WINDOW.toMinutes()}분이 지나 되돌릴 수 없어요") }
                }
                return find(id)!!
            }
            update(id) { it.copy(undoing = true, undoNote = null) }
        }
        val result = runCatching { executor.undo(find(id)!!) }
            .getOrElse { e -> if (e is CancellationException) throw e else UndoResult.Failed(e.message ?: "되돌리지 못했어요") }
        update(id) { e ->
            when (result) {
                is UndoResult.Undone -> e.copy(
                    state = ErrandState.CANCELLED,
                    undoing = false,
                    undoneVia = channel,
                    undoNote = result.note,
                    finishedAt = now(),
                    steps = e.steps + ErrandStep(UNDO_STEP, StepState.DONE, now()),
                )
                is UndoResult.Failed -> e.copy(undoing = false, undoNote = result.reason)
            }
        }
        return find(id)!!
    }

    private fun find(id: String) = _errands.value.firstOrNull { it.id == id }

    private fun update(id: String, transform: (Errand) -> Errand) =
        _errands.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun Errand.sameTarget(other: ErrandDetail): Boolean = when (val mine = detail) {
        is ErrandDetail.LateArrival -> other is ErrandDetail.LateArrival &&
            (mine.reservationId ?: mine.appointment.toString()) == (other.reservationId ?: other.appointment.toString())
        is ErrandDetail.Pickup -> other is ErrandDetail.Pickup
        is ErrandDetail.ChargeAndDine -> other is ErrandDetail.ChargeAndDine
    }

    companion object {
        const val APPROVAL_STEP = "승인 대기"
        const val UNDO_STEP = "되돌림"
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
            is ErrandDetail.ChargeAndDine -> listOf(
                "줄서기 등록 · ${detail.restaurant.name}",
                "경유지 추가 · ${detail.charger.name}",
            )
        }
    }
}
