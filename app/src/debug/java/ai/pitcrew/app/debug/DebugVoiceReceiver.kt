package ai.pitcrew.app.debug

import ai.pitcrew.app.PitCrewApplication
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * debug 빌드 전용. 에뮬레이터에서 마이크 없이 먼저 묻기를 시연·검증한다.
 * adb shell am broadcast -a ai.pitcrew.app.debug.CHECK_NOW -n ai.pitcrew.app/.debug.DebugVoiceReceiver
 * adb shell am broadcast -a ai.pitcrew.app.debug.ANSWER --es text 응 -n ai.pitcrew.app/.debug.DebugVoiceReceiver
 */
class DebugVoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PitCrewApplication
        when (intent.action) {
            ACTION_CHECK_NOW -> {
                app.assistant.start()
                app.assistant.checkNow()
            }
            ACTION_ANSWER -> app.confirmer.answer(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    private companion object {
        const val TAG = "DebugVoiceReceiver"
        const val ACTION_CHECK_NOW = "ai.pitcrew.app.debug.CHECK_NOW"
        const val ACTION_ANSWER = "ai.pitcrew.app.debug.ANSWER"
        const val EXTRA_TEXT = "text"
    }
}
