package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.InMemoryContactRepository
import ai.pitcrew.app.data.mock.InMemoryReservationRepository
import ai.pitcrew.app.data.mock.MockChargerCatalog
import ai.pitcrew.app.data.mock.MockMailService
import ai.pitcrew.app.data.mock.MockMeetupService
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.data.mock.SampleData
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Mail
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoStatus
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.testing.FakeWaypoints
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class DriverRequestHandlerTest {
    private val now = LocalDateTime.of(2026, 11, 11, 17, 0)
    private val route = Destination("강남역", 40 * 60, 13_000)
    private val reservations = InMemoryReservationRepository(emptyList())
    private val meetups = MockMeetupService({ reservations.reservations.value }) { now }
    private val mailbox = MockMailService(listOf(Mail("m1", "정하윤", "h@example.com", "회의 시간", "회의를 11시로 옮겨도 될지", now.minusMinutes(3))))
    private val errands = ErrandRunner(MockStepExecutor(FakeWaypoints(), meetups, reservations, mailbox), now = { now })
    private val contacts = InMemoryContactRepository()
    private val pickup = PickupUseCase({ route }, MockStoreCatalog(), errands) { now }
    private val handler = DriverRequestHandler(
        interpreter = RuleBasedInterpreter({ emptyList() }, { SampleData.contacts.map { it.name } }),
        errands = errands,
        undo = CancelLastErrandHandler(UndoUseCase(errands) { now }),
        lateArrival = LateArrivalHandler(LateArrivalUseCase({ route }, errands, reservations, contacts) { now }),
        pickup = PickupOnRouteHandler(pickup),
        meetup = MeetupUseCase(contacts, meetups, { null }, errands) { now },
        mail = MailReplyUseCase(mailbox, errands),
        charging = ChargeAndDineUseCase({ route }, MockChargerCatalog(), { 18 }, errands) { now },
    )

    private fun request(text: String, confirmed: Boolean = false) =
        GleoRequest(GleoFunction.HANDLE_PITCREW_REQUEST, JSONObject().put("request", text).put("confirmed", confirmed))

    @Test
    fun `약속 조율은 장소를 묻고 확인하면 예약까지 끝낸다`() = runTest {
        val asked = handler.handle(request("피트크루 앱으로 민수랑 중간에서 보자"))
        assertEquals(GleoStatus.ASKING, asked.status)
        assertTrue(asked.message.contains("김민수"))
        val done = handler.handle(request("응, 피트크루 앱으로 민수랑 중간에서 만나", confirmed = true))
        assertEquals(GleoStatus.SUCCESS, done.status)
        assertEquals(GleoFunction.HANDLE_PITCREW_REQUEST, done.function)
        assertEquals(ErrandState.COMPLETED, errands.errands.value.single().state)
        assertEquals(1, reservations.reservations.value.size)
    }

    @Test
    fun `묻지 않은 일에 확인을 붙여 보내면 바로 실행하지 않고 먼저 묻는다`() = runTest {
        assertEquals(GleoStatus.ASKING, handler.handle(request("민수랑 중간에서 보자", confirmed = true)).status)
        assertEquals(ErrandState.AWAITING_APPROVAL, errands.errands.value.single().state)
    }

    @Test
    fun `메일 답장은 초안만 만들고 확인을 받아도 보내지 않는다`() = runTest {
        val drafted = handler.handle(request("방금 메일 괜찮다고 답장해"))
        assertEquals(GleoStatus.SUCCESS, drafted.status)
        handler.handle(request("응 보내", confirmed = true))
        val errand = errands.errands.value.single()
        assertTrue(errand.detail is ErrandDetail.MailReply)
        assertEquals(ErrandState.AWAITING_APPROVAL, errand.state)
    }

    @Test
    fun `되돌리기와 픽업도 같은 함수로 받는다`() = runTest {
        assertEquals(GleoStatus.ASKING, handler.handle(request("가는 길에 아메리카노 픽업 주문해줘")).status)
        assertEquals(GleoStatus.SUCCESS, handler.handle(request("응", confirmed = true)).status)
        val ordered = errands.errands.value.single()
        assertEquals(ErrandState.COMPLETED, ordered.state)
        assertEquals(GleoStatus.ASKING, handler.handle(request("방금 거 취소해")).status)
        assertEquals(GleoStatus.SUCCESS, handler.handle(request("방금 거 취소해", confirmed = true)).status)
        assertEquals(ErrandState.CANCELLED, errands.errands.value.single().state)
    }

    @Test
    fun `알아들을 수 없거나 빈 요청이면 되묻거나 실패로 답한다`() = runTest {
        assertEquals(GleoStatus.FAIL, handler.handle(request("오늘 날씨 어때")).status)
        assertEquals(GleoStatus.ASKING, handler.handle(GleoRequest(GleoFunction.HANDLE_PITCREW_REQUEST, JSONObject())).status)
    }
}
