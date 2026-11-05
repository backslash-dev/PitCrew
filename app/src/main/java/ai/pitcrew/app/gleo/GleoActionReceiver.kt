package ai.pitcrew.app.gleo

import ai.pitcrew.app.PitCrewApplication
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import org.json.JSONException

/** schema.json의 component "service" 함수는 sendBroadcast로 들어온다. 주행 중에도 화면 없이 처리된다. */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as PitCrewApplication
        val function = GleoFunction.fromAction(intent.action, context.packageName) ?: run {
            Log.w(TAG, "unknown action ${intent.action}")
            return
        }
        val rawParams = intent.getStringExtra(GleoRequest.EXTRA_ACTION_PARAM)
        Log.i(TAG, "received $function params=$rawParams")

        val pending = goAsync()
        app.scope.launch {
            try {
                val response = try {
                    app.gleo.dispatch(GleoRequest.parse(function, rawParams))
                } catch (e: JSONException) {
                    Log.w(TAG, "invalid params $rawParams", e)
                    GleoResponse.fail(function, "The request parameters could not be read.")
                }
                GleoCallback.send(app, response)
                // 앱 화면 없이 Gleo로만 주문해도 도착 직전 안내가 나가도록 한다
                app.announcer.start()
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "GleoActionReceiver"
    }
}
