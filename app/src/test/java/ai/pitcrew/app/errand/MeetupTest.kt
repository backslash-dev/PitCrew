package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.InMemoryContactRepository
import ai.pitcrew.app.data.mock.InMemoryReservationRepository
import ai.pitcrew.app.data.mock.MockMeetupService
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.MeetupPlace
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.domain.StepState
import ai.pitcrew.app.platform.GeoPoint
import ai.pitcrew.app.testing.FakeWaypoints
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class MeetupPlannerTest {
    private val now = LocalDateTime.of(2026, 11, 11, 17, 3)

    @Test
    fun `두 사람 중 더 오래 걸리는 쪽이 가장 짧은 곳을 고른다`() {
        val near = MeetupPlace("a", "내 집 앞", 37.4979, 127.0276)
        val middle = MeetupPlace("b", "가운데", 37.4500, 127.0700)
        val place = MeetupPlanner.fairestPlace(listOf(near, middle), 37.4979, 127.0276, 37.3948, 127.1112)
        assertEquals("b", place!!.id)
    }

    @Test
    fun `둘 다 올 수 있는 가장 이른 10분 단위 시각으로 잡고 내 예약과 겹치면 30분씩 미룬다`() {
        assertEquals(LocalDateTime.of(2026, 11, 11, 17, 30), MeetupPlanner.meetAt(now, null, 25, now, emptyList()))
        val busy = listOf(LocalDateTime.of(2026, 11, 11, 18, 0))
        assertEquals(LocalDateTime.of(2026, 11, 11, 19, 30), MeetupPlanner.meetAt(now, null, 25, now, busy))
        assertEquals(LocalDateTime.of(2026, 11, 11, 20, 0), MeetupPlanner.meetAt(now, LocalDateTime.of(2026, 11, 11, 19, 55), 25, now, emptyList()))
    }

    @Test
    fun `시각만 말하면 지금 이후 가장 가까운 때로 본다`() {
        assertEquals(LocalDateTime.of(2026, 11, 11, 19, 0), nextOccurrence(LocalTime.of(7, 0), now))
        assertEquals(LocalDateTime.of(2026, 11, 12, 9, 0), nextOccurrence(LocalTime.of(9, 0), now.withHour(22)))
    }

    @Test
    fun `성을 빼고 불러도 찾지만 겹치면 찾지 못한 것으로 본다`() {
        val contacts = listOf(Contact("1", "김민수", ""), Contact("2", "이서연", ""))
        assertEquals("1", findContact(contacts, "민수")!!.id)
        assertEquals("2", findContact(contacts, "이서연")!!.id)
        assertNull(findContact(contacts + Contact("3", "박민수", ""), "민수"))
        assertNull(findContact(contacts, null))
    }
}

class MeetupUseCaseTest {
    private val now = LocalDateTime.of(2026, 11, 11, 17, 3)
    private val reservations = InMemoryReservationRepository(emptyList())
    private val meetups = MockMeetupService({ reservations.reservations.value }) { now }
    private val errands = ErrandRunner(MockStepExecutor(FakeWaypoints(), meetups, reservations), now = { now })
    private val useCase = MeetupUseCase(InMemoryContactRepository(), meetups, { GeoPoint(37.4979, 127.0276, 0f) }, errands) { now }

    @Test
    fun `상대에게 에이전트가 있으면 장소·시각까지 정해 묻고, 승인하면 예약하고 전달한다`() = runTest {
        val proposed = useCase.propose("김민수", null) as MeetupOutcome.AwaitingApproval
        val d = proposed.errand.detail as ErrandDetail.Meetup
        assertEquals(MeetupMode.AGENT, d.mode)
        assertTrue(d.place != null && d.meetAt != null)
        assertEquals(listOf("승인 대기", "${d.place!!.name} ${d.meetAt!!.toLocalTime().toClockTime()} 예약", "김민수님 에이전트에 확정 전달"), proposed.errand.steps.map { it.title })

        val done = (useCase.approve(proposed.errand.id, ApprovalChannel.TOUCH) as MeetupOutcome.Finished).errand
        assertEquals(ErrandState.COMPLETED, done.state)
        val booked = reservations.reservations.value.single()
        assertEquals(Reservation(done.reference!!, d.place!!.name, d.meetAt!!, 2, listOf("c-minsu")), booked)
    }

    @Test
    fun `에이전트가 없으면 승인 뒤 문자로 물어 받은 답으로 장소를 정하고 남은 단계 이름도 바꾼다`() = runTest {
        val proposed = useCase.propose("이서연", LocalTime.of(7, 0)) as MeetupOutcome.AwaitingApproval
        val before = proposed.errand.detail as ErrandDetail.Meetup
        assertEquals(MeetupMode.SMS, before.mode)
        assertNull(before.place)
        assertEquals("중간 장소 예약", proposed.errand.steps[2].title)

        val done = errands.approve(proposed.errand.id, ApprovalChannel.TOUCH)
        val after = done.detail as ErrandDetail.Meetup
        assertEquals(ErrandState.COMPLETED, done.state)
        assertEquals(LocalDateTime.of(2026, 11, 11, 19, 0), after.meetAt)
        assertEquals("${after.place!!.name} 19:00 예약", done.steps[2].title)
        assertTrue(done.steps.all { it.state == StepState.DONE })
        assertEquals(1, reservations.reservations.value.size)
    }

    @Test
    fun `되돌리면 만든 예약을 지운다`() = runTest {
        val proposed = useCase.propose("민수", null) as MeetupOutcome.AwaitingApproval
        val done = errands.approve(proposed.errand.id, ApprovalChannel.VOICE)
        assertEquals(ErrandState.CANCELLED, errands.undo(done.id, ApprovalChannel.TOUCH).state)
        assertTrue(reservations.reservations.value.isEmpty())
    }

    @Test
    fun `등록하지 않은 사람이나 이름 없이 말하면 찾지 못했다고 한다`() = runTest {
        assertEquals(MeetupOutcome.UnknownContact("최지훈"), useCase.propose("최지훈", null))
        assertEquals(MeetupOutcome.UnknownContact(null), useCase.propose(null, null))
        assertTrue(errands.errands.value.isEmpty())
    }

    @Test
    fun `상대가 그 시간에 안 되면 제안하지 않는다`() = runTest {
        val outcome = useCase.propose("김민수", LocalTime.of(4, 0))
        assertTrue(outcome is MeetupOutcome.Unavailable)
        assertTrue(errands.errands.value.isEmpty())
    }
}
