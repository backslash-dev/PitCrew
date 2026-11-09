package ai.pitcrew.app.gleo

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GleoActionDispatcherTest {
    private val request = GleoRequest(GleoFunction.NOTIFY_LATE_ARRIVAL, JSONObject())

    @Test
    fun `응답 예산을 넘기면 fail로 응답한다`() = runTest {
        val dispatcher = GleoActionDispatcher(mapOf(GleoFunction.NOTIFY_LATE_ARRIVAL to GleoActionHandler { awaitCancellation() }))
        val response = dispatcher.dispatch(request)
        assertEquals(GleoStatus.FAIL, response.status)
        assertTrue(response.message.startsWith("The request took too long"))
    }

    @Test
    fun `처리기 예외는 fail로 바꾸고 기록한다`() = runTest {
        val dispatcher = GleoActionDispatcher(mapOf(GleoFunction.NOTIFY_LATE_ARRIVAL to GleoActionHandler { error("boom") }))
        assertEquals(GleoStatus.FAIL, dispatcher.dispatch(request).status)
        assertEquals(GleoStatus.FAIL, dispatcher.lastExchange.value?.response?.status)
    }

    @Test
    fun `처리기가 없는 함수는 fail로 응답한다`() = runTest {
        assertEquals(GleoStatus.FAIL, GleoActionDispatcher(emptyMap()).dispatch(request).status)
    }
}
