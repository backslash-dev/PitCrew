package ai.pitcrew.app.errand

import ai.pitcrew.app.data.mock.MockErrandService
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoStatus
import ai.pitcrew.app.platform.Destination
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class PickupOnRouteTest {
    private val now = LocalDateTime.of(2026, 11, 5, 18, 0)
    private val route = Destination("집", remainTimeSeconds = 30 * 60, remainDistanceMeters = 12_000)
    private var waypointResult = true
    private val errands = MockErrandService(addWaypoint = { waypointResult }, now = { now }, stepDelayMillis = 0)
    private val handler = PickupOnRouteHandler(PickupUseCase({ route }, MockStoreCatalog(), errands) { now })

    private fun request(vararg params: Pair<String, Any>) =
        GleoRequest(GleoFunction.ORDER_PICKUP_ON_ROUTE, JSONObject(params.toMap()))

    @Test
    fun `품목이 없으면 되묻는다`() = runTest {
        assertEquals(GleoStatus.ASKING, handler.handle(request()).status)
    }

    @Test
    fun `경로가 없으면 실패로 응답한다`() = runTest {
        val noRoute = PickupOnRouteHandler(PickupUseCase({ null }, MockStoreCatalog(), errands) { now })
        assertEquals(GleoStatus.FAIL, noRoute.handle(request("item" to "아메리카노")).status)
    }

    @Test
    fun `우회가 가장 짧은 매장으로 제안하고 되묻는다`() = runTest {
        val response = handler.handle(request("item" to "아메리카노"))
        assertEquals(GleoStatus.ASKING, response.status)
        assertEquals("세모 커피 역삼점", response.data.single()["store"])
        assertEquals(ErrandState.AWAITING_APPROVAL, errands.errands.value.single().state)
    }

    @Test
    fun `매장 이름을 말하면 그 매장을 찾는다`() = runTest {
        assertEquals("노을 베이커리 서초점", handler.handle(request("item" to "식빵", "storeName" to "노을 베이커리")).data.single()["store"])
    }

    @Test
    fun `승인하면 주문하고 경유지를 추가한다`() = runTest {
        assertEquals(GleoStatus.SUCCESS, handler.handle(request("item" to "아메리카노", "confirmed" to true)).status)
        assertEquals(ErrandState.COMPLETED, errands.errands.value.single().state)
    }

    @Test
    fun `경유지 추가에 실패하면 실패로 알린다`() = runTest {
        waypointResult = false
        assertEquals(GleoStatus.FAIL, handler.handle(request("item" to "아메리카노", "confirmed" to true)).status)
        assertEquals(ErrandState.FAILED, errands.errands.value.single().state)
    }

    @Test
    fun `픽업 시각은 매장 도착과 조리 중 늦은 쪽을 5분 단위로 올린다`() {
        val store = MockStoreCatalog.DEFAULT_STORES.first()
        // 경로 절반 15분 + 우회 2분 = 18:17 → 18:20, 조리 6분보다 늦음
        assertEquals(LocalTime.of(18, 20), PickupPlanner.readyAt(store, now, 30 * 60))
    }
}
