package ai.pitcrew.app.platform

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface Speaker {
    val speaking: StateFlow<Boolean>
    fun speak(text: String): Boolean
}

class VoiceOutput(context: Context) : Speaker, AutoCloseable {
    private val tts = TextToSpeech(context, Mode.ON_DEVICE)

    private val _status = MutableStateFlow<SdkStatus>(SdkStatus.Connecting)
    val status: StateFlow<SdkStatus> = _status.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    override val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val listener = object : EventListener {
        override fun onReady() {}
        override fun onStart() { _speaking.value = true }
        override fun onDone() { _speaking.value = false }
        override fun onStop() { _speaking.value = false }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "tts error $errorType")
            _speaking.value = false
        }
        override fun onUpdatedRms(rms: Double) {}
    }

    // 리스너는 initialize 이후에 등록해야 한다. 먼저 등록하면 SDK 내부 미초기화로 크래시
    fun start() {
        runCatching {
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() { _status.value = SdkStatus.Ready }
                override fun onFailure(errorType: ErrorType) {
                    Log.e(TAG, "tts init failed $errorType")
                    _status.value = SdkStatus.Failed(errorType.name)
                }
            })
            tts.addEventListener(listener)
        }.onFailure {
            Log.e(TAG, "tts init failed", it)
            _status.value = SdkStatus.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    override fun speak(text: String): Boolean {
        if (_status.value != SdkStatus.Ready || text.isBlank()) return false
        // isFinal=false는 뒤에 조각이 이어진다는 뜻이라 onDone이 오지 않는다
        tts.speak(text, true)
        return true
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    override fun close() {
        runCatching { tts.removeEventListener(listener) }
        runCatching { tts.release() }
    }

    private companion object {
        const val TAG = "VoiceOutput"
    }
}
