package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ChargerCatalog
import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.domain.Charger
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.NearbyRestaurant
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.platform.SdkUnavailableException
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

object ChargePlanner {
    const val TARGET_PERCENT = 80
    const val LOW_PERCENT = 20
    const val UNKNOWN_BATTERY_MINUTES = 30
    private const val MIN_MINUTES = 10

    // 배터리 용량·최대 충전 출력은 시연 차량(중형 전기 SUV) 기준 가정값이다. 충전 곡선 때문에 평균 출력은 최대의 70%로 본다
    private const val BATTERY_KWH = 77.4
    private const val VEHICLE_MAX_KW = 230
    private const val AVERAGE_FACTOR = 0.7
    private const val WAIT_SLACK_MINUTES = 5

    /** [TARGET_PERCENT]까지 걸리는 분. 이미 넘었으면 null, 잔량을 모르면 [UNKNOWN_BATTERY_MINUTES]. */
    fun chargeMinutes(percent: Int?, powerKw: Int): Int? {
        if (percent == null) return UNKNOWN_BATTERY_MINUTES
        if (percent >= TARGET_PERCENT) return null
        val kwh = (TARGET_PERCENT - percent) / 100.0 * BATTERY_KWH
        val kw = minOf(powerKw, VEHICLE_MAX_KW) * AVERAGE_FACTOR
        return maxOf(MIN_MINUTES, ceil(kwh / kw * 60).toInt())
    }

    /** 빈자리가 있고 걸어갈 식당이 있는 충전소 중 우회가 가장 짧은 곳. */
    fun pickCharger(chargers: List<Charger>): Charger? =
        chargers.filter { it.availableStalls > 0 && it.restaurants.isNotEmpty() }.minByOrNull { it.detourMinutes }

    /** 충전이 끝날 즈음 자리가 나는 식당. 그런 곳이 없으면 대기가 가장 짧은 곳. */
    fun pickRestaurant(restaurants: List<NearbyRestaurant>, chargeMinutes: Int): NearbyRestaurant? =
        restaurants.filter { it.waitMinutes <= chargeMinutes + WAIT_SLACK_MINUTES }.maxByOrNull { it.waitMinutes }
            ?: restaurants.minByOrNull { it.waitMinutes }

    /** 충전소가 경로 중간쯤에 있다고 보고 도착 시각을 분 단위로 올린다. 픽업 매장과 같은 가정. */
    fun arriveAt(charger: Charger, now: LocalDateTime, remainSeconds: Int): LocalTime =
        now.plusSeconds(remainSeconds.coerceAtLeast(0) / 2L).plusMinutes(charger.detourMinutes.toLong())
            .truncatedTo(ChronoUnit.MINUTES).plusMinutes(1).toLocalTime()
}

sealed interface ChargeOutcome {
    data object NoRoute : ChargeOutcome
    data object NavigationUnavailable : ChargeOutcome
    data class NotNeeded(val batteryPercent: Int) : ChargeOutcome
    data object NoCharger : ChargeOutcome
    data class AwaitingApproval(val errand: Errand) : ChargeOutcome
}

/** 충전하는 동안 근처 식당에 줄을 서 둔다. 앱 음성 입력과 배터리 감시가 같이 쓴다. */
class ChargeAndDineUseCase(
    private val fetchDestination: suspend () -> Destination?,
    private val chargers: ChargerCatalog,
    private val batteryPercent: () -> Int?,
    private val errands: ErrandService,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    suspend fun run(partySize: Int = 1, origin: ErrandOrigin = ErrandOrigin.REQUESTED): ChargeOutcome {
        val destination = try {
            fetchDestination()
        } catch (e: SdkUnavailableException) {
            return ChargeOutcome.NavigationUnavailable
        } ?: return ChargeOutcome.NoRoute
        val percent = batteryPercent()
        val charger = ChargePlanner.pickCharger(chargers.onRoute()) ?: return ChargeOutcome.NoCharger
        val minutes = ChargePlanner.chargeMinutes(percent, charger.powerKw) ?: return ChargeOutcome.NotNeeded(percent!!)
        val restaurant = ChargePlanner.pickRestaurant(charger.restaurants, minutes) ?: return ChargeOutcome.NoCharger
        val errand = errands.propose(
            ErrandDetail.ChargeAndDine(
                charger = charger,
                restaurant = restaurant,
                partySize = partySize.coerceIn(1, 20),
                batteryPercent = percent,
                chargeMinutes = minutes,
                arriveAt = ChargePlanner.arriveAt(charger, now(), destination.remainTimeSeconds),
            ),
            origin,
        )
        return ChargeOutcome.AwaitingApproval(errand)
    }
}
