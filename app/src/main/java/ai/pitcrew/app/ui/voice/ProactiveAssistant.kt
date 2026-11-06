package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.OrderHistory
import ai.pitcrew.app.data.ReservationRepository
import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.errand.ChargeAndDineUseCase
import ai.pitcrew.app.errand.ChargeOutcome
import ai.pitcrew.app.errand.ChargePlanner
import ai.pitcrew.app.errand.LateArrivalUseCase
import ai.pitcrew.app.errand.LateWatch
import ai.pitcrew.app.errand.PickupOutcome
import ai.pitcrew.app.errand.PickupUseCase
import ai.pitcrew.app.errand.RoutinePlanner
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.ui.format.hhmm
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDateTime

/**
 * 운전자가 말하기 전에 PitCrew가 먼저 묻는다. 주기적으로 경로·배터리·주문 이력을 보고 한 번에 하나만 제안한다.
 * 우선순위는 예약 지각(또 늦음 포함) > 배터리 부족 충전 > 단골 픽업. 이미 물은 일은 상황이 바뀌기 전까지 다시 묻지 않는다.
 */
class ProactiveAssistant(
    private val scope: CoroutineScope,
    private val enabled: StateFlow<Boolean>,
    private val fetchDestination: suspend () -> Destination?,
    private val batteryPercent: () -> Int?,
    private val reservations: ReservationRepository,
    private val orders: OrderHistory,
    private val stores: StoreCatalog,
    private val errands: ErrandService,
    private val lateArrival: LateArrivalUseCase,
    private val pickup: PickupUseCase,
    private val charging: ChargeAndDineUseCase,
    private val confirmer: VoiceConfirmer,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val intervalMillis: Long = INTERVAL_MILLIS,
) {
    private var job: Job? = null
    private val tickLock = Mutex()
    private val promptedLate = mutableSetOf<String>()
    private val promptedRoutines = mutableSetOf<String>()
    // 한 번 물은 뒤에는 충전해서 다시 올라가기 전까지 묻지 않는다
    private var batteryArmed = true

    /** false면 주기 확인을 멈추고 [checkNow]로만 본다. 시연 녹화가 장면 순서를 정할 때 쓴다. */
    @Volatile var periodic = true

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                if (periodic) runTick()
            }
        }
    }

    /** 주기를 기다리지 않고 지금 한 번 본다. 시연 녹화와 디버그 빌드가 쓴다. */
    fun checkNow() {
        scope.launch { runTick() }
    }

    private suspend fun runTick() {
        try {
            tick()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "proactive check failed", e)
        }
    }

    /** 물어본 작업을 돌려준다. 묻지 않았으면 null. */
    suspend fun tick(): Errand? = tickLock.withLock {
        if (!enabled.value) return null
        // 승인 대기·진행 중인 일이 있으면 겹쳐 묻지 않는다
        if (errands.errands.value.any { it.isActive }) return null
        val destination = runCatching { fetchDestination() }.getOrNull() ?: return null
        lateSuggestion(destination)?.let { return ask(it) }
        batterySuggestion()?.let { return ask(it) }
        routineSuggestion()?.let { return ask(it) }
        null
    }

    private suspend fun lateSuggestion(destination: Destination): Errand? {
        reservations.refreshNow()
        val reservation = LateWatch.candidate(
            reservations.reservations.value, destination.name, now(), destination.remainTimeSeconds, promptedLate,
        ) ?: return null
        promptedLate += LateWatch.key(reservation)
        return lateArrival.suggest(reservation, destination.remainTimeSeconds)
    }

    private suspend fun batterySuggestion(): Errand? {
        val percent = batteryPercent() ?: return null
        if (percent >= ChargePlanner.LOW_PERCENT + REARM_MARGIN) batteryArmed = true
        if (percent > ChargePlanner.LOW_PERCENT || !batteryArmed) return null
        batteryArmed = false
        return (charging.run(origin = ErrandOrigin.SUGGESTED) as? ChargeOutcome.AwaitingApproval)?.errand
    }

    private suspend fun routineSuggestion(): Errand? {
        val history = runCatching { orders.recent() }.getOrElse { return null }
        val routine = RoutinePlanner.find(history, now()) ?: return null
        val today = now().toLocalDate()
        if (!promptedRoutines.add(RoutinePlanner.key(routine, today))) return null
        if (RoutinePlanner.alreadyToday(routine, history, errands.errands.value, today)) return null
        val store = runCatching { stores.candidatesOnRoute() }.getOrDefault(emptyList()).firstOrNull { it.id == routine.storeId } ?: return null
        return (pickup.suggest(routine.item, store) as? PickupOutcome.AwaitingApproval)?.errand
    }

    // 답이 없으면 제안은 카드로 남겨 정차 후 화면에서 고를 수 있게 한다
    private suspend fun ask(errand: Errand): Errand {
        val question = question(errand)
        Log.i(TAG, "asking: $question")
        val answer = confirmer.ask(question)
        Log.i(TAG, "answer: $answer")
        when (answer) {
            Answer.YES -> runCatching { errands.approve(errand.id, ApprovalChannel.VOICE) }
                .onFailure { if (it is CancellationException) throw it else Log.e(TAG, "approve failed", it) }
            Answer.NO -> errands.decline(errand.id)
            Answer.NONE, null -> Unit
        }
        return errand
    }

    companion object {
        private const val TAG = "ProactiveAssistant"
        private const val INTERVAL_MILLIS = 60_000L
        private const val REARM_MARGIN = 10

        fun question(errand: Errand): String = when (val d = errand.detail) {
            is ErrandDetail.LateArrival -> buildString {
                val place = d.placeName ?: "약속"
                if (d.followUp) append("길이 더 막혀 $place 예약에 또 ${d.delayMinutes}분 늦어요. ")
                else append("지금 가면 $place 예약보다 ${d.delayMinutes}분 늦어요. ")
                append("예약을 ${d.proposedTime.hhmm()}에 다시 ")
                append(if (d.companions.isEmpty()) "잡을까요?" else "잡고 일행 ${d.companions.size}명에게 알릴까요?")
            }
            is ErrandDetail.ChargeAndDine -> buildString {
                d.batteryPercent?.let { append("배터리가 ${it}% 남았어요. ") }
                append("${d.charger.name}에서 ${d.chargeMinutes}분쯤 충전하는 동안 ${d.restaurant.name}에 줄을 서 둘까요?")
            }
            is ErrandDetail.Pickup -> "오늘도 ${d.store.displayName}에서 ${d.item} 픽업할까요? 들르면 ${d.readyAt.hhmm()}에 받을 수 있어요."
        }
    }
}
