package ai.pitcrew.app.errand

import ai.pitcrew.app.gleo.GleoActionHandler
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoResponse
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.platform.SdkUnavailableException
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class LateArrivalPlan(
    val appointment: LocalDateTime,
    val estimatedArrival: LocalDateTime,
    val delayMinutes: Long,
    val proposedTime: LocalTime?,
) {
    val isLate: Boolean get() = delayMinutes > 0
}

object LateArrivalPlanner {
    private const val PROPOSAL_STEP_MINUTES = 10L

    fun plan(appointmentTime: LocalTime, now: LocalDateTime, remainSeconds: Int): LateArrivalPlan {
        val appointment = nearestOccurrence(appointmentTime, now)
        // 안내·지연·제안이 같은 값을 쓰도록 도착 시각을 분 단위로 반올림한다
        val arrival = now.plusSeconds(remainSeconds.toLong().coerceAtLeast(0) + 30).truncatedTo(ChronoUnit.MINUTES)
        val delayMinutes = Duration.between(appointment, arrival).toMinutes()
        val proposed = if (delayMinutes > 0) roundUp(arrival).toLocalTime() else null
        return LateArrivalPlan(appointment, arrival, delayMinutes, proposed)
    }

    // 시각만 받으므로 지금과 가장 가까운 시점으로 본다. Gleo는 "10시"를 저녁에도 10:00으로 넘긴 적이 있어
    // 12시 이하 시각은 오전·오후 둘 다 후보로 둔다. 전날·다음 날도 후보라 자정을 넘는 약속도 맞는다
    private fun nearestOccurrence(time: LocalTime, now: LocalDateTime): LocalDateTime {
        val times = when {
            time.hour < 12 -> listOf(time, time.plusHours(12))
            time.hour == 12 -> listOf(time, time.minusHours(12))
            else -> listOf(time)
        }
        return times
            .flatMap { t -> (-1L..1L).map { now.toLocalDate().plusDays(it).atTime(t) } }
            .minBy { Duration.between(now, it).abs() }
    }

    private fun roundUp(time: LocalDateTime): LocalDateTime {
        val remainder = time.minute % PROPOSAL_STEP_MINUTES
        return if (remainder == 0L) time else time.plusMinutes(PROPOSAL_STEP_MINUTES - remainder)
    }
}

class LateArrivalHandler(
    private val fetchDestination: suspend () -> Destination?,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : GleoActionHandler {
    private val function = GleoFunction.NOTIFY_LATE_ARRIVAL

    override suspend fun handle(request: GleoRequest): GleoResponse {
        val rawAppointment = request.params.optString("appointmentTime")
        val appointment = parseClockTime(rawAppointment)
            ?: return GleoResponse.asking(
                function,
                "The appointment time is required in 24-hour HH:mm format.",
                listOf(mapOf("missingField" to "appointmentTime")),
            )

        val destination = try {
            fetchDestination()
        } catch (e: SdkUnavailableException) {
            return GleoResponse.fail(function, "The navigation service is not available, so the arrival time is unknown.")
        } ?: return GleoResponse.fail(function, "There is no active navigation route, so the arrival time is unknown.")

        val plan = LateArrivalPlanner.plan(appointment, now(), destination.remainTimeSeconds)
        val data = plan.toData(destination)

        if (!plan.isLate) {
            return GleoResponse.success(function, "The driver is expected to arrive on time, so no change is needed.", data)
        }

        val confirmedTime = parseClockTime(request.params.optString("confirmedTime"))
            ?: return GleoResponse.asking(
                function,
                "The driver is expected to be ${plan.delayMinutes} minutes late. " +
                    "User approval is required to change the reservation to ${plan.proposedTime?.toClockTime()} and notify the companions.",
                data,
            )

        return GleoResponse.fail(
            function,
            "Changing the reservation and notifying companions is not available yet.",
            listOf(data.first() + ("confirmedTime" to confirmedTime.toClockTime())),
        )
    }

    private fun LateArrivalPlan.toData(destination: Destination): List<Map<String, Any>> = listOf(
        buildMap {
            put("destination", destination.name)
            put("appointmentTime", appointment.toLocalTime().toClockTime())
            put("estimatedArrivalTime", estimatedArrival.toLocalTime().toClockTime())
            put("delayMinutes", delayMinutes)
            proposedTime?.let { put("proposedTime", it.toClockTime()) }
        },
    )
}
