package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ContactRepository
import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.ReservationRepository
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Reservation
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

    /** 안내·지연·제안이 같은 값을 쓰도록 도착 시각을 분 단위로 반올림한다. 화면 표시도 이 값을 쓴다. */
    fun arrivalAt(now: LocalDateTime, remainSeconds: Int): LocalDateTime =
        now.plusSeconds(remainSeconds.toLong().coerceAtLeast(0) + 30).truncatedTo(ChronoUnit.MINUTES)

    fun plan(appointmentTime: LocalTime, now: LocalDateTime, remainSeconds: Int): LateArrivalPlan =
        plan(nearestOccurrence(appointmentTime, now), now, remainSeconds)

    fun plan(appointment: LocalDateTime, now: LocalDateTime, remainSeconds: Int): LateArrivalPlan {
        val arrival = arrivalAt(now, remainSeconds)
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

/**
 * Gleo는 승인 시각도 12시간제로 헷갈려 넘긴다(01:10을 13:10으로). 제안한 시각과 12시간 차이 나는 두 후보 중 가까운 쪽으로 본다.
 */
internal fun closestHalfDay(time: LocalTime, reference: LocalTime): LocalTime {
    fun distance(t: LocalTime): Long {
        val diff = Duration.between(reference, t).abs().toMinutes()
        return minOf(diff, 24 * 60 - diff)
    }
    return listOf(time, time.plusHours(12)).minBy(::distance)
}

sealed interface LateArrivalOutcome {
    data object NoRoute : LateArrivalOutcome
    data object NavigationUnavailable : LateArrivalOutcome
    data class OnTime(val plan: LateArrivalPlan, val reservation: Reservation?) : LateArrivalOutcome
    /** 늦지만 그 시각에 등록된 예약이 없어 바꿀 대상이 없다. */
    data class NoReservation(val plan: LateArrivalPlan) : LateArrivalOutcome
    data class AwaitingApproval(val errand: Errand, val plan: LateArrivalPlan, val reservation: Reservation?) : LateArrivalOutcome
    data class Finished(val errand: Errand, val plan: LateArrivalPlan, val reservation: Reservation?) : LateArrivalOutcome
}

/** 지각 판단과 실행. Gleo·앱 음성 입력이 같이 쓴다. */
class LateArrivalUseCase(
    private val fetchDestination: suspend () -> Destination?,
    private val errands: ErrandService,
    private val reservations: ReservationRepository,
    private val contacts: ContactRepository,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    /**
     * [approvedTime]이 있으면 사용자가 그 시간으로 승인한 것으로 보고 바로 실행한다.
     * 단, 이 예약에 대해 먼저 물은 제안이 없으면(Gleo가 첫 요청부터 확인을 붙여 보낸 경우) 승인으로 보지 않고 다시 묻는다.
     */
    suspend fun run(appointment: LocalTime, approvedTime: LocalTime? = null, channel: ApprovalChannel = ApprovalChannel.VOICE): LateArrivalOutcome {
        val destination = try {
            fetchDestination()
        } catch (e: SdkUnavailableException) {
            return LateArrivalOutcome.NavigationUnavailable
        } ?: return LateArrivalOutcome.NoRoute

        val plan = LateArrivalPlanner.plan(appointment, now(), destination.remainTimeSeconds)
        // 화면에서 이미 승인했는데 Gleo에도 "응"이라고 한 경우·Gleo 재시도: 같은 약속을 방금 처리했으면 다시 실행하지 않는다.
        // 예약 시각이 이미 바뀌어 아래 예약 찾기로는 못 찾으므로 먼저 본다
        if (approvedTime != null) recentlyHandled(plan.appointment)?.let { return LateArrivalOutcome.Finished(it, plan, null) }

        reservations.refreshNow()
        contacts.ensureLoaded()
        val reservation = matchReservation(plan.appointment)
        if (!plan.isLate) return LateArrivalOutcome.OnTime(plan, reservation)
        if (reservation == null) return LateArrivalOutcome.NoReservation(plan)

        val asked = errands.errands.value.any {
            it.state == ErrandState.AWAITING_APPROVAL && (it.detail as? ErrandDetail.LateArrival)?.reservationId == reservation.id
        }
        val approved = approvedTime?.takeIf { asked }
        val proposedTime = approved?.let { closestHalfDay(it, plan.proposedTime!!) } ?: plan.proposedTime!!
        val errand = propose(reservation, plan, proposedTime, ErrandOrigin.REQUESTED, followUp = wasRescheduled(reservation))
        if (approved == null) return LateArrivalOutcome.AwaitingApproval(errand, plan, reservation)
        return LateArrivalOutcome.Finished(errands.approve(errand.id, channel), plan, reservation)
    }

    /** 경로 감시가 늦음을 알아챘을 때 PitCrew 제안으로 올린다. 늦지 않으면 null. */
    suspend fun suggest(reservation: Reservation, remainSeconds: Int): Errand? {
        contacts.ensureLoaded()
        val plan = LateArrivalPlanner.plan(reservation.time, now(), remainSeconds)
        val proposedTime = plan.proposedTime ?: return null
        return propose(reservation, plan, proposedTime, ErrandOrigin.SUGGESTED, followUp = wasRescheduled(reservation))
    }

    private fun propose(reservation: Reservation, plan: LateArrivalPlan, proposedTime: LocalTime, origin: ErrandOrigin, followUp: Boolean): Errand {
        val companions = reservation.companionIds.mapNotNull { id -> contacts.contacts.value.firstOrNull { it.id == id } }
        return errands.propose(
            ErrandDetail.LateArrival(
                reservationId = reservation.id,
                placeName = reservation.placeName,
                appointment = plan.appointment,
                estimatedArrival = plan.estimatedArrival,
                delayMinutes = plan.delayMinutes,
                proposedTime = proposedTime,
                companions = companions,
                followUp = followUp,
            ),
            origin,
        )
    }

    private fun recentlyHandled(appointment: LocalDateTime): Errand? = errands.errands.value.firstOrNull {
        val d = it.detail as? ErrandDetail.LateArrival ?: return@firstOrNull false
        d.appointment == appointment && it.handledRecently(now())
    }

    // 이 예약을 이미 한 번 미뤘고 무르지 않았으면 또 늦는 경우다
    private fun wasRescheduled(reservation: Reservation): Boolean = errands.errands.value.any {
        it.state == ErrandState.COMPLETED && (it.detail as? ErrandDetail.LateArrival)?.reservationId == reservation.id
    }

    // 말한 시각과 15분 안쪽인 예약을 같은 약속으로 본다
    private fun matchReservation(appointment: LocalDateTime): Reservation? =
        reservations.reservations.value
            .filter { Duration.between(it.time, appointment).abs() <= MATCH_WINDOW }
            .minByOrNull { Duration.between(it.time, appointment).abs() }

    private companion object {
        val MATCH_WINDOW: Duration = Duration.ofMinutes(15)
    }
}

class LateArrivalHandler(private val useCase: LateArrivalUseCase) : GleoActionHandler {
    private val function = GleoFunction.NOTIFY_LATE_ARRIVAL

    override suspend fun handle(request: GleoRequest): GleoResponse {
        val appointment = parseClockTime(request.params.optString("appointmentTime"))
            ?: return GleoResponse.asking(
                function,
                "The appointment time is required in 24-hour HH:mm format.",
                listOf(mapOf("missingField" to "appointmentTime")),
            )
        val confirmedTime = parseClockTime(request.params.optString("confirmedTime"))

        return when (val outcome = useCase.run(appointment, confirmedTime, ApprovalChannel.VOICE)) {
            LateArrivalOutcome.NavigationUnavailable ->
                GleoResponse.fail(function, "The navigation service is not available, so the arrival time is unknown.")
            LateArrivalOutcome.NoRoute ->
                GleoResponse.fail(function, "There is no active navigation route, so the arrival time is unknown.")
            is LateArrivalOutcome.OnTime ->
                GleoResponse.success(function, "The driver is expected to arrive on time, so no change is needed.", outcome.plan.toData(outcome.reservation))
            is LateArrivalOutcome.NoReservation ->
                GleoResponse.fail(
                    function,
                    "No reservation around ${outcome.plan.appointment.toLocalTime().toClockTime()} is registered in PitCrew, so there is nothing to change. " +
                        "The user can register it in the PitCrew app.",
                    outcome.plan.toData(null),
                )
            is LateArrivalOutcome.AwaitingApproval -> {
                val detail = outcome.errand.detail as ErrandDetail.LateArrival
                GleoResponse.asking(
                    function,
                    "The driver is expected to be ${outcome.plan.delayMinutes} minutes late for the reservation${outcome.reservation.place()}. " +
                        "User approval is required to change it to ${detail.proposedTime.toClockTime()}${detail.notifyClause(" and notify")}.",
                    outcome.plan.toData(outcome.reservation),
                )
            }
            is LateArrivalOutcome.Finished -> {
                val detail = outcome.errand.detail as ErrandDetail.LateArrival
                when (outcome.errand.state) {
                    ErrandState.COMPLETED -> GleoResponse.success(
                        function,
                        "The reservation${detail.placeName?.let { " at $it" }.orEmpty()} was changed to ${detail.proposedTime.toClockTime()}${detail.notifyClause(" and notified")}.",
                        listOf(outcome.plan.toData(outcome.reservation).first() + ("confirmedTime" to detail.proposedTime.toClockTime())),
                    )
                    ErrandState.IN_PROGRESS -> GleoResponse.success(function, "This reservation change is already being processed. The result will be shown in the PitCrew app.")
                    ErrandState.DECLINED -> GleoResponse.fail(function, "The user declined this change on the screen.")
                    else -> GleoResponse.fail(function, "Changing the reservation failed: ${outcome.errand.failureReason ?: "unknown error"}.")
                }
            }
        }
    }

    // 알릴 일행이 없으면 "0 companions"라고 말하지 않게 문장에서 뺀다
    private fun ErrandDetail.LateArrival.notifyClause(verb: String): String = when (companions.size) {
        0 -> ""
        1 -> "$verb 1 companion"
        else -> "$verb ${companions.size} companions"
    }

    private fun Reservation?.place(): String = this?.placeName?.let { " at $it" }.orEmpty()

    private fun LateArrivalPlan.toData(reservation: Reservation?): List<Map<String, Any>> = listOf(
        buildMap {
            reservation?.let { put("place", it.placeName) }
            put("appointmentTime", appointment.toLocalTime().toClockTime())
            put("estimatedArrivalTime", estimatedArrival.toLocalTime().toClockTime())
            put("delayMinutes", delayMinutes)
            proposedTime?.let { put("proposedTime", it.toClockTime()) }
        },
    )
}
