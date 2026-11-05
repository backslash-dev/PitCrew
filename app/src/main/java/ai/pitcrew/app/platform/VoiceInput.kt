package ai.pitcrew.app.platform

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpeechResult(val text: String, val isFinal: Boolean)

interface SpeechSource {
    val listening: StateFlow<Boolean>
    val result: StateFlow<SpeechResult?>
    fun listen(): Boolean
    fun stop()
}

class VoiceInput(context: Context) : SpeechSource, AutoCloseable {
    private val stt = SpeechToText(context, Mode.ON_DEVICE)

    private val _status = MutableStateFlow<SdkStatus>(SdkStatus.Connecting)
    val status: StateFlow<SdkStatus> = _status.asStateFlow()

    private val _listening = MutableStateFlow(false)
    override val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _result = MutableStateFlow<SpeechResult?>(null)
    override val result: StateFlow<SpeechResult?> = _result.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val listener = object : ResultListener {
        override fun onReady() {}
        override fun onStartedRecognition() {}
        override fun onUpdated(text: String, isFinal: Boolean) {
            _result.value = SpeechResult(text, isFinal)
        }
        override fun onUpdatedEpdData(start: Long, end: Long) {}
        override fun onEndedRecognition() { _listening.value = false }
        override fun onError(errorType: ErrorType) {
            Log.w(TAG, "stt error $errorType")
            _listening.value = false
            _lastError.value = errorType.name
        }
        override fun onUpdatedRms(rms: Double) {}
    }

    // 리스너는 initialize 이후에 등록해야 한다. 먼저 등록하면 SDK 내부 미초기화로 크래시
    fun start() {
        runCatching {
            stt.initialize(object : OnInitializeListener {
                override fun onSuccess() { _status.value = SdkStatus.Ready }
                override fun onFailure(errorType: ErrorType) {
                    Log.e(TAG, "stt init failed $errorType")
                    _status.value = SdkStatus.Failed(errorType.name)
                }
            })
            stt.addListener(listener)
        }.onFailure {
            Log.e(TAG, "stt init failed", it)
            _status.value = SdkStatus.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    // onStartedRecognition은 말소리가 감지돼야 오므로 요청 시점부터 듣는 중으로 본다
    override fun listen(): Boolean {
        if (_status.value != SdkStatus.Ready || _listening.value) return false
        beginRecognition()
        return runCatching { stt.request() }.onFailure(::abortRecognition).isSuccess
    }

    /** 마이크 대신 16kHz 16-bit mono PCM을 넣는다. ON_DEVICE 모드에서만 동작한다. */
    fun recognize(pcm: ByteArray): Boolean {
        if (_status.value != SdkStatus.Ready || _listening.value || pcm.isEmpty()) return false
        beginRecognition()
        return runCatching {
            stt.sendAudio(pcm)
            stt.completeAudioSend()
        }.onFailure(::abortRecognition).isSuccess
    }

    override fun stop() {
        runCatching { stt.stop() }
        _listening.value = false
    }

    private fun beginRecognition() {
        _result.value = null
        _lastError.value = null
        _listening.value = true
    }

    private fun abortRecognition(e: Throwable) {
        Log.w(TAG, "stt request failed", e)
        _listening.value = false
        _lastError.value = e.message ?: e.javaClass.simpleName
    }

    override fun close() {
        runCatching { stt.removeListener(listener) }
        runCatching { stt.release() }
    }

    private companion object {
        const val TAG = "VoiceInput"
    }
}
