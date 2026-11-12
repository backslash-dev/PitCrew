package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.remote.userMessage
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.needsTouchApproval
import ai.pitcrew.app.errand.ChargeAndDineUseCase
import ai.pitcrew.app.errand.ChargeOutcome
import ai.pitcrew.app.errand.CommandInterpreter
import ai.pitcrew.app.errand.LateArrivalOutcome
import ai.pitcrew.app.errand.LateArrivalUseCase
import ai.pitcrew.app.errand.MailReplyOutcome
import ai.pitcrew.app.errand.MailReplyUseCase
import ai.pitcrew.app.errand.MeetupOutcome
import ai.pitcrew.app.errand.MeetupUseCase
import ai.pitcrew.app.errand.PickupOutcome
import ai.pitcrew.app.errand.PickupUseCase
import ai.pitcrew.app.errand.UndoOutcome
import ai.pitcrew.app.errand.UndoUseCase
import ai.pitcrew.app.errand.VoiceCommand
import ai.pitcrew.app.platform.SpeechSource
import ai.pitcrew.app.ui.errand.outcomeLine
import ai.pitcrew.app.ui.errand.title
import ai.pitcrew.app.ui.format.hhmm
import ai.pitcrew.app.ui.format.withObjectParticle
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface VoiceCommandState {
    data object Idle : VoiceCommandState
    data class Listening(val partial: String) : VoiceCommandState
    data class Working(val heard: String) : VoiceCommandState
    /** 주행 중 말로 시킨 일을 음성으로 승인받는 중. */
    data class Confirming(val heard: String, val question: String) : VoiceCommandState
    /** [errandId]는 이 말로 만든 작업. 주행 중에는 그 자리에서 음성으로 승인을 받는다. */
    data class Done(val heard: String?, val message: String, val needsApproval: Boolean, val errandId: String? = null) : VoiceCommandState
}

class VoiceCommandController(
    private val speech: SpeechSource,
    private val interpreter: CommandInterpreter,
    private val lateArrival: LateArrivalUseCase,
    private val pickup: PickupUseCase,
    private val charging: ChargeAndDineUseCase,
    private val undo: UndoUseCase,
    private val meetup: MeetupUseCase,
    private val mail: MailReplyUseCase,
    private val errands: ErrandService,
    private val confirmer: VoiceConfirmer,
    private val driving: () -> Boolean,
    private val scope: CoroutineScope,
    private val listenTimeoutMillis: Long = LISTEN_TIMEOUT_MILLIS,
) {
    private val _state = MutableStateFlow<VoiceCommandState>(VoiceCommandState.Idle)
    val state: StateFlow<VoiceCommandState> = _state.asStateFlow()
    private var job: Job? = null
    private val injected = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** 듣는 중일 때 마이크 대신 문장을 넣는다. debug 빌드의 방송 입력이 쓴다. */
    fun hear(text: String) {
        if (_state.value is VoiceCommandState.Listening) injected.tryEmit(text)
    }

    fun start() {
        val current = _state.value
        if (current is VoiceCommandState.Listening || current is VoiceCommandState.Working || current is VoiceCommandState.Confirming) return
        if (confirmer.busy) {
            _state.value = VoiceCommandState.Done(null, "PitCrew가 묻는 중이에요. 답한 뒤 다시 눌러 주세요.", needsApproval = false)
            return
        }
        if (!speech.listen()) {
            _state.value = VoiceCommandState.Done(null, "음성 인식을 시작하지 못했어요. 잠시 뒤 다시 눌러 주세요.", needsApproval = false)
            return
        }
        _state.value = VoiceCommandState.Listening("")
        job = scope.launch {
            val partials = launch {
                speech.result.collect { r -> if (r != null && !r.isFinal) _state.value = VoiceCommandState.Listening(r.text) }
            }
            // 무음이면 인식이 스스로 끝나지 않으므로 앱이 시간을 정한다
            val final = withTimeoutOrNull(listenTimeoutMillis) {
                merge(speech.result.filterNotNull().filter { it.isFinal }.map { it.text }, injected).first()
            }
            partials.cancel()
            speech.stop()
            val heard = final?.trim().orEmpty()
            if (heard.isEmpty()) {
                _state.value = VoiceCommandState.Done(null, "잘 못 들었어요. 다시 말해 주세요.", needsApproval = false)
                return@launch
            }
            _state.value = VoiceCommandState.Working(heard)
            // 서버·내비 오류가 여기서 새면 앱 범위 코루틴이 죽고 프로세스가 끝난다
            _state.value = try {
                execute(heard).let { if (driving()) confirmByVoice(it) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "voice command failed", e)
                VoiceCommandState.Done(heard, "처리하지 못했어요. ${e.userMessage()}", needsApproval = false)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        speech.stop()
        _state.value = VoiceCommandState.Idle
    }

    fun dismiss() {
        if (_state.value is VoiceCommandState.Done) _state.value = VoiceCommandState.Idle
    }

    // 주행 중에는 화면을 볼 수 없으므로 결과를 말로 알리고, 승인이 필요하면 그 자리에서 묻는다
    private suspend fun confirmByVoice(done: VoiceCommandState.Done): VoiceCommandState.Done {
        val errand = done.errandId?.let { id -> errands.errands.value.firstOrNull { it.id == id } }
        if (errand == null || !done.needsApproval) return speak(done)
        if (errand.detail.needsTouchApproval) {
            val message = "${(errand.detail as ErrandDetail.MailReply).mail.senderName}님께 보낼 답장을 써 뒀어요. 차를 세우면 화면에서 확인하고 보낼 수 있어요."
            return speak(done.copy(message = message, needsApproval = false))
        }
        val question = confirmQuestion(errand)
        _state.value = VoiceCommandState.Confirming(done.heard.orEmpty(), question)
        val message = when (confirmer.ask(question)) {
            Answer.YES -> {
                val result = errands.approve(errand.id, ApprovalChannel.VOICE)
                result.outcomeLine() ?: "처리하고 있어요. 결과는 화면에 남겨 둘게요."
            }
            Answer.NO -> {
                errands.decline(errand.id)
                "하지 않을게요."
            }
            Answer.NONE, null -> "답이 없어 카드로 남겨 뒀어요. 차를 세우면 화면에서 승인할 수 있어요."
        }
        return speak(done.copy(message = message, needsApproval = false))
    }

    private suspend fun confirmUndo(heard: String, target: Errand): String {
        val question = "방금 한 일(${target.title()})을 되돌릴까요?"
        _state.value = VoiceCommandState.Confirming(heard, question)
        return when (confirmer.ask(question)) {
            Answer.YES -> when (val outcome = undo.run(confirmed = true, channel = ApprovalChannel.VOICE)) {
                is UndoOutcome.Finished -> outcome.errand.outcomeLine() ?: "되돌렸어요."
                else -> "되돌릴 수 있는 작업이 없어요."
            }
            Answer.NO -> "그대로 둘게요."
            Answer.NONE, null -> "답이 없어 되돌리지 않았어요."
        }
    }

    // 말로 읽는 동안에도 화면에 같은 문장이 보이게 먼저 바꾼다
    private suspend fun speak(done: VoiceCommandState.Done): VoiceCommandState.Done {
        _state.value = done
        confirmer.tell(done.message)
        return done
    }

    private suspend fun execute(heard: String): VoiceCommandState.Done {
        fun done(message: String, needsApproval: Boolean = false) = VoiceCommandState.Done(heard, message, needsApproval)
        return when (val command = interpreter.interpret(heard)) {
            is VoiceCommand.LateArrival -> when (val outcome = lateArrival.run(command.appointment)) {
                LateArrivalOutcome.NoRoute -> done("내비게이션에서 목적지를 먼저 정해 주세요.")
                LateArrivalOutcome.NavigationUnavailable -> done("내비게이션에 연결하지 못했어요.")
                is LateArrivalOutcome.OnTime -> done("제때 도착해요. 예약을 바꿀 필요가 없어요.")
                is LateArrivalOutcome.NoReservation -> done("${outcome.plan.delayMinutes}분 늦지만 그 시각에 등록된 예약이 없어요. 예약 탭에서 먼저 등록해 주세요.")
                is LateArrivalOutcome.AwaitingApproval ->
                    done("${outcome.plan.delayMinutes}분 늦어요. 아래 카드에서 확인하고 승인해 주세요.", needsApproval = true).copy(errandId = outcome.errand.id)
                is LateArrivalOutcome.Finished -> done("처리했어요.")
            }
            VoiceCommand.LateArrivalWithoutTime -> done("몇 시 약속인지 함께 말해 주세요.")
            is VoiceCommand.Pickup -> when (val outcome = pickup.run(command.item, command.storeName)) {
                PickupOutcome.NoRoute -> done("내비게이션에서 목적지를 먼저 정해 주세요.")
                PickupOutcome.NavigationUnavailable -> done("내비게이션에 연결하지 못했어요.")
                PickupOutcome.NoStore -> done("경로에서 맞는 매장을 찾지 못했어요.")
                is PickupOutcome.AwaitingApproval -> {
                    val store = (outcome.errand.detail as ErrandDetail.Pickup).store
                    done("${store.displayName}에서 픽업할 수 있어요. 아래 카드에서 확인하고 승인해 주세요.", needsApproval = true).copy(errandId = outcome.errand.id)
                }
                is PickupOutcome.Finished -> done("처리했어요.")
            }
            VoiceCommand.PickupWithoutItem -> done("무엇을 픽업할지 함께 말해 주세요.")
            is VoiceCommand.ChargeAndDine -> when (val outcome = charging.run(command.partySize)) {
                ChargeOutcome.NoRoute -> done("내비게이션에서 목적지를 먼저 정해 주세요.")
                ChargeOutcome.NavigationUnavailable -> done("내비게이션에 연결하지 못했어요.")
                ChargeOutcome.NoCharger -> done("경로에서 자리가 있는 충전소를 찾지 못했어요.")
                is ChargeOutcome.NotNeeded -> done("배터리가 ${outcome.batteryPercent}% 남아 있어 충전하지 않아도 돼요.")
                is ChargeOutcome.AwaitingApproval -> {
                    val d = outcome.errand.detail as ErrandDetail.ChargeAndDine
                    done("${d.charger.name}에서 충전하는 동안 ${d.restaurant.name}에 줄을 설 수 있어요. 아래 카드에서 확인하고 승인해 주세요.", needsApproval = true)
                        .copy(errandId = outcome.errand.id)
                }
            }
            // "취소"라는 말이 다른 문장에 섞여도 바로 무르지 않게, 대상만 알려 주고 화면에서 확인받는다
            VoiceCommand.Undo -> when (val outcome = undo.run(confirmed = false, channel = ApprovalChannel.VOICE)) {
                UndoOutcome.NothingToUndo -> done("되돌릴 수 있는 최근 작업이 없어요. 끝낸 지 ${Errand.UNDO_WINDOW.toMinutes()}분 안의 작업만 되돌릴 수 있어요.")
                is UndoOutcome.AwaitingConfirmation ->
                    if (driving()) done(confirmUndo(heard, outcome.errand))
                    else done("방금 한 일(${outcome.errand.title()})을 되돌리려면 최근 목록에서 되돌리기를 눌러 주세요.")
                is UndoOutcome.Finished -> done("되돌렸어요.")
            }
            is VoiceCommand.Meetup -> when (val outcome = meetup.propose(command.contactName, command.time)) {
                is MeetupOutcome.UnknownContact -> done(
                    if (outcome.spoken == null) "누구와 만날지 일행 이름을 함께 말해 주세요." else "${outcome.spoken}님을 일행에서 찾지 못했어요. 예약 탭의 일행에 먼저 등록해 주세요.",
                )
                is MeetupOutcome.Unavailable -> done(outcome.reason)
                is MeetupOutcome.AwaitingApproval -> {
                    val d = outcome.errand.detail as ErrandDetail.Meetup
                    done(
                        when (d.mode) {
                            MeetupMode.AGENT -> "${d.contact.name}님 에이전트와 맞춰 ${d.meetAt?.hhmm()} ${d.place?.name}에서 만나요. 아래 카드에서 확인하고 승인해 주세요."
                            MeetupMode.SMS -> "${d.contact.name}님은 에이전트가 없어 문자로 위치를 물어본 뒤 장소를 정해요. 아래 카드에서 승인해 주세요."
                        },
                        needsApproval = true,
                    ).copy(errandId = outcome.errand.id)
                }
                is MeetupOutcome.Finished -> done("처리했어요.")
            }
            is VoiceCommand.MailReply -> when (val outcome = mail.draft(command.instruction)) {
                MailReplyOutcome.NoMail -> done("답장할 메일이 없어요.")
                is MailReplyOutcome.AwaitingReview -> {
                    val d = outcome.errand.detail as ErrandDetail.MailReply
                    done("${d.mail.senderName}님께 보낼 답장을 썼어요. 아래 카드에서 내용을 확인하고 보내 주세요.", needsApproval = true)
                        .copy(errandId = outcome.errand.id)
                }
            }
            VoiceCommand.Unknown -> done("어떤 일을 할지 알아듣지 못했어요. 지각 대응, 픽업 주문, 충전, 약속 잡기, 메일 답장, 되돌리기를 말해 주세요.")
        }
    }

    companion object {
        private const val LISTEN_TIMEOUT_MILLIS = 10_000L

        /** 주행 중 말로 시킨 일을 승인받을 때 묻는 문장. */
        fun confirmQuestion(errand: Errand): String = when (val d = errand.detail) {
            is ErrandDetail.Pickup -> "${d.store.displayName}에서 ${d.item.withObjectParticle()} ${d.readyAt.hhmm()}에 받도록 주문할까요?"
            is ErrandDetail.Meetup -> when (d.mode) {
                MeetupMode.AGENT -> "${d.contact.name}님과 ${d.meetAt?.hhmm()}에 ${d.place?.name}에서 만나도록 예약할까요?"
                MeetupMode.SMS -> "${d.contact.name}님께 문자로 위치를 물어 중간 장소를 잡을까요?"
            }
            else -> ProactiveAssistant.question(errand)
        }
        private const val TAG = "VoiceCommandController"
    }
}
