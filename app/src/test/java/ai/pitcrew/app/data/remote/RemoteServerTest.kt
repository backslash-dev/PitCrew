package ai.pitcrew.app.data.remote

import ai.pitcrew.app.data.StepResult
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
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

    private fun errand(detail: ErrandDetail) = Errand("e1", now, ErrandState.IN_PROGRESS, detail, emptyList(), ApprovalChannel.TOUCH)

    @Test
    fun `자정을 넘는 변경은 다음 날 시각으로 요청하고 결과가 같아야 성공이다`() = runTest {
        val target = LocalDateTime.of(2026, 11, 6, 0, 0)
        respond(200, """{"reservedAt":"${target.toInstantString()}"}""")
        val executor = RemoteStepExecutor(api, reloadReservations = {}, addWaypoint = { true }) { now }
        assertEquals(StepResult.Done, executor.execute(errand(late), 1))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(target.toInstantString(), body.getString("reservedAt"))
    }

    @Test
    fun `알림이 일부 실패하면 몇 명이 실패했는지 알린다`() = runTest {
        respond(200, """{"results":[{"contactId":"a","name":"김민수","status":"FAILED"}]}""")
        val executor = RemoteStepExecutor(api, reloadReservations = {}, addWaypoint = { true }) { now }
        assertEquals(StepResult.Failed("1명에게 알림을 보내지 못했어요"), executor.execute(errand(late), 3))
        assertTrue(server.takeRequest().body.readUtf8().contains("00:00에 다시 잡았어요"))
    }

    @Test
    fun `주문을 거절하면 서버 사유가 실패 사유가 된다`() = runTest {
        respond(409, """{"error":{"code":"ORDER_REJECTED","message":"조리 시간보다 이른 픽업은 받을 수 없습니다"}}""")
        val pickup = ErrandDetail.Pickup("아메리카노", MockStoreCatalog.DEFAULT_STORES.first(), LocalTime.of(23, 30))
        val executor = RemoteStepExecutor(api, reloadReservations = {}, addWaypoint = { true }) { now }
        assertEquals(StepResult.Failed("조리 시간보다 이른 픽업은 받을 수 없습니다"), executor.execute(errand(pickup), 1))
    }
}
