package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.platform.Speaker
import ai.pitcrew.app.platform.SpeechSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

enum class Answer { YES, NO, NONE }

object AnswerClassifier {
    // "안 해줘"처럼 거절 안에 승낙 낱말이 들어가므로 거절부터 본다
    private val NO = Regex("아니|싫어|됐어|괜찮아|하지\\s*마|안\\s*해|거절|취소|나중에|그만")
    // "어떡하지"처럼 같은 글자로 시작하는 말을 승낙으로 보지 않도록 짧은 답은 뒤가 끊겨야 한다
    private val YES = Regex("^(응|어|네|예|넵|그래|좋아|오케이|콜)(?=$|[\\s.!,요응네])|해\\s*줘|부탁|승인|그렇게|맞아")

    fun classify(text: String): Answer {
        val t = text.trim()
        return when {
            t.isEmpty() -> Answer.NONE
            NO.containsMatchIn(t) -> Answer.NO
            // "어?", "네?"처럼 되묻는 말은 못 들었다는 뜻이라 승낙으로 보지 않는다
            t.endsWith("?") -> Answer.NONE
            YES.containsMatchIn(t) -> Answer.YES
            else -> Answer.NONE
        }
    }
}

/**
 * PitCrew가 먼저 묻는 질문을 TTS로 말하고 STT로 "응/아니"를 듣는다. Gleo를 거치지 않으므로 주행 중에도 손을 쓰지 않는다.
 * 다른 음성 입력("말하기")이 듣는 중이면 묻지 않는다.
 */
class VoiceConfirmer(
    private val speaker: Speaker,
    private val speech: SpeechSource,
    private val listenMillis: Long = LISTEN_MILLIS,
    private val speakStartMillis: Long = SPEAK_START_MILLIS,
    private val speakMaxMillis: Long = SPEAK_MAX_MILLIS,
) {
    private val mutex = Mutex()
    // 질문을 말하는 동안 들어온 답도 받도록 한 개를 남겨 둔다
    private val injected = MutableSharedFlow<String>(replay = 1)

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    /** 마이크 대신 답을 넣는다. 시연 녹화와 디버그 빌드의 방송 입력이 쓴다. */
    fun answer(text: String) {
        injected.tryEmit(text)
    }

    /** 물을 수 없으면(다른 질문·음성 입력 중, TTS 미연결) null. */
    suspend fun ask(question: String): Answer? {
        if (speech.listening.value || !mutex.tryLock()) return null
        try {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            injected.resetReplayCache()
            if (!speaker.speak(question)) return null
            // 말하기 시작 이벤트가 오지 않는 경우(SDK 오류)에도 멈추지 않게 시간 제한을 둔다
            withTimeoutOrNull(speakStartMillis) { speaker.speaking.first { it } }
            withTimeoutOrNull(speakMaxMillis) { speaker.speaking.first { !it } }

            val micStarted = speech.listen()
            _listening.value = true
            val heard = try {
                withTimeoutOrNull(listenMillis) {
                    val spoken = if (micStarted) speech.result.filterNotNull().filter { it.isFinal }.map { it.text } else null
                    (if (spoken != null) merge(spoken, injected) else injected).first()
                }
            } finally {
                _listening.value = false
                if (micStarted) speech.stop()
            }
            return heard?.let(AnswerClassifier::classify) ?: Answer.NONE
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        const val LISTEN_MILLIS = 7_000L
        const val SPEAK_START_MILLIS = 3_000L
        const val SPEAK_MAX_MILLIS = 20_000L
    }
}
