package ai.pitcrew.app.data.mock

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.ReservationRepository
import ai.pitcrew.app.data.StepExecutor
import ai.pitcrew.app.data.StepResult
import ai.pitcrew.app.data.UndoResult
import ai.pitcrew.app.data.WaypointEditor
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.Reservation

/** 경유지만 실제 내비를 부르고, 약속 조율은 화면에 보이게 목업 메일함·예약에도 반영한다. */
class MockStepExecutor(
    private val waypoints: WaypointEditor,
    private val meetups: MockMeetupService? = null,
    private val reservations: ReservationRepository? = null,
    private val mails: MockMailService? = null,
) : StepExecutor {
    override suspend fun execute(errand: Errand, stepIndex: Int): StepResult {
        val d = errand.detail
        if (d is ErrandDetail.Meetup) return meetup(errand, d, stepIndex)
        if (d is ErrandDetail.MailReply) {
            mails?.markReplied(d.mail.id)
            return StepResult.Done
        }
        if (stepIndex != ErrandRunner.plannedSteps(d).size) return StepResult.Done
        val added = when (d) {
            is ErrandDetail.Pickup -> waypoints.add(d.store.displayName, d.store.latitude, d.store.longitude)
            is ErrandDetail.ChargeAndDine -> waypoints.add(d.charger.name, d.charger.latitude, d.charger.longitude)
            else -> true
        }
        return if (added) StepResult.Done else StepResult.Failed(WAYPOINT_ADD_FAILED)
    }

    private fun meetup(errand: Errand, d: ErrandDetail.Meetup, stepIndex: Int): StepResult {
        val booking = if (d.mode == MeetupMode.SMS) 2 else 1
        return when {
            d.mode == MeetupMode.SMS && stepIndex == 1 -> {
                val plan = meetups?.replied(d.contact, d.latitude, d.longitude, d.preferredAt) ?: return StepResult.Failed("답장을 받지 못했어요")
                StepResult.Planned(d.copy(place = plan.place, meetAt = plan.meetAt, userTravelMinutes = plan.userTravelMinutes, peerTravelMinutes = plan.peerTravelMinutes))
            }
            stepIndex == booking -> {
                val place = d.place ?: return StepResult.Failed("만날 장소가 정해지지 않았어요")
                val at = d.meetAt ?: return StepResult.Failed("만날 시각이 정해지지 않았어요")
                val id = "meetup-${errand.id}"
                reservations?.upsert(Reservation(id, place.name, at, partySize = 2, companionIds = listOf(d.contact.id)))
                StepResult.Recorded(id)
            }
            else -> StepResult.Done
        }
    }

    override suspend fun undo(errand: Errand): UndoResult {
        val removed = when (val d = errand.detail) {
            is ErrandDetail.Pickup -> waypoints.remove(d.store.displayName, d.store.latitude, d.store.longitude)
            is ErrandDetail.ChargeAndDine -> waypoints.remove(d.charger.name, d.charger.latitude, d.charger.longitude)
            is ErrandDetail.Meetup -> {
                errand.reference?.let { reservations?.delete(it) }
                true
            }
            is ErrandDetail.LateArrival, is ErrandDetail.MailReply -> true
        }
        return UndoResult.Undone(if (removed) null else WAYPOINT_REMOVE_FAILED)
    }

    companion object {
        const val WAYPOINT_ADD_FAILED = "내비게이션에 경유지를 넣지 못했어요"
        const val WAYPOINT_REMOVE_FAILED = "경유지는 내비게이션에서 직접 지워 주세요"
    }
}
