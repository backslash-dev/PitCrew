package ai.pitcrew.app.debug

import ai.pitcrew.app.PitCrewApplication
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * debug 빌드 전용. 에뮬레이터에서 마이크 없이 먼저 묻기를 시연한다.
 * adb shell am broadcast -a ai.pitcrew.app.debug.CHECK_NOW -n ai.pitcrew.app/.debug.DebugVoiceReceiver
 * adb shell am broadcast -a ai.pitcrew.app.debug.ANSWER --es text 응 -n ai.pitcrew.app/.debug.DebugVoiceReceiver
 * adb shell "am broadcast -a ai.pitcrew.app.debug.SAY --es text '민수랑 중간에서 보자' -n ai.pitcrew.app/.debug.DebugVoiceReceiver"
 * adb shell am broadcast -a ai.pitcrew.app.debug.PERIODIC --ez on false -n ai.pitcrew.app/.debug.DebugVoiceReceiver
 * adb shell am broadcast -a ai.pitcrew.app.debug.ROUTE --es name 강남역 --ef lat 37.4979 --ef lon 127.0276 -n ai.pitcrew.app/.debug.DebugVoiceReceiver
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
            ACTION_SAY -> app.voiceCommands.hear(intent.getStringExtra(EXTRA_TEXT).orEmpty())
            ACTION_PERIODIC -> app.assistant.periodic = intent.getBooleanExtra(EXTRA_ON, true)
            ACTION_ROUTE -> {
                val ok = app.platform.navigation.requestRoute(
                    intent.getStringExtra("name").orEmpty(),
                    intent.getFloatExtra("lat", 0f).toDouble(),
                    intent.getFloatExtra("lon", 0f).toDouble(),
                )
                Log.i(TAG, "route requested=$ok")
            }
            else -> Log.w(TAG, "unknown action ${intent.action}")
        }
    }

    private companion object {
        const val TAG = "DebugVoiceReceiver"
        const val ACTION_CHECK_NOW = "ai.pitcrew.app.debug.CHECK_NOW"
        const val ACTION_ANSWER = "ai.pitcrew.app.debug.ANSWER"
        const val ACTION_SAY = "ai.pitcrew.app.debug.SAY"
        const val ACTION_PERIODIC = "ai.pitcrew.app.debug.PERIODIC"
        const val ACTION_ROUTE = "ai.pitcrew.app.debug.ROUTE"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ON = "on"
    }
}
