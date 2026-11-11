package ai.pitcrew.app.ui.timeline

import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.ui.format.hhmm
import java.time.Duration
import java.time.LocalDateTime

enum class StopKind { APPOINTMENT, PICKUP, CHARGE }

/** 경로 위의 한 지점. [lateMinutes]는 약속에 도착이 늦는 분(늦지 않으면 0). */
data class Stop(val kind: StopKind, val time: LocalDateTime, val name: String, val lateMinutes: Long = 0) {
    val late: Boolean get() = lateMinutes > 0
}

/** 막대 위 한 자리. 가까운 지점은 하나로 묶인다. [fraction]은 0(지금)~1(도착) 위치. */
data class Mark(val fraction: Float, val stops: List<Stop>) {
    val cluster: Boolean get() = stops.size > 1
    val lateCount: Int get() = stops.count { it.late }

    /** 막대 위에 붙는 짧은 이름표. 묶음은 범위와 늦는 곳 수로 요약한다. */
    fun label(): String {
        if (!cluster) {
            val s = stops.single()
            return when (s.kind) {
                StopKind.APPOINTMENT -> "${s.name} ${s.time.hhmm()}" + if (s.late) " · ${s.lateMinutes}분 늦음" else ""
                StopKind.PICKUP -> "픽업 ${s.time.hhmm()}"
                StopKind.CHARGE -> "충전 ${s.time.hhmm()}"
            }
        }
        val range = "${stops.first().time.hhmm()}~${stops.last().time.hhmm()}"
        return if (lateCount > 0) "$range · ${lateCount}곳 늦음" else range
    }
}

/**
 * 지금부터 도착까지의 경로. 도착이 [ZOOM_AFTER]보다 멀면 다음 [ZOOM_WINDOW]만 막대의 [ZOOM_SPAN]에 펼치고
 * 나머지는 점선 꼬리로 줄인다(긴 경로에서 가까운 일이 왼쪽 끝에 몰리지 않게). [laterCount]는 꼬리 쪽에 숨은 지점 수.
 */
data class TripTimeline(
    val now: LocalDateTime,
    val arrival: LocalDateTime,
    val zoomed: Boolean,
    val marks: List<Mark>,
    val laterCount: Int,
) {
    companion object {
        val ZOOM_AFTER: Duration = Duration.ofMinutes(75)
        val ZOOM_WINDOW: Duration = Duration.ofMinutes(60)
        const val ZOOM_SPAN = 0.8f
        /** 막대 너비의 이 비율보다 가까운 지점은 이름표가 겹치므로 묶는다. */
        const val MIN_GAP = 0.12f
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

        // 도착 전후의 약속만 본다. 경로와 무관한 먼 약속(내일 저녁 등)은 막대를 복잡하게만 한다
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
                    is ErrandDetail.LateArrival -> null
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
