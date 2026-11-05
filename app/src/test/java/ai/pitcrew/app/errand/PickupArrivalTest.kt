package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.platform.WaypointStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class PickupArrivalTest {
    private val store = MockStoreCatalog.DEFAULT_STORES.first()
    private val service = ErrandRunner(MockStepExecutor { true }, now = { LocalDateTime.of(2026, 11, 5, 18, 0) }, stepDelayMillis = 0)

    private suspend fun orderedPickup() =
        service.approve(service.propose(ErrandDetail.Pickup("아메리카노", store, LocalTime.of(18, 20))).id, ApprovalChannel.TOUCH)

    private fun waypoint(seconds: Int, meters: Int, name: String = store.displayName) =
        WaypointStatus(name, store.latitude, store.longitude, seconds, meters)

    @Test
    fun `매장까지 3분 이내면 안내한다`() = runTest {
        orderedPickup()
        assertEquals(1, PickupArrival.due(service.errands.value, listOf(waypoint(170, 2_000))).size)
    }

    @Test
    fun `매장까지 800m 이내면 안내한다`() = runTest {
        orderedPickup()
        assertEquals(1, PickupArrival.due(service.errands.value, listOf(waypoint(600, 700))).size)
    }

    @Test
    fun `아직 멀면 안내하지 않는다`() = runTest {
        orderedPickup()
        assertTrue(PickupArrival.due(service.errands.value, listOf(waypoint(1_200, 5_000))).isEmpty())
    }

    @Test
    fun `이름이 달라도 좌표가 같으면 같은 매장으로 본다`() = runTest {
        orderedPickup()
        assertEquals(1, PickupArrival.due(service.errands.value, listOf(waypoint(100, 300, name = "세모커피"))).size)
    }

    @Test
    fun `한 번 안내한 픽업은 다시 안내하지 않는다`() = runTest {
        val errand = orderedPickup()
        service.markArrivalAnnounced(errand.id)
        assertTrue(PickupArrival.due(service.errands.value, listOf(waypoint(100, 300))).isEmpty())
    }

    @Test
    fun `승인 전 픽업은 안내 대상이 아니다`() {
        service.propose(ErrandDetail.Pickup("아메리카노", store, LocalTime.of(18, 20)))
        assertTrue(PickupArrival.due(service.errands.value, listOf(waypoint(100, 300))).isEmpty())
    }
}
