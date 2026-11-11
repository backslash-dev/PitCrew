package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.errand.ChargeAndDineUseCase
import ai.pitcrew.app.errand.ChargeOutcome
import ai.pitcrew.app.errand.CommandInterpreter
import ai.pitcrew.app.errand.LateArrivalOutcome
import ai.pitcrew.app.errand.LateArrivalUseCase
import ai.pitcrew.app.errand.PickupOutcome
import ai.pitcrew.app.errand.PickupUseCase
import ai.pitcrew.app.errand.UndoOutcome
import ai.pitcrew.app.errand.UndoUseCase
import ai.pitcrew.app.errand.VoiceCommand
import ai.pitcrew.app.ui.errand.title
import ai.pitcrew.app.platform.SpeechSource
import ai.pitcrew.app.data.remote.userMessage
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface VoiceCommandState {
    data object Idle : VoiceCommandState
    data class Listening(val partial: String) : VoiceCommandState
    data class Working(val heard: String) : VoiceCommandState
    data class Done(val heard: String?, val message: String, val needsApproval: Boolean) : VoiceCommandState
}

/** 주차 중 앱 화면의 "말하기". 들은 문장을 해석해 지각 대응·픽업·충전·되돌리기 유스케이스를 실행한다. */
class VoiceCommandController(
    private val speech: SpeechSource,
    private val interpreter: CommandInterpreter,
    private val lateArrival: LateArrivalUseCase,
    private val pickup: PickupUseCase,
    private val charging: ChargeAndDineUseCase,
    private val undo: UndoUseCase,
    private val scope: CoroutineScope,
    private val listenTimeoutMillis: Long = LISTEN_TIMEOUT_MILLIS,
) {
    private val _state = MutableStateFlow<VoiceCommandState>(VoiceCommandState.Idle)
    val state: StateFlow<VoiceCommandState> = _state.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (_state.value is VoiceCommandState.Listening || _state.value is VoiceCommandState.Working) return
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
            val final = withTimeoutOrNull(listenTimeoutMillis) { speech.result.filterNotNull().first { it.isFinal } }
            partials.cancel()
            speech.stop()
            val heard = final?.text?.trim().orEmpty()
            if (heard.isEmpty()) {
                _state.value = VoiceCommandState.Done(null, "잘 못 들었어요. 다시 말해 주세요.", needsApproval = false)
                return@launch
            }
            _state.value = VoiceCommandState.Working(heard)
            // 서버·내비 오류가 여기서 새면 앱 범위 코루틴이 죽고 프로세스가 끝난다
            _state.value = try {
                execute(heard)
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

    private suspend fun execute(heard: String): VoiceCommandState.Done {
        fun done(message: String, needsApproval: Boolean = false) = VoiceCommandState.Done(heard, message, needsApproval)
        return when (val command = interpreter.interpret(heard)) {
            is VoiceCommand.LateArrival -> when (val outcome = lateArrival.run(command.appointment)) {
                LateArrivalOutcome.NoRoute -> done("내비게이션에서 목적지를 먼저 정해 주세요.")
                LateArrivalOutcome.NavigationUnavailable -> done("내비게이션에 연결하지 못했어요.")
                is LateArrivalOutcome.OnTime -> done("제때 도착해요. 예약을 바꿀 필요가 없어요.")
                is LateArrivalOutcome.NoReservation -> done("${outcome.plan.delayMinutes}분 늦지만 그 시각에 등록된 예약이 없어요. 예약 탭에서 먼저 등록해 주세요.")
                is LateArrivalOutcome.AwaitingApproval -> done("${outcome.plan.delayMinutes}분 늦어요. 아래 카드에서 확인하고 승인해 주세요.", needsApproval = true)
                is LateArrivalOutcome.Finished -> done("처리했어요.")
            }
            VoiceCommand.LateArrivalWithoutTime -> done("몇 시 약속인지 함께 말해 주세요.")
            is VoiceCommand.Pickup -> when (val outcome = pickup.run(command.item, command.storeName)) {
                PickupOutcome.NoRoute -> done("내비게이션에서 목적지를 먼저 정해 주세요.")
                PickupOutcome.NavigationUnavailable -> done("내비게이션에 연결하지 못했어요.")
                PickupOutcome.NoStore -> done("경로에서 맞는 매장을 찾지 못했어요.")
                is PickupOutcome.AwaitingApproval -> {
                    val store = (outcome.errand.detail as ErrandDetail.Pickup).store
                    done("${store.displayName}에서 픽업할 수 있어요. 아래 카드에서 확인하고 승인해 주세요.", needsApproval = true)
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
                }
            }
            // "취소"라는 말이 다른 문장에 섞여도 바로 무르지 않게, 대상만 알려 주고 화면에서 확인받는다
            VoiceCommand.Undo -> when (val outcome = undo.run(confirmed = false, channel = ApprovalChannel.VOICE)) {
                UndoOutcome.NothingToUndo -> done("되돌릴 수 있는 최근 작업이 없어요. 끝낸 지 ${Errand.UNDO_WINDOW.toMinutes()}분 안의 작업만 되돌릴 수 있어요.")
                is UndoOutcome.AwaitingConfirmation -> done("방금 한 일(${outcome.errand.title()})을 되돌리려면 최근 목록에서 되돌리기를 눌러 주세요.")
                is UndoOutcome.Finished -> done("되돌렸어요.")
            }
            VoiceCommand.Unknown -> done("어떤 일을 할지 알아듣지 못했어요. 지각 대응, 픽업 주문, 충전, 되돌리기를 말해 주세요.")
        }
    }

    private companion object {
        const val LISTEN_TIMEOUT_MILLIS = 10_000L
        const val TAG = "VoiceCommandController"
    }
}
