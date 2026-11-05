package ai.pitcrew.app.errand

import ai.pitcrew.app.data.mock.InMemoryContactRepository
import ai.pitcrew.app.data.mock.InMemoryReservationRepository
import ai.pitcrew.app.data.mock.MockErrandService
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoStatus
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.platform.SdkUnavailableException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class LateArrivalPlannerTest {
    private val now = LocalDateTime.of(2026, 11, 5, 18, 40, 0)

    @Test
    fun `늦으면 10분 단위로 올린 새 시간을 제안한다`() {
        val plan = LateArrivalPlanner.plan(LocalTime.of(19, 0), now, remainSeconds = 43 * 60 + 10)
        assertEquals(23, plan.delayMinutes)
        assertEquals(LocalTime.of(19, 30), plan.proposedTime)
    }

    @Test
    fun `도착 시각은 분 단위로 반올림해 제안과 어긋나지 않는다`() {
        val early = LateArrivalPlanner.plan(LocalTime.of(19, 0), now, remainSeconds = 50 * 60 + 20)
        assertEquals(LocalDateTime.of(2026, 11, 5, 19, 30), early.estimatedArrival)
        assertEquals(LocalTime.of(19, 30), early.proposedTime)
        val late = LateArrivalPlanner.plan(LocalTime.of(19, 0), now, remainSeconds = 50 * 60 + 30)
        assertEquals(LocalDateTime.of(2026, 11, 5, 19, 31), late.estimatedArrival)
        assertEquals(LocalTime.of(19, 40), late.proposedTime)
    }

    @Test
    fun `도착 시각이 10분 단위면 그대로 제안한다`() {
        val plan = LateArrivalPlanner.plan(LocalTime.of(19, 0), now, remainSeconds = 40 * 60)
        assertEquals(LocalTime.of(19, 20), plan.proposedTime)
    }

    @Test
    fun `제때 도착하면 제안하지 않는다`() {
        val plan = LateArrivalPlanner.plan(LocalTime.of(19, 0), now, remainSeconds = 15 * 60)
        assertFalse(plan.isLate)
        assertNull(plan.proposedTime)
    }

    @Test
    fun `자정을 넘는 약속은 다음 날로 본다`() {
        val lateNight = LocalDateTime.of(2026, 11, 5, 23, 50)
        val plan = LateArrivalPlanner.plan(LocalTime.of(0, 10), lateNight, remainSeconds = 30 * 60)
        assertEquals(LocalDateTime.of(2026, 11, 6, 0, 10), plan.appointment)
        assertEquals(10, plan.delayMinutes)
    }

    @Test
    fun `오전 오후가 모호하면 지금과 가까운 쪽으로 본다`() {
        val evening = LocalDateTime.of(2026, 11, 5, 21, 41)
        assertEquals(LocalDateTime.of(2026, 11, 5, 22, 0), LateArrivalPlanner.plan(LocalTime.of(10, 0), evening, 3600).appointment)
        val morning = LocalDateTime.of(2026, 11, 5, 8, 30)
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 0), LateArrivalPlanner.plan(LocalTime.of(9, 0), morning, 3600).appointment)
        assertEquals(LocalDateTime.of(2026, 11, 5, 12, 0), LateArrivalPlanner.plan(LocalTime.of(0, 0), LocalDateTime.of(2026, 11, 5, 11, 30), 3600).appointment)
    }

    @Test
    fun `시각 형식은 24시간제 H_mm만 받는다`() {
        assertEquals(LocalTime.of(7, 5), parseClockTime("7:05"))
        assertEquals(LocalTime.of(19, 0), parseClockTime(" 19:00 "))
        assertNull(parseClockTime("24:00"))
        assertNull(parseClockTime("7시"))
        assertNull(parseClockTime(""))
    }
}

class LateArrivalHandlerTest {
    private val now = LocalDateTime.of(2026, 11, 5, 18, 40, 0)
    private val route = Destination("강남역", remainTimeSeconds = 45 * 60, remainDistanceMeters = 13_000)
    private val contacts = InMemoryContactRepository()
    private val reservations = InMemoryReservationRepository(
        listOf(Reservation("r1", "온담 한식당", LocalDateTime.of(2026, 11, 5, 19, 0), 3, listOf("c-minsu", "c-seoyeon"))),
    )
    private val errands = MockErrandService(addWaypoint = { true }, now = { now }, stepDelayMillis = 0)

    private fun handler(destination: suspend () -> Destination?) =
        LateArrivalHandler(LateArrivalUseCase(destination, errands, reservations, contacts) { now })

    private fun request(vararg params: Pair<String, String>) =
        GleoRequest(GleoFunction.NOTIFY_LATE_ARRIVAL, JSONObject(params.toMap()))

    @Test
    fun `약속 시각이 없으면 되묻는다`() = runTest {
        val response = handler { route }.handle(request())
        assertEquals(GleoStatus.ASKING, response.status)
        assertEquals("appointmentTime", response.data.single()["missingField"])
    }

    @Test
    fun `늦으면 예약과 맞춰 승인 대기 작업을 만들고 되묻는다`() = runTest {
        val response = handler { route }.handle(request("appointmentTime" to "19:00"))
        assertEquals(GleoStatus.ASKING, response.status)
        val data = JSONArray(response.toJson().getString("data")).getJSONObject(0)
        assertEquals("온담 한식당", data.getString("place"))
        assertEquals(25, data.getInt("delayMinutes"))
        assertEquals("19:30", data.getString("proposedTime"))
        val errand = errands.errands.value.single()
        assertEquals(ErrandState.AWAITING_APPROVAL, errand.state)
        assertEquals(2, (errand.detail as ErrandDetail.LateArrival).companions.size)
    }

    @Test
    fun `제때 도착하면 작업을 만들지 않는다`() = runTest {
        assertEquals(GleoStatus.SUCCESS, handler { route }.handle(request("appointmentTime" to "19:30")).status)
        assertTrue(errands.errands.value.isEmpty())
    }

    @Test
    fun `음성 승인하면 승인한 시간으로 실행하고 성공을 알린다`() = runTest {
        handler { route }.handle(request("appointmentTime" to "19:00"))
        val response = handler { route }.handle(request("appointmentTime" to "19:00", "confirmedTime" to "19:40"))
        assertEquals(GleoStatus.SUCCESS, response.status)
        val done = errands.errands.value.single()
        assertEquals(ErrandState.COMPLETED, done.state)
        assertEquals(ApprovalChannel.VOICE, done.approvedVia)
        assertEquals(LocalTime.of(19, 40), (done.detail as ErrandDetail.LateArrival).proposedTime)
    }

    @Test
    fun `경로가 없으면 실패로 응답한다`() = runTest {
        val response = handler { null }.handle(request("appointmentTime" to "19:00"))
        assertEquals(GleoStatus.FAIL, response.status)
        assertTrue(response.message.contains("no active navigation route"))
    }

    @Test
    fun `내비 SDK를 못 쓰면 실패로 응답한다`() = runTest {
        val response = handler { throw SdkUnavailableException("navigation", "bind failed") }.handle(request("appointmentTime" to "19:00"))
        assertEquals(GleoStatus.FAIL, response.status)
    }
}
