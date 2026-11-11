package ai.pitcrew.app.data.mock

import ai.pitcrew.app.data.ChargerCatalog
import ai.pitcrew.app.data.OrderHistory
import ai.pitcrew.app.domain.Charger
import ai.pitcrew.app.domain.NearbyRestaurant
import ai.pitcrew.app.domain.PastOrder
import java.time.LocalDateTime

/** 서버 카탈로그와 같은 값을 쓴다. */
class MockChargerCatalog(private val chargers: List<Charger> = DEFAULT_CHARGERS) : ChargerCatalog {
    override suspend fun onRoute(): List<Charger> = chargers.sortedBy { it.detourMinutes }

    companion object {
        val DEFAULT_CHARGERS = listOf(
            Charger(
                "ch-ieum-yangjae", "이음차지 양재", 37.4846, 127.0342, detourMinutes = 3, powerKw = 350, availableStalls = 2,
                restaurants = listOf(
                    NearbyRestaurant("cr-sobok-yangjae", "소복 국밥", walkMinutes = 2, waitMinutes = 15),
                    NearbyRestaurant("cr-mulgyeol-yangjae", "물결 우동", walkMinutes = 4, waitMinutes = 25),
                ),
            ),
            Charger(
                "ch-green-seocho", "그린차지 서초", 37.4918, 127.0096, detourMinutes = 6, powerKw = 100, availableStalls = 4,
                restaurants = listOf(NearbyRestaurant("cr-dalbit-seocho", "달빛 분식", walkMinutes = 3, waitMinutes = 10)),
            ),
        )
    }
}

/** 최근 사흘 지금 시간대에 같은 메뉴를 픽업한 것으로 둔다. */
class MockOrderHistory(private val orders: List<PastOrder>) : OrderHistory {
    override suspend fun recent(): List<PastOrder> = orders

    companion object {
        fun routine(now: LocalDateTime): MockOrderHistory = MockOrderHistory(
            (1L..3L).map { daysAgo ->
                PastOrder("s-semo-yeoksam", "세모 커피 역삼점", "아메리카노", now.minusDays(daysAgo).plusMinutes(10), cancelled = false)
            },
        )
    }
}
