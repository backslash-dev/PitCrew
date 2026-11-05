package ai.pitcrew.app.data.remote

import ai.pitcrew.app.data.StepResult
import ai.pitcrew.app.data.UndoResult
import ai.pitcrew.app.data.mock.MockChargerCatalog
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.testing.FakeWaypoints
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class RemoteServerTest {
    private val server = MockWebServer()
    private val seoul = ZoneId.of("Asia/Seoul")
    private lateinit var api: PitCrewApi

    @Before
    fun setUp() {
        server.start()
        api = PitCrewApi(server.url("/").toString(), apiToken = "t".repeat(20))
    }

    @After
    fun tearDown() = server.shutdown()

    private fun respond(code: Int, body: String) = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun `토큰을 붙이고 서버 오류 문장을 그대로 전한다`() = runTest {
        respond(409, """{"error":{"code":"ORDER_REJECTED","message":"조리 시간보다 이른 픽업은 받을 수 없습니다"}}""")
        val error = runCatching { api.post("v1/orders", JSONObject()) }.exceptionOrNull() as ApiException
        assertEquals(409, error.status)
        assertEquals("ORDER_REJECTED", error.code)
        assertEquals("조리 시간보다 이른 픽업은 받을 수 없습니다", error.userMessage())
        assertEquals("Bearer ${"t".repeat(20)}", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `연결이 안 되면 사용자 문장으로 바꾼다`() = runTest {
        server.shutdown()
        val error = runCatching { api.get("v1/stores") }.exceptionOrNull()!!
        assertEquals("서버에 연결하지 못했어요", error.userMessage())
    }

    @Test
    fun `시각은 오프셋 포함 ISO로 보내고 받은 UTC를 기기 시간대로 바꾼다`() {
        assertEquals("2026-11-05T19:00:00+09:00", LocalDateTime.of(2026, 11, 5, 19, 0).toInstantString(seoul))
        assertEquals(LocalDateTime.of(2026, 11, 5, 19, 30), "2026-11-05T10:30:00.000Z".toLocalDateTime(seoul))
    }

    private val now = LocalDateTime.of(2026, 11, 5, 23, 10)
    private val late = ErrandDetail.LateArrival(
        reservationId = "8a1d2c3b-4e5f-4a6b-8c7d-9e0f1a2b3c01",
        placeName = "온담 한식당",
        appointment = LocalDateTime.of(2026, 11, 5, 23, 40),
        estimatedArrival = LocalDateTime.of(2026, 11, 5, 23, 57),
        delayMinutes = 17,
        proposedTime = LocalTime.of(0, 0),
        companions = listOf(Contact("5f3c1b7e-2a4d-4c1e-9b8a-1d2e3f4a5b01", "김민수", "010-2481-3307")),
    )

    private fun errand(detail: ErrandDetail) = Errand("e1", now, ErrandState.IN_PROGRESS, detail, emptyList(), approvedVia = ApprovalChannel.TOUCH)

    @Test
    fun `자정을 넘는 변경은 다음 날 시각으로 요청하고 결과가 같아야 성공이다`() = runTest {
        val target = LocalDateTime.of(2026, 11, 6, 0, 0)
        respond(200, """{"reservedAt":"${target.toInstantString()}","changeId":"c-1"}""")
        val executor = RemoteStepExecutor(api, reloadReservations = {}, waypoints = FakeWaypoints()) { now }
        assertEquals(StepResult.Recorded("c-1"), executor.execute(errand(late), 1))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(target.toInstantString(), body.getString("reservedAt"))
    }

    @Test
    fun `알림이 일부 실패하면 몇 명이 실패했는지 알린다`() = runTest {
        respond(200, """{"results":[{"contactId":"a","name":"김민수","status":"FAILED"}]}""")
        val executor = RemoteStepExecutor(api, reloadReservations = {}, waypoints = FakeWaypoints()) { now }
        assertEquals(StepResult.Failed("1명에게 알림을 보내지 못했어요"), executor.execute(errand(late), 3))
        assertTrue(server.takeRequest().body.readUtf8().contains("00:00에 다시 잡았어요"))
    }

    @Test
    fun `주문을 거절하면 서버 사유가 실패 사유가 된다`() = runTest {
        respond(409, """{"error":{"code":"ORDER_REJECTED","message":"조리 시간보다 이른 픽업은 받을 수 없습니다"}}""")
        val pickup = ErrandDetail.Pickup("아메리카노", MockStoreCatalog.DEFAULT_STORES.first(), LocalTime.of(23, 30))
        val executor = RemoteStepExecutor(api, reloadReservations = {}, waypoints = FakeWaypoints()) { now }
        assertEquals(StepResult.Failed("조리 시간보다 이른 픽업은 받을 수 없습니다"), executor.execute(errand(pickup), 1))
    }

    @Test
    fun `예약 되돌리기는 변경을 무르고 일행에게 정정 안내를 보낸다`() = runTest {
        respond(200, """{"reservedAt":"${late.appointment.toInstantString()}"}""")
        respond(200, """{"results":[{"contactId":"a","name":"김민수","status":"SENT"}]}""")
        var reloaded = 0
        val executor = RemoteStepExecutor(api, reloadReservations = { reloaded++ }, waypoints = FakeWaypoints()) { now }
        assertEquals(UndoResult.Undone(), executor.undo(errand(late).copy(reference = "c-1")))
        assertEquals("/v1/reservation-changes/c-1/revert", server.takeRequest().path)
        assertTrue(server.takeRequest().body.readUtf8().contains("앞서 보낸 안내는 취소됐어요. 온담 한식당 예약은 23:40 그대로예요."))
        assertEquals(1, reloaded)
    }

    @Test
    fun `정정 안내를 못 보내도 예약은 되돌린 것으로 보고 안내를 남긴다`() = runTest {
        respond(200, """{"reservedAt":"${late.appointment.toInstantString()}"}""")
        respond(500, """{"error":{"code":"INTERNAL","message":"서버 오류가 발생했습니다"}}""")
        val executor = RemoteStepExecutor(api, reloadReservations = {}, waypoints = FakeWaypoints()) { now }
        assertEquals(
            UndoResult.Undone("예약은 되돌렸지만 일행 1명에게 정정 안내를 보내지 못했어요"),
            executor.undo(errand(late).copy(reference = "c-1")),
        )
    }

    @Test
    fun `서버 기록이 없으면 되돌리지 않는다`() = runTest {
        val executor = RemoteStepExecutor(api, reloadReservations = {}, waypoints = FakeWaypoints()) { now }
        assertEquals(UndoResult.Failed("서버 기록이 없어 되돌릴 수 없어요"), executor.undo(errand(late)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `충전 줄서기는 줄서기 id를 남기고 취소할 때 경유지도 뺀다`() = runTest {
        val charger = MockChargerCatalog.DEFAULT_CHARGERS.first()
        val detail = ErrandDetail.ChargeAndDine(charger, charger.restaurants.first(), 2, 18, 18, LocalTime.of(23, 30))
        respond(201, """{"id":"w-1","status":"WAITING"}""")
        respond(200, """{"id":"w-1","status":"CANCELLED"}""")
        val waypoints = FakeWaypoints()
        val executor = RemoteStepExecutor(api, reloadReservations = {}, waypoints = waypoints) { now }
        assertEquals(StepResult.Recorded("w-1"), executor.execute(errand(detail), 1))
        assertEquals(StepResult.Done, executor.execute(errand(detail), 2))
        assertEquals(UndoResult.Undone(), executor.undo(errand(detail).copy(reference = "w-1")))
        assertEquals(listOf(charger.name), waypoints.added)
        assertEquals(listOf(charger.name), waypoints.removed)
        assertEquals("""{"restaurantId":"cr-sobok-yangjae","partySize":2}""", server.takeRequest().body.readUtf8())
        assertEquals("/v1/waitlists/w-1/cancel", server.takeRequest().path)
    }
}
