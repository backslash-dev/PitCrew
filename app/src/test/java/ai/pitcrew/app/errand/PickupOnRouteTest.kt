package ai.pitcrew.app.errand

import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoStatus
import ai.pitcrew.app.platform.Destination
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PickupOnRouteTest {
    private val route = Destination("집", remainTimeSeconds = 30 * 60, remainDistanceMeters = 12_000)
    private fun request(vararg params: Pair<String, String>) =
        GleoRequest(GleoFunction.ORDER_PICKUP_ON_ROUTE, JSONObject(params.toMap()))

    @Test
    fun `품목이 없으면 되묻는다`() = runTest {
        assertEquals(GleoStatus.ASKING, PickupOnRouteHandler { route }.handle(request()).status)
    }

    @Test
    fun `경로가 없으면 실패로 응답한다`() = runTest {
        assertEquals(GleoStatus.FAIL, PickupOnRouteHandler { null }.handle(request("item" to "아메리카노")).status)
    }

    @Test
    fun `매장 검색은 아직 지원하지 않는다고 응답한다`() = runTest {
        val response = PickupOnRouteHandler { route }.handle(request("item" to "아메리카노", "storeName" to " "))
        assertEquals(GleoStatus.FAIL, response.status)
        assertEquals(mapOf("item" to "아메리카노", "destination" to "집", "remainMinutes" to 30), response.data.single())
    }
}
