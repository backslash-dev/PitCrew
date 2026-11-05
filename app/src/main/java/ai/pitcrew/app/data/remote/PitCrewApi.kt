package ai.pitcrew.app.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 서버가 준 오류. [message]는 서버의 한국어 안내를 그대로 쓴다. */
class ApiException(val status: Int, val code: String, message: String) : IOException(message)

/** PitCrew 서버 HTTP 클라이언트. 응답 대기는 Gleo 응답 예산을 넘지 않게 짧게 둔다. */
class PitCrewApi(
    baseUrl: String,
    private val apiToken: String = "",
    private val client: OkHttpClient = defaultClient(),
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()

    suspend fun get(path: String, query: Map<String, String> = emptyMap()): JSONObject = send(request(path, query).get())

    suspend fun put(path: String, body: JSONObject): JSONObject = send(request(path).put(body.toRequestBody()))

    suspend fun post(path: String, body: JSONObject): JSONObject = send(request(path).post(body.toRequestBody()))

    suspend fun delete(path: String): JSONObject = send(request(path).delete())

    private fun request(path: String, query: Map<String, String> = emptyMap()): Request.Builder {
        val url = base.newBuilder().addPathSegments(path.trimStart('/')).apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        return Request.Builder().url(url).apply {
            if (apiToken.isNotEmpty()) header("Authorization", "Bearer $apiToken")
        }
    }

    private suspend fun send(builder: Request.Builder): JSONObject {
        val call = client.newCall(builder.build())
        val response = call.await()
        response.use {
            val text = it.body?.string().orEmpty()
            val json = if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!it.isSuccessful) {
                val error = json.optJSONObject("error")
                throw ApiException(it.code, error?.optString("code").orEmpty().ifEmpty { "HTTP_${it.code}" }, error?.optString("message").orEmpty().ifEmpty { "서버 오류(${it.code})" })
            }
            return json
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
        })
    }

    private fun JSONObject.toRequestBody() = toString().toRequestBody(JSON)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }
}

/** 네트워크·서버 오류를 사용자 문장으로 바꾼다. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> message ?: "서버 오류가 발생했어요"
    is JSONException -> "서버 응답을 읽지 못했어요"
    is IOException -> "서버에 연결하지 못했어요"
    else -> message ?: "알 수 없는 오류가 발생했어요"
}
