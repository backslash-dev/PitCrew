package ai.pitcrew.app.errand

import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.platform.WaypointStatus
import kotlin.math.abs

/** 주문한 픽업 매장에 거의 다 왔는지 판단한다. 3.0.5 SDK에는 경유지 도착 이벤트가 없어 남은 시간·거리로 본다. */
object PickupArrival {
    const val NEAR_SECONDS = 180
    const val NEAR_METERS = 800
    private const val SAME_PLACE_DEGREES = 0.002

    fun due(errands: List<Errand>, waypoints: List<WaypointStatus>): List<Errand> =
        waiting(errands).filter { errand ->
            val store = (errand.detail as ErrandDetail.Pickup).store
            waypoints.any { wp ->
                val samePlace = wp.name == store.displayName ||
                    (abs(wp.latitude - store.latitude) < SAME_PLACE_DEGREES && abs(wp.longitude - store.longitude) < SAME_PLACE_DEGREES)
                samePlace && (wp.remainTimeSeconds in 0..NEAR_SECONDS || wp.remainDistanceMeters in 0..NEAR_METERS)
            }
        }

    /** 경유지까지 추가했고 아직 안내하지 않은 픽업. */
    fun waiting(errands: List<Errand>): List<Errand> = errands.filter {
        it.detail is ErrandDetail.Pickup && it.state == ErrandState.COMPLETED && it.arrivalAnnouncedAt == null
    }

    fun message(errand: Errand): String {
        val store = (errand.detail as ErrandDetail.Pickup).store
        return "곧 ${store.displayName} 도착이에요. 주문한 메뉴를 찾아가세요."
    }
}
