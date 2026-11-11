package ai.pitcrew.app.data

import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.ErrandStep
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.StepState
import ai.pitcrew.app.domain.needsTouchApproval
import kotlinx.coroutines.CancellationException
import ai.pitcrew.app.data.local.ErrandStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

sealed interface StepResult {
    data object Done : StepResult
    data class Recorded(val reference: String) : StepResult
    /** 실행 중 정해진 내용(예: 문자 답장으로 정한 약속 장소)으로 작업을 바꾼다. */
    data class Planned(val detail: ErrandDetail) : StepResult
    /** [reference]는 서버 처리 뒤 실패한 경우(예: 주문 후 경유지 실패) 되돌리기용이다. */
    data class Failed(val reason: String?, val reference: String? = null) : StepResult
}

sealed interface UndoResult {
    data class Undone(val note: String? = null) : UndoResult
    data class Failed(val reason: String) : UndoResult
}

/** [stepIndex]는 승인 단계를 0으로 센다. */
fun interface StepExecutor {
    suspend fun execute(errand: Errand, stepIndex: Int): StepResult

    suspend fun undo(errand: Errand): UndoResult = UndoResult.Failed("되돌릴 수 없는 작업이에요")
}

/** [workScope]에서 실행해 기다리던 쪽(Gleo 5초 예산)이 취소돼도 서버 요청과 화면 상태를 맞춘다. */
class ErrandRunner(
    private val executor: StepExecutor,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val stepDelayMillis: Long = 0,
    private val workScope: CoroutineScope? = null,
    private val store: ErrandStore? = null,
) : ErrandService {
    private val _errands = MutableStateFlow(store?.load()?.let(::recover).orEmpty())
    override val errands: StateFlow<List<Errand>> = _errands.asStateFlow()
    private val mutex = Mutex()

    init {
        // 저장은 한 곳에서 순서대로 한다. StateFlow라 중간 상태는 건너뛴다
        if (store != null && workScope != null) {
            workScope.launch(Dispatchers.IO) { _errands.drop(1).collect { store.save(it.take(MAX_KEPT)) } }
        }
    }

    /** 실행 도중 종료된 작업은 결과를 모르므로, 서버 기록이 있으면 되돌릴 수 있게 남긴다. */
    private fun recover(saved: List<Errand>): List<Errand> {
        val at = now()
        return saved.filter { Duration.between(it.createdAt, at) <= KEEP_FOR }.take(MAX_KEPT).map { e ->
            when {
                e.state == ErrandState.IN_PROGRESS -> e.copy(
                    state = ErrandState.FAILED,
                    finishedAt = at,
                    failureReason = "앱이 다시 시작돼 결과를 확인하지 못했어요",
                    steps = e.steps.map { if (it.state == StepState.CURRENT) it.copy(state = StepState.FAILED, at = at) else it },
                )
                e.undoing -> e.copy(undoing = false, undoNote = "앱이 다시 시작돼 되돌리기 결과를 확인하지 못했어요")
                else -> e
            }
        }
    }

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
            // 메일처럼 화면에서 내용을 봐야 하는 일은 음성 승인(Gleo·먼저 묻기)을 받지 않는다
            if (channel == ApprovalChannel.VOICE && current.detail.needsTouchApproval) return current
            // 오래된 제안을 승인하면 지난 픽업 시각이 다음 날로 넘어가는 등 엉뚱하게 실행되므로 만료시킨다
            if (current.isStale(now())) {
                expire(id)
                return find(id)!!
            }
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
        return detached { runSteps(id) }
    }

    private suspend fun runSteps(id: String): Errand {
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
                        reference = result.reference ?: e.reference,
                        steps = e.steps.mapIndexed { i, s -> if (i == stepIndex) s.copy(state = StepState.FAILED, at = now()) else s },
                    )
                }
                return find(id)!!
            }
            update(id) { e ->
                val detail = (result as? StepResult.Planned)?.detail ?: e.detail
                val titles = if (detail === e.detail) null else plannedSteps(detail)
                e.copy(
                    detail = detail,
                    reference = (result as? StepResult.Recorded)?.reference ?: e.reference,
                    steps = e.steps.mapIndexed { i, step ->
                        val s = if (titles != null && i > stepIndex) step.copy(title = titles.getOrElse(i - 1) { step.title }) else step
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
                if (current.undoable && !current.undoing) {
                    update(id) { it.copy(undoNote = "끝낸 지 ${Errand.UNDO_WINDOW.toMinutes()}분이 지나 되돌릴 수 없어요") }
                }
                return find(id)!!
            }
            update(id) { it.copy(undoing = true, undoNote = null) }
        }
        return detached { runUndo(id, channel) }
    }

    private suspend fun runUndo(id: String, channel: ApprovalChannel): Errand {
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

    override fun expireStale() {
        val at = now()
        _errands.value.filter { it.state == ErrandState.AWAITING_APPROVAL && it.isStale(at) }.forEach { expire(it.id) }
    }

    private fun expire(id: String) = update(id) { errand ->
        if (errand.state != ErrandState.AWAITING_APPROVAL) errand
        else errand.copy(
            state = ErrandState.EXPIRED,
            finishedAt = now(),
            steps = errand.steps.mapIndexed { i, s -> if (i == 0) s.copy(title = "응답이 없어 넘어감", state = StepState.FAILED, at = now()) else s },
        )
    }

    private suspend fun detached(block: suspend () -> Errand): Errand =
        workScope?.async(NonCancellable) { block() }?.await() ?: block()

    private fun find(id: String) = _errands.value.firstOrNull { it.id == id }

    private fun update(id: String, transform: (Errand) -> Errand) =
        _errands.update { list -> list.map { if (it.id == id) transform(it) else it } }

    private fun Errand.sameTarget(other: ErrandDetail): Boolean = when (val mine = detail) {
        is ErrandDetail.LateArrival -> other is ErrandDetail.LateArrival &&
            (mine.reservationId ?: mine.appointment.toString()) == (other.reservationId ?: other.appointment.toString())
        is ErrandDetail.Pickup -> other is ErrandDetail.Pickup
        is ErrandDetail.ChargeAndDine -> other is ErrandDetail.ChargeAndDine
        is ErrandDetail.Meetup -> other is ErrandDetail.Meetup && other.contact.id == mine.contact.id
        is ErrandDetail.MailReply -> other is ErrandDetail.MailReply && other.mail.id == mine.mail.id
    }

    companion object {
        const val APPROVAL_STEP = "승인 대기"
        const val UNDO_STEP = "되돌림"
        private const val MAX_KEPT = 100
        private val KEEP_FOR: Duration = Duration.ofDays(14)
        private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

        fun plannedSteps(detail: ErrandDetail): List<String> = when (detail) {
            is ErrandDetail.LateArrival -> listOfNotNull(
                "식당에 시간 변경 요청",
                "${detail.proposedTime.format(HHMM)}에 확정",
                if (detail.companions.isEmpty()) null else "일행 ${detail.companions.size}명에게 알림",
            )
            is ErrandDetail.Pickup -> listOf(
                "${detail.item} 주문",
                "${detail.readyAt.format(HHMM)} 픽업 준비 확인",
                "${detail.store.displayName} 경유지 추가",
            )
            is ErrandDetail.ChargeAndDine -> listOf(
                "${detail.restaurant.name} 줄서기",
                "${detail.charger.name} 경유지 추가",
            )
            is ErrandDetail.Meetup -> {
                val name = detail.contact.name
                val booking = if (detail.place != null && detail.meetAt != null) "${detail.place.name} ${detail.meetAt.format(HHMM)} 예약" else "중간 장소 예약"
                when (detail.mode) {
                    MeetupMode.AGENT -> listOf(booking, "${name}님 에이전트에 확정 전달")
                    MeetupMode.SMS -> listOf("${name}님께 문자로 묻고 장소 정하기", booking, "${name}님께 장소·시간 문자")
                }
            }
            is ErrandDetail.MailReply -> listOf("${detail.mail.senderName}님께 답장 보내기")
        }
    }
}
