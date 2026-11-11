package ai.pitcrew.app.ui.timeline

import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.ui.format.hhmm
import java.time.Duration
import java.time.LocalDateTime

enum class StopKind { APPOINTMENT, PICKUP, CHARGE }

/** [lateMinutes]는 약속에 늦는 분(늦지 않으면 0). */
data class Stop(val kind: StopKind, val time: LocalDateTime, val name: String, val lateMinutes: Long = 0) {
    val late: Boolean get() = lateMinutes > 0
}

/** [fraction]은 0(지금)~1(도착) 위치. */
data class Mark(val fraction: Float, val stops: List<Stop>) {
    val cluster: Boolean get() = stops.size > 1
    val lateCount: Int get() = stops.count { it.late }
}

/** 긴 경로에서 가까운 일이 왼쪽 끝에 몰리지 않게 먼 구간은 점선 꼬리로 줄인다. */
data class TripTimeline(
    val now: LocalDateTime,
    val arrival: LocalDateTime,
    val zoomed: Boolean,
    val marks: List<Mark>,
    val laterCount: Int,
) {
    val stops: List<Stop> get() = marks.flatMap { it.stops }

    fun headline(): Headline? {
        val late = stops.filter { it.late }
        late.firstOrNull()?.let { first ->
            val more = late.size - 1
            return Headline("${first.name} ${first.time.hhmm()}에 ${first.lateMinutes}분 늦어요" + if (more > 0) " · ${more}곳 더" else "", late = true)
        }
        val next = stops.firstOrNull() ?: return null
        val what = when (next.kind) {
            StopKind.APPOINTMENT -> next.name
            StopKind.PICKUP -> "픽업"
            StopKind.CHARGE -> "충전"
        }
        return Headline("다음 · $what ${next.time.hhmm()}", late = false)
    }

    data class Headline(val text: String, val late: Boolean)

    companion object {
        val ZOOM_AFTER: Duration = Duration.ofMinutes(75)
        val ZOOM_WINDOW: Duration = Duration.ofMinutes(60)
        const val ZOOM_SPAN = 0.8f
        /** 막대 너비의 이 비율보다 가까운 지점은 점이 겹치므로 하나로 묶는다. */
        const val MIN_GAP = 0.03f
        private val LOOK_BEHIND: Duration = Duration.ofMinutes(30)
        private val LOOK_AFTER_ARRIVAL: Duration = Duration.ofMinutes(60)

        fun build(now: LocalDateTime, arrival: LocalDateTime, reservations: List<Reservation>, errands: List<Errand>): TripTimeline {
            val stops = (appointments(reservations, now, arrival) + errandStops(errands, now)).sortedBy { it.time }
            val total = Duration.between(now, arrival).coerceAtLeast(Duration.ofMinutes(1))
            val zoomed = total > ZOOM_AFTER
            val windowEnd = if (zoomed) now.plus(ZOOM_WINDOW) else arrival
            val (visible, later) = stops.partition { !it.time.isAfter(windowEnd) }
            fun fraction(t: LocalDateTime): Float {
                val span = if (zoomed) ZOOM_WINDOW else total
                val f = Duration.between(now, t).toMillis().toFloat() / span.toMillis()
                return f.coerceIn(0f, 1f) * if (zoomed) ZOOM_SPAN else 1f
            }
            return TripTimeline(now, arrival, zoomed, cluster(visible.map { fraction(it.time) to it }), later.size)
        }

        // 경로와 무관한 먼 약속(내일 저녁 등)은 막대를 복잡하게만 한다
        private fun appointments(reservations: List<Reservation>, now: LocalDateTime, arrival: LocalDateTime) =
            reservations
                .filter { it.time.isAfter(now.minus(LOOK_BEHIND)) && !it.time.isAfter(arrival.plus(LOOK_AFTER_ARRIVAL)) }
                .map { Stop(StopKind.APPOINTMENT, it.time, it.placeName, Duration.between(it.time, arrival).toMinutes().coerceAtLeast(0)) }

        private fun errandStops(errands: List<Errand>, now: LocalDateTime) = errands
            .filter { it.state == ErrandState.AWAITING_APPROVAL || it.state == ErrandState.IN_PROGRESS || it.state == ErrandState.COMPLETED }
            .mapNotNull { e ->
                when (val d = e.detail) {
                    is ErrandDetail.Pickup -> Stop(StopKind.PICKUP, at(e, d.readyAt), d.store.name)
                    is ErrandDetail.ChargeAndDine -> Stop(StopKind.CHARGE, at(e, d.arriveAt), d.charger.name)
                    // 약속은 서버 예약으로 들어와 예약 지점으로 보이고, 지각·메일은 경로 위 지점이 아니다
                    is ErrandDetail.LateArrival, is ErrandDetail.Meetup, is ErrandDetail.MailReply -> null
                }
            }
            .filter { !it.time.isBefore(now) }

        private fun at(errand: Errand, time: java.time.LocalTime): LocalDateTime =
            errand.createdAt.toLocalDate().atTime(time).let { if (it.isBefore(errand.createdAt.minusMinutes(1))) it.plusDays(1) else it }

        private fun cluster(points: List<Pair<Float, Stop>>): List<Mark> {
            val marks = mutableListOf<Mark>()
            var group = mutableListOf<Pair<Float, Stop>>()
            for (point in points) {
                if (group.isNotEmpty() && point.first - group.first().first >= MIN_GAP) {
                    marks += Mark(group.map { it.first }.average().toFloat(), group.map { it.second })
                    group = mutableListOf()
                }
                group += point
            }
            if (group.isNotEmpty()) marks += Mark(group.map { it.first }.average().toFloat(), group.map { it.second })
            return marks
        }
    }
}
