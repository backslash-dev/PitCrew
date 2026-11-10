package ai.pitcrew.app.gleo

import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

enum class GleoFunction {
    NOTIFY_LATE_ARRIVAL,
    ORDER_PICKUP_ON_ROUTE,
    CANCEL_LAST_ERRAND;

    companion object {
        // 문서 예시는 action이 함수명이지만, 에뮬레이터 Gleo(ai.umos.ambientai)는 "<패키지명>.<함수명>"으로 보낸다
        fun fromAction(action: String?, packageName: String): GleoFunction? {
            val name = action?.removePrefix("$packageName.") ?: return null
            return entries.firstOrNull { it.name == name }
        }
    }
}

class GleoRequest(val function: GleoFunction, val params: JSONObject) {
    companion object {
        const val EXTRA_ACTION_PARAM = "ACTION_PARAM"

        /** @throws JSONException 파라미터가 JSON 객체로 해석되지 않을 때 */
        fun parse(function: GleoFunction, rawParams: String?): GleoRequest =
            GleoRequest(function, parseParams(rawParams))

        // 문서 예시는 JSON 객체를 따옴표로 한 번 더 감싸 보낸다. 감싼 형태와 그대로인 형태를 모두 받는다
        internal fun parseParams(raw: String?): JSONObject {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return JSONObject()
            if (text.startsWith("{")) return JSONObject(text)
            if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                val inner = decodeStringLiteral(text) ?: text.substring(1, text.length - 1)
                return parseParams(inner)
            }
            throw JSONException("ACTION_PARAM is not a JSON object")
        }

        // 안쪽 따옴표가 이스케이프되지 않았으면 앞부분만 문자열로 읽히므로, 끝까지 소비했을 때만 인정한다
        private fun decodeStringLiteral(text: String): String? = runCatching {
            val tokener = JSONTokener(text)
            (tokener.nextValue() as? String)?.takeUnless { tokener.more() }
        }.getOrNull()
    }
}

enum class GleoStatus(val wire: String) { SUCCESS("success"), ASKING("asking"), FAIL("fail") }

/** message는 Gleo 규격상 영어 완결 문장이어야 한다. 사용자에게 할 한국어 답변은 Gleo가 만든다. */
data class GleoResponse(
    val function: GleoFunction,
    val status: GleoStatus,
    val message: String,
    val data: List<Map<String, Any>> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("functionName", function.name)
        put("status", status.wire)
        put("message", message)
        if (data.isNotEmpty()) put("data", JSONArray(data.map { JSONObject(it) }).toString())
    }

    companion object {
        fun success(function: GleoFunction, message: String, data: List<Map<String, Any>> = emptyList()) =
            GleoResponse(function, GleoStatus.SUCCESS, message, data)

        fun asking(function: GleoFunction, message: String, data: List<Map<String, Any>> = emptyList()) =
            GleoResponse(function, GleoStatus.ASKING, message, data)

        fun fail(function: GleoFunction, message: String, data: List<Map<String, Any>> = emptyList()) =
            GleoResponse(function, GleoStatus.FAIL, message, data)
    }
}

object GleoCallback {
    private const val ACTION_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    /** Gleo(ai.umos.ambientai)가 정의한 서명 권한. 응답에 예약·주문 내용이 담기므로 Gleo만 받게 한다. */
    const val GLEO_PERMISSION = "ai.umos.gleo.permission.APP_ACTION"
    private const val TAG = "GleoCallback"

    // Gleo는 런타임 등록 수신기로 받으므로 패키지를 지정하지 않고, 받는 쪽 권한으로 제한한다
    fun send(context: Context, response: GleoResponse) {
        val payload = response.toJson().toString()
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_RESPONSE)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", payload),
                GLEO_PERMISSION,
            )
        }.onSuccess {
            Log.i(TAG, "sent $payload")
        }.onFailure {
            Log.e(TAG, "callback failed $payload", it)
        }
    }
}
