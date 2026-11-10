package ai.pitcrew.app.ui.errand

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.testing.FakeWaypoints
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.StepState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ErrandTextTest {
    private val now = LocalDateTime.of(2026, 11, 5, 23, 10)
    private val late = ErrandDetail.LateArrival(
        reservationId = "r1",
        placeName = "온담 한식당",
        appointment = LocalDateTime.of(2026, 11, 5, 23, 40),
        estimatedArrival = LocalDateTime.of(2026, 11, 5, 23, 54),
        delayMinutes = 14,
        proposedTime = LocalTime.of(0, 0),
        companions = listOf(Contact("c1", "김민수", "010-0000-0000"), Contact("c2", "이서연", "010-0000-0001")),
    )
    private val pickup = ErrandDetail.Pickup("아메리카노", MockStoreCatalog.DEFAULT_STORES.first(), LocalTime.of(23, 35))

    @Test
    fun `승인 전에는 진행·결과 문장이 없다`() {
        val errand = ErrandRunner(MockStepExecutor(FakeWaypoints { true }), now = { now }).propose(late)
        assertNull(errand.progressLine())
        assertNull(errand.outcomeLine())
    }

    @Test
    fun `진행 중이면 지금 단계와 순서를 보여준다`() {
        val errand = ErrandRunner(MockStepExecutor(FakeWaypoints { true }), now = { now }).propose(late)
        val inProgress = errand.copy(
            state = ErrandState.IN_PROGRESS,
            steps = errand.steps.mapIndexed { i, s ->
                when (i) {
                    0, 1 -> s.copy(state = StepState.DONE)
                    2 -> s.copy(state = StepState.CURRENT)
                    else -> s
                }
            },
        )
        assertEquals("00:00에 확정 중 (2/3)", inProgress.progressLine())
    }

    @Test
    fun `완료하면 결과를 문장으로 말한다`() = runTest {
        val service = ErrandRunner(MockStepExecutor(FakeWaypoints { true }), now = { now }, stepDelayMillis = 0)
        val done = service.approve(service.propose(late).id, ApprovalChannel.TOUCH)
        assertEquals("예약을 00:00에 다시 잡고 김민수, 이서연에게 알렸어요", done.outcomeLine())
        assertEquals("온담 한식당에 14분 늦어요", done.title())
    }

    @Test
    fun `실패하면 실패한 단계를 말한다`() = runTest {
        val service = ErrandRunner(MockStepExecutor(FakeWaypoints { false }), now = { now }, stepDelayMillis = 0)
        val failed = service.approve(service.propose(pickup).id, ApprovalChannel.VOICE)
        assertEquals("세모 커피 역삼점 경유지 추가에 실패했어요. 내비게이션에 경유지를 넣지 못했어요", failed.outcomeLine())
    }

    @Test
    fun `거절하면 진행하지 않았다고 말한다`() {
        val service = ErrandRunner(MockStepExecutor(FakeWaypoints { true }), now = { now })
        val id = service.propose(pickup).id
        service.decline(id)
        assertEquals("거절해서 진행하지 않았어요", service.errands.value.single().outcomeLine())
    }

    @Test
    fun `일행이 셋 이상이면 첫 사람 외 몇 명으로 줄인다`() {
        val many = late.companions + Contact("c3", "박준호", "010-0000-0002")
        assertEquals("김민수 외 2명", companionNames(many))
        val followUp = ErrandRunner(MockStepExecutor(FakeWaypoints { true }), now = { now }).propose(late.copy(followUp = true, companions = emptyList()))
        assertEquals("온담 한식당에 14분 더 늦어요", followUp.title())
        assertEquals("예약을 00:00에 다시 잡아요", followUp.summary())
    }
}
