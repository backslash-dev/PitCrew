package ai.pitcrew.app.errand

import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.PastOrder
import ai.pitcrew.app.domain.Reservation
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

object LateWatch {
    /** 1~2분 차이로 매번 묻지 않게 한다. */
    const val MIN_DELAY_MINUTES = 5L
    private val LOOK_AHEAD: Duration = Duration.ofHours(3)
    // 약속 시각이 조금 지났어도 아직 가는 중이면 대상이다
    private val LOOK_BEHIND: Duration = Duration.ofMinutes(30)

    /** 경로가 예약 장소로 가는 길이라고 가정한다(지각 대응과 같은 가정). */
    fun candidate(
        reservations: List<Reservation>,
        destinationName: String,
        now: LocalDateTime,
        remainSeconds: Int,
        prompted: Set<String>,
    ): Reservation? {
        val arrival = LateArrivalPlanner.arrivalAt(now, remainSeconds)
        val late = reservations.filter { r ->
            r.time.isAfter(now.minus(LOOK_BEHIND)) && !r.time.isAfter(now.plus(LOOK_AHEAD)) &&
                Duration.between(r.time, arrival).toMinutes() >= MIN_DELAY_MINUTES && key(r) !in prompted
        }
        return late.firstOrNull { it.matches(destinationName) } ?: late.minByOrNull { it.time }
    }

    /** 같은 예약이라도 시각이 바뀌면(한 번 미룬 뒤 또 늦으면) 다시 묻는다. */
    fun key(reservation: Reservation): String = "${reservation.id}@${reservation.time}"

    private fun Reservation.matches(destinationName: String): Boolean {
        val place = placeName.replace(" ", "")
        val destination = destinationName.replace(" ", "")
        return destination.isNotEmpty() && (place.contains(destination) || destination.contains(place))
    }
}

data class Routine(val storeId: String, val storeName: String, val item: String, val days: Int)

object RoutinePlanner {
    private const val MIN_DAYS = 2
    private const val LOOK_BACK_DAYS = 14L
    private const val TIME_WINDOW_MINUTES = 60L

    fun find(orders: List<PastOrder>, now: LocalDateTime): Routine? {
        val today = now.toLocalDate()
        val past = orders.filter { !it.cancelled && it.pickupAt.toLocalDate().isBefore(today) && !it.pickupAt.isBefore(now.minusDays(LOOK_BACK_DAYS)) }
        return past
            .filter { minutesApart(it.pickupAt.toLocalTime(), now.toLocalTime()) <= TIME_WINDOW_MINUTES }
            .groupBy { it.storeId to it.item }
            .map { (key, list) -> Routine(key.first, list.first().storeName, key.second, list.map { it.pickupAt.toLocalDate() }.distinct().size) }
            .filter { it.days >= MIN_DAYS }
            .maxByOrNull { it.days }
    }

    fun alreadyToday(routine: Routine, orders: List<PastOrder>, errands: List<Errand>, today: LocalDate): Boolean =
        orders.any { !it.cancelled && it.storeId == routine.storeId && it.pickupAt.toLocalDate() == today } ||
            errands.any { e ->
                e.detail is ErrandDetail.Pickup && e.createdAt.toLocalDate() == today &&
                    e.state in setOf(ErrandState.AWAITING_APPROVAL, ErrandState.IN_PROGRESS, ErrandState.COMPLETED)
            }

    fun key(routine: Routine, today: LocalDate): String = "$today/${routine.storeId}/${routine.item}"

    private fun minutesApart(a: LocalTime, b: LocalTime): Long {
        val diff = Duration.between(a, b).abs().toMinutes()
        return minOf(diff, 24 * 60 - diff)
    }
}
