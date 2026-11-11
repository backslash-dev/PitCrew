package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.MailService
import ai.pitcrew.app.data.OrderHistory
import ai.pitcrew.app.data.ReservationRepository
import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.Mail
import ai.pitcrew.app.errand.ChargeAndDineUseCase
import ai.pitcrew.app.errand.ChargeOutcome
import ai.pitcrew.app.errand.ChargePlanner
import ai.pitcrew.app.errand.LateArrivalUseCase
import ai.pitcrew.app.errand.LateWatch
import ai.pitcrew.app.errand.MailReplyOutcome
import ai.pitcrew.app.errand.MailReplyUseCase
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
import java.time.Duration
import java.time.LocalDateTime

/** 이미 물은 일은 상황이 바뀌기 전까지 다시 묻지 않는다. */
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
    private val mails: MailService,
    private val mailReply: MailReplyUseCase,
    private val confirmer: VoiceConfirmer,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val intervalMillis: Long = INTERVAL_MILLIS,
) {
    private var job: Job? = null
    private val tickLock = Mutex()
    private val promptedLate = mutableSetOf<String>()
    private val promptedRoutines = mutableSetOf<String>()
    private val announcedMails = mutableSetOf<String>()
    // 한 번 물은 뒤에는 충전해서 다시 올라가기 전까지 묻지 않는다
    private var batteryArmed = true

    /** 시연 녹화가 장면 순서를 직접 정할 때 끈다. */
    @Volatile var periodic = true

    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMillis)
                errands.expireStale()
                if (periodic) runTick()
            }
        }
    }

    /** 시연 녹화와 디버그 빌드가 쓴다. */
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

    suspend fun tick(): Errand? = tickLock.withLock {
        errands.expireStale()
        if (!enabled.value) return skip("disabled")
        // 승인 대기·진행 중인 일이 있으면 겹쳐 묻지 않는다
        if (errands.errands.value.any { it.blocksSuggestions }) return skip("active errand")
        val destination = runCatching { fetchDestination() }.onFailure { Log.w(TAG, "destination unavailable", it) }.getOrNull()
        if (destination != null) {
            lateSuggestion(destination)?.let { return ask(it) }
            batterySuggestion()?.let { return ask(it) }
        }
        newMail()?.let { return readMail(it) }
        if (destination == null) return skip("no route")
        routineSuggestion()?.let { return ask(it) }
        skip("nothing to suggest")
    }

    private fun skip(reason: String): Errand? {
        Log.d(TAG, "no suggestion: $reason")
        return null
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

    private suspend fun newMail(): Mail? {
        runCatching { mails.refreshNow() }.onFailure { if (it is CancellationException) throw it else Log.w(TAG, "mail refresh failed", it) }
        val drafted = errands.errands.value.mapNotNull { (it.detail as? ErrandDetail.MailReply)?.mail?.id }.toSet()
        return mails.mails.value
            // 서버와 기기 시계가 조금 어긋날 수 있어 앞뒤로 본다
            .filter { it.id !in announcedMails && it.id !in drafted && Duration.between(it.receivedAt, now()).abs() <= MAIL_FRESH }
            .maxByOrNull { it.receivedAt }
    }

    // 운전 중에는 메일을 볼 수 없으므로 요약을 읽고 답은 초안으로만 만든다
    private suspend fun readMail(mail: Mail): Errand? {
        val question = "${mail.senderName}님 메일이 왔어요. ${mail.summary.trimEnd('.', ' ')}. 답장하려면 하실 말을 해 주세요."
        Log.i(TAG, "asking: $question")
        val heard = confirmer.hear(question) ?: return skip("confirmer busy")
        announcedMails += mail.id
        Log.i(TAG, "answer: ${heard.ifEmpty { "NONE" }}")
        if (heard.isBlank() || AnswerClassifier.classify(heard) == Answer.NO) return null
        val outcome = mailReply.draft(heard, mail, ErrandOrigin.SUGGESTED) as? MailReplyOutcome.AwaitingReview ?: return null
        confirmer.tell("답장을 써 뒀어요. 차를 세우면 화면에서 확인하고 보낼 수 있어요.")
        return outcome.errand
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
        // 앱을 켜기 한참 전에 온 메일까지 줄줄이 읽지 않는다
        private val MAIL_FRESH: Duration = Duration.ofMinutes(30)

        fun question(errand: Errand): String = when (val d = errand.detail) {
            is ErrandDetail.LateArrival -> buildString {
                val place = d.placeName ?: "약속"
                if (d.followUp) append("도착이 더 늦어져 $place 예약에 또 ${d.delayMinutes}분 늦어요. ")
                else append("지금 가면 $place 예약보다 ${d.delayMinutes}분 늦어요. ")
                append("예약을 ${d.proposedTime.hhmm()}에 다시 ")
                append(if (d.companions.isEmpty()) "잡을까요?" else "잡고 일행 ${d.companions.size}명에게 알릴까요?")
            }
            is ErrandDetail.ChargeAndDine -> buildString {
                d.batteryPercent?.let { append("배터리가 ${it}% 남았어요. ") }
                append("${d.charger.name}에서 ${d.chargeMinutes}분쯤 충전하는 동안 ${d.restaurant.name}에 줄을 서 둘까요?")
            }
            is ErrandDetail.Pickup -> "오늘도 ${d.store.displayName}에서 ${d.item} 픽업할까요? 들르면 ${d.readyAt.hhmm()}에 받을 수 있어요."
            is ErrandDetail.Meetup -> "${d.contact.name}님과 ${d.meetAt?.hhmm().orEmpty()} ${d.place?.name ?: "중간 지점"}에서 만날까요?"
            is ErrandDetail.MailReply -> "${d.mail.senderName}님께 답장을 보낼까요?"
        }
    }
}
