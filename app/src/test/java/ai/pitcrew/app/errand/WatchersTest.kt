package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.MockChargerCatalog
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.NearbyRestaurant
import ai.pitcrew.app.domain.PastOrder
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.testing.FakeWaypoints
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class LateWatchTest {
    private val now = LocalDateTime.of(2026, 11, 5, 18, 40)
    private val dinner = Reservation("r1", "온담 한식당", LocalDateTime.of(2026, 11, 5, 19, 0), 3, emptyList())
    private val coffee = Reservation("r2", "세모 커피 판교점", LocalDateTime.of(2026, 11, 5, 19, 10), 2, emptyList())

    @Test
    fun `도착이 예약보다 5분 넘게 늦을 때만 고른다`() {
        assertEquals(dinner, LateWatch.candidate(listOf(dinner), "강남역", now, 26 * 60, emptySet()))
        assertNull(LateWatch.candidate(listOf(dinner), "강남역", now, 23 * 60, emptySet()))
    }

    @Test
    fun `목적지 이름이 예약 장소와 겹치면 그 예약을 먼저 본다`() {
        assertEquals(coffee, LateWatch.candidate(listOf(dinner, coffee), "세모커피 판교점", now, 60 * 60, emptySet()))
        assertEquals(dinner, LateWatch.candidate(listOf(dinner, coffee), "강남역", now, 60 * 60, emptySet()))
    }

    @Test
    fun `이미 물은 예약은 시각이 바뀌기 전까지 다시 고르지 않는다`() {
        val prompted = setOf(LateWatch.key(dinner))
        assertNull(LateWatch.candidate(listOf(dinner), "강남역", now, 40 * 60, prompted))
        val moved = dinner.copy(time = LocalDateTime.of(2026, 11, 5, 19, 10))
        assertEquals(moved, LateWatch.candidate(listOf(moved), "강남역", now, 40 * 60, prompted))
    }

    @Test
    fun `세 시간 뒤 예약이나 30분 넘게 지난 예약은 보지 않는다`() {
        val later = dinner.copy(time = now.plusHours(4))
        val past = dinner.copy(time = now.minusMinutes(40))
        assertNull(LateWatch.candidate(listOf(later, past), "강남역", now, 5 * 60 * 60, emptySet()))
    }
}

class RoutinePlannerTest {
    private val now = LocalDateTime.of(2026, 11, 5, 8, 20)
    private fun order(daysAgo: Long, time: LocalTime, item: String = "아메리카노", cancelled: Boolean = false) =
        PastOrder("s-semo-yeoksam", "세모 커피 역삼점", item, now.toLocalDate().minusDays(daysAgo).atTime(time), cancelled)

    @Test
    fun `같은 시간대에 이틀 넘게 시킨 메뉴를 루틴으로 본다`() {
        val routine = RoutinePlanner.find(listOf(order(1, LocalTime.of(8, 30)), order(2, LocalTime.of(7, 50)), order(3, LocalTime.of(13, 0))), now)
        assertEquals(Routine("s-semo-yeoksam", "세모 커피 역삼점", "아메리카노", 2), routine)
    }

    @Test
    fun `하루만 시켰거나 취소했거나 시간대가 다르면 루틴이 아니다`() {
        assertNull(RoutinePlanner.find(listOf(order(1, LocalTime.of(8, 30)), order(1, LocalTime.of(8, 40))), now))
        assertNull(RoutinePlanner.find(listOf(order(1, LocalTime.of(8, 30)), order(2, LocalTime.of(8, 30), cancelled = true)), now))
        assertNull(RoutinePlanner.find(listOf(order(1, LocalTime.of(12, 0)), order(2, LocalTime.of(12, 0))), now))
        assertNull(RoutinePlanner.find(listOf(order(20, LocalTime.of(8, 30)), order(21, LocalTime.of(8, 30))), now))
    }

    @Test
    fun `오늘 이미 그 매장에서 픽업했으면 다시 권하지 않는다`() {
        val routine = Routine("s-semo-yeoksam", "세모 커피 역삼점", "아메리카노", 3)
        assertTrue(RoutinePlanner.alreadyToday(routine, listOf(order(0, LocalTime.of(7, 30))), emptyList(), now.toLocalDate()))
        assertFalse(RoutinePlanner.alreadyToday(routine, listOf(order(1, LocalTime.of(7, 30))), emptyList(), now.toLocalDate()))
    }
}

class ChargePlannerTest {
    private val now = LocalDateTime.of(2026, 11, 5, 18, 0)
    private val restaurants = listOf(
        NearbyRestaurant("a", "소복 국밥", 2, 15),
        NearbyRestaurant("b", "물결 우동", 4, 25),
    )

    @Test
    fun `목표 잔량까지 걸리는 시간을 충전기 출력과 차량 한계로 계산한다`() {
        assertEquals(18, ChargePlanner.chargeMinutes(18, 350))
        assertEquals(42, ChargePlanner.chargeMinutes(18, 100))
        assertEquals(10, ChargePlanner.chargeMinutes(75, 350))
        assertNull(ChargePlanner.chargeMinutes(80, 350))
        assertEquals(ChargePlanner.UNKNOWN_BATTERY_MINUTES, ChargePlanner.chargeMinutes(null, 350))
    }

    @Test
    fun `충전이 끝날 즈음 자리가 나는 식당을 고르고 없으면 대기가 짧은 곳`() {
        assertEquals("a", ChargePlanner.pickRestaurant(restaurants, 18)!!.id)
        assertEquals("b", ChargePlanner.pickRestaurant(restaurants, 41)!!.id)
        assertEquals("a", ChargePlanner.pickRestaurant(restaurants, 5)!!.id)
    }

    @Test
    fun `빈자리가 없는 충전소는 건너뛴다`() {
        val chargers = MockChargerCatalog.DEFAULT_CHARGERS
        assertEquals("ch-ieum-yangjae", ChargePlanner.pickCharger(chargers)!!.id)
        assertEquals("ch-green-seocho", ChargePlanner.pickCharger(listOf(chargers[0].copy(availableStalls = 0), chargers[1]))!!.id)
    }

    @Test
    fun `배터리가 충분하면 제안하지 않고 낮으면 줄서기 제안을 만든다`() = runTest {
        val errands = ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now })
        val route = Destination("강남역", 40 * 60, 20_000)
        val full = ChargeAndDineUseCase({ route }, MockChargerCatalog(), { 85 }, errands) { now }
        assertEquals(ChargeOutcome.NotNeeded(85), full.run())
        val low = ChargeAndDineUseCase({ route }, MockChargerCatalog(), { 18 }, errands) { now }
        val outcome = low.run(partySize = 2, origin = ErrandOrigin.SUGGESTED) as ChargeOutcome.AwaitingApproval
        val d = outcome.errand.detail as ErrandDetail.ChargeAndDine
        assertEquals("소복 국밥", d.restaurant.name)
        assertEquals(2, d.partySize)
        assertEquals(LocalTime.of(18, 24), d.arriveAt)
        assertEquals(ErrandOrigin.SUGGESTED, outcome.errand.origin)
    }
}
