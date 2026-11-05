package ai.pitcrew.app.data

import ai.pitcrew.app.data.mock.MockErrandService
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.StepState
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class MockErrandServiceTest {
    private val now = LocalDateTime.of(2026, 11, 5, 18, 0)
    private val pickup = ErrandDetail.Pickup("아메리카노", MockStoreCatalog.DEFAULT_STORES.first(), LocalTime.of(18, 20))
    private val service = MockErrandService(addWaypoint = { true }, now = { now }, stepDelayMillis = 0)

    @Test
    fun `제안하면 승인 대기 단계부터 시작한다`() {
        val errand = service.propose(pickup)
        assertEquals(ErrandState.AWAITING_APPROVAL, errand.state)
        assertEquals(StepState.CURRENT, errand.steps.first().state)
        assertTrue(errand.steps.drop(1).all { it.state == StepState.PENDING })
    }

    @Test
    fun `같은 대상의 대기 중 제안은 새 제안으로 바뀐다`() {
        service.propose(pickup)
        service.propose(pickup.copy(item = "라테"))
        assertEquals("라테", (service.errands.value.single().detail as ErrandDetail.Pickup).item)
    }

    @Test
    fun `승인하면 모든 단계를 끝내고 승인 경로를 기록한다`() = runTest {
        val done = service.approve(service.propose(pickup).id, ApprovalChannel.TOUCH)
        assertEquals(ErrandState.COMPLETED, done.state)
        assertEquals(ApprovalChannel.TOUCH, done.approvedVia)
        assertTrue(done.steps.all { it.state == StepState.DONE })
    }

    @Test
    fun `음성과 화면 승인이 겹쳐도 한 번만 실행한다`() = runTest {
        val id = service.propose(pickup).id
        val a = async { service.approve(id, ApprovalChannel.VOICE) }
        val b = async { service.approve(id, ApprovalChannel.TOUCH) }
        a.await(); b.await()
        val errand = service.errands.value.single()
        assertEquals(ErrandState.COMPLETED, errand.state)
        assertEquals(1, errand.steps.count { it.title.endsWith("승인") })
    }

    @Test
    fun `거절하면 실행하지 않는다`() = runTest {
        val id = service.propose(pickup).id
        service.decline(id)
        assertEquals(ErrandState.DECLINED, service.approve(id, ApprovalChannel.VOICE).state)
    }
}
