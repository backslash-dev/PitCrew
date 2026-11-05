package ai.pitcrew.app.gleo

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout

data class GleoExchange(val function: GleoFunction, val params: String, val response: GleoResponse)

class GleoActionDispatcher(
    private val handlers: Map<GleoFunction, GleoActionHandler>,
    private val timeoutMillis: Long = RESPONSE_BUDGET_MILLIS,
) {
    private val _lastExchange = MutableStateFlow<GleoExchange?>(null)
    val lastExchange: StateFlow<GleoExchange?> = _lastExchange.asStateFlow()

    suspend fun dispatch(request: GleoRequest): GleoResponse {
        val response = try {
            val handler = handlers[request.function]
                ?: return record(request, GleoResponse.fail(request.function, "This action is not supported."))
            withTimeout(timeoutMillis) { handler.handle(request) }
        } catch (e: TimeoutCancellationException) {
            GleoResponse.fail(request.function, "A timeout occurred while processing the request.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "${request.function} failed", e)
            GleoResponse.fail(request.function, "An unexpected internal error occurred.")
        }
        return record(request, response)
    }

    private fun record(request: GleoRequest, response: GleoResponse): GleoResponse {
        _lastExchange.value = GleoExchange(request.function, request.params.toString(), response)
        return response
    }

    companion object {
        // Gleo는 7초 후 액션을 취소한다. 콜백 전송 여유를 남긴다
        const val RESPONSE_BUDGET_MILLIS = 5_000L
        private const val TAG = "GleoActionDispatcher"
    }
}
