package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.errand.CommandInterpreter
import ai.pitcrew.app.errand.LateArrivalOutcome
import ai.pitcrew.app.errand.LateArrivalUseCase
import ai.pitcrew.app.errand.PickupOutcome
import ai.pitcrew.app.errand.PickupUseCase
import ai.pitcrew.app.errand.VoiceCommand
import ai.pitcrew.app.platform.SpeechSource
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

/** 주차 중 앱 화면에서 말로 시키기. 들은 문장을 해석해 지각 대응·픽업 유스케이스를 실행한다. */
class VoiceCommandController(
    private val speech: SpeechSource,
    private val interpreter: CommandInterpreter,
    private val lateArrival: LateArrivalUseCase,
    private val pickup: PickupUseCase,
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
            _state.value = execute(heard)
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
            VoiceCommand.Unknown -> done("어떤 일을 할지 알아듣지 못했어요. 지각 대응이나 픽업 주문을 말해 주세요.")
        }
    }

    private companion object {
        const val LISTEN_TIMEOUT_MILLIS = 10_000L
    }
}
