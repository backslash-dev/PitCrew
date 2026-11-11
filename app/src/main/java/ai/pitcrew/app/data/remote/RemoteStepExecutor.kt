package ai.pitcrew.app.data.remote

import ai.pitcrew.app.data.StepExecutor
import ai.pitcrew.app.data.StepResult
import ai.pitcrew.app.data.UndoResult
import ai.pitcrew.app.data.WaypointEditor
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.StepState
import ai.pitcrew.app.ui.format.hhmm
import android.util.Log
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime

/** 되돌리기에 쓰도록 첫 단계에서 서버가 준 id(예약 변경·주문·줄서기)를 남긴다. */
class RemoteStepExecutor(
    private val api: PitCrewApi,
    private val reloadReservations: suspend () -> Unit,
    private val waypoints: WaypointEditor,
    private val reloadMails: suspend () -> Unit = {},
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : StepExecutor {
    override suspend fun execute(errand: Errand, stepIndex: Int): StepResult = try {
        when (val d = errand.detail) {
            is ErrandDetail.LateArrival -> lateArrival(d, stepIndex, key(errand, stepIndex))
            is ErrandDetail.Pickup -> pickup(d, stepIndex, key(errand, stepIndex))
            is ErrandDetail.ChargeAndDine -> chargeAndDine(d, stepIndex, key(errand, stepIndex))
            is ErrandDetail.Meetup -> meetup(errand, d, stepIndex, key(errand, stepIndex))
            is ErrandDetail.MailReply -> mailReply(d, key(errand, stepIndex))
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StepResult.Failed(e.userMessage())
    }

    // 작업·단계마다 고정된 키라 같은 단계를 다시 보내면 서버가 처음 결과를 돌려준다
    private fun key(errand: Errand, step: String) = "${errand.id}-$step"
    private fun key(errand: Errand, stepIndex: Int) = key(errand, stepIndex.toString())

    override suspend fun undo(errand: Errand): UndoResult {
        val reference = errand.reference ?: return UndoResult.Failed("서버 기록이 없어 되돌릴 수 없어요")
        return try {
            when (val d = errand.detail) {
                is ErrandDetail.LateArrival -> undoLateArrival(d, reference, notified = errand.notifiedCompanions(), key = key(errand, "undo-notify"))
                is ErrandDetail.Pickup -> {
                    api.post("v1/orders/$reference/cancel", JSONObject())
                    UndoResult.Undone(removeWaypoint(d.store.displayName, d.store.latitude, d.store.longitude))
                }
                is ErrandDetail.ChargeAndDine -> {
                    api.post("v1/waitlists/$reference/cancel", JSONObject())
                    UndoResult.Undone(removeWaypoint(d.charger.name, d.charger.latitude, d.charger.longitude))
                }
                is ErrandDetail.Meetup -> {
                    val cancelled = api.post("v1/meetups/$reference/cancel", JSONObject())
                    reloadReservations()
                    UndoResult.Undone(cancelled.optString("note").takeIf { it.isNotEmpty() })
                }
                is ErrandDetail.MailReply -> UndoResult.Failed("보낸 메일은 되돌릴 수 없어요")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UndoResult.Failed(e.userMessage())
        }
    }

    private suspend fun lateArrival(d: ErrandDetail.LateArrival, stepIndex: Int, key: String): StepResult = when (stepIndex) {
        1 -> {
            val reservationId = d.reservationId ?: return StepResult.Failed("등록된 예약이 없어요")
            val target = proposedDateTime(d)
            val updated = api.post("v1/reservations/$reservationId/reschedule", JSONObject().put("reservedAt", target.toInstantString()), key)
            val changeId = updated.getString("changeId")
            // 다른 시각으로 잡혔어도 서버 예약은 바뀌었으므로 되돌릴 수 있게 변경 id를 남긴다
            if (updated.getString("reservedAt").toLocalDateTime() == target) StepResult.Recorded(changeId)
            else StepResult.Failed("식당이 다른 시간으로 잡았어요", reference = changeId)
        }
        2 -> {
            reloadReservations()
            StepResult.Done
        }
        3 -> {
            val place = d.placeName ?: "약속"
            val message = if (d.followUp) {
                "PitCrew 알림 · 도착이 더 늦어져 $place 예약을 한 번 더 미뤘어요. 새 예약 ${d.proposedTime.hhmm()}, 도착 예정 ${d.estimatedArrival.hhmm()}."
            } else {
                "PitCrew 알림 · $place 예약을 ${d.proposedTime.hhmm()}에 다시 잡았어요. 도착 예정 ${d.estimatedArrival.hhmm()}."
            }
            val failed = notify(message, d, key)
            if (failed == 0) StepResult.Done else StepResult.Failed("${failed}명에게 알림을 보내지 못했어요")
        }
        else -> StepResult.Done
    }

    // 예약을 되돌린 뒤에는 앞서 보낸 안내가 틀린 정보가 되므로 일행에게 정정 안내를 보낸다
    private suspend fun undoLateArrival(d: ErrandDetail.LateArrival, changeId: String, notified: Boolean, key: String): UndoResult {
        val restored = api.post("v1/reservation-changes/$changeId/revert", JSONObject())
        reloadReservations()
        if (d.companions.isEmpty() || !notified) return UndoResult.Undone()
        val message = "PitCrew 알림 · 앞서 보낸 안내는 취소됐어요. ${d.placeName ?: "약속"} 예약은 ${restored.getString("reservedAt").toLocalDateTime().hhmm()} 그대로예요."
        val failed = try {
            notify(message, d, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            d.companions.size
        }
        return UndoResult.Undone(if (failed == 0) null else "예약은 되돌렸지만 일행 ${failed}명에게 정정 안내를 보내지 못했어요")
    }

    private suspend fun pickup(d: ErrandDetail.Pickup, stepIndex: Int, key: String): StepResult = when (stepIndex) {
        1 -> {
            val order = api.post(
                "v1/orders",
                JSONObject().put("storeId", d.store.id).put("item", d.item).put("pickupAt", nextOccurrence(d.readyAt).toInstantString()),
                key,
            )
            StepResult.Recorded(order.getString("id"))
        }
        2 -> StepResult.Done
        3 -> addWaypoint(d.store.displayName, d.store.latitude, d.store.longitude)
        else -> StepResult.Done
    }

    private suspend fun chargeAndDine(d: ErrandDetail.ChargeAndDine, stepIndex: Int, key: String): StepResult = when (stepIndex) {
        1 -> {
            val waitlist = api.post("v1/waitlists", JSONObject().put("restaurantId", d.restaurant.id).put("partySize", d.partySize), key)
            StepResult.Recorded(waitlist.getString("id"))
        }
        2 -> addWaypoint(d.charger.name, d.charger.latitude, d.charger.longitude)
        else -> StepResult.Done
    }

    private suspend fun meetup(errand: Errand, d: ErrandDetail.Meetup, stepIndex: Int, key: String): StepResult {
        val booking = if (d.mode == MeetupMode.SMS) 2 else 1
        return when {
            d.mode == MeetupMode.SMS && stepIndex == 1 -> {
                val plan = api.post("v1/meetups/ask", meetupRequest(d.contact.id, d.latitude, d.longitude, d.preferredAt), key).toMeetupPlan()
                StepResult.Planned(d.copy(place = plan.place, meetAt = plan.meetAt, userTravelMinutes = plan.userTravelMinutes, peerTravelMinutes = plan.peerTravelMinutes))
            }
            stepIndex == booking -> {
                val place = d.place ?: return StepResult.Failed("만날 장소가 정해지지 않았어요")
                val at = d.meetAt ?: return StepResult.Failed("만날 시각이 정해지지 않았어요")
                val booked = api.post(
                    "v1/meetups",
                    JSONObject().put("contactId", d.contact.id).put("placeId", place.id).put("meetAt", at.toInstantString()).put("mode", d.mode.name),
                    key,
                )
                reloadReservations()
                StepResult.Recorded(booked.getString("id"))
            }
            else -> {
                val id = errand.reference ?: return StepResult.Failed("예약 기록이 없어 전달하지 못했어요")
                api.post("v1/meetups/$id/share", JSONObject(), key)
                StepResult.Done
            }
        }
    }

    private suspend fun mailReply(d: ErrandDetail.MailReply, key: String): StepResult {
        api.post("v1/mails/${d.mail.id}/reply", JSONObject().put("body", d.draft).put("instruction", d.instruction.take(200)), key)
        // 이미 보냈으므로 목록 갱신 실패는 작업 실패로 보지 않는다
        try {
            reloadMails()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("RemoteStepExecutor", "mail reload failed", e)
        }
        return StepResult.Done
    }

    /** 실패한 받는 사람 수를 돌려준다. */
    private suspend fun notify(message: String, d: ErrandDetail.LateArrival, key: String): Int {
        val results = api.post(
            "v1/notifications",
            JSONObject().put("message", message).put("contactIds", JSONArray(d.companions.map { it.id })),
            key,
        ).getJSONArray("results").objects()
        return results.count { it.getString("status") != "SENT" }
    }

    private suspend fun addWaypoint(name: String, latitude: Double, longitude: Double): StepResult =
        if (waypoints.add(name, latitude, longitude)) StepResult.Done else StepResult.Failed(MockStepExecutor.WAYPOINT_ADD_FAILED)

    private suspend fun removeWaypoint(name: String, latitude: Double, longitude: Double): String? =
        if (waypoints.remove(name, latitude, longitude)) null else MockStepExecutor.WAYPOINT_REMOVE_FAILED

    // 알림 단계까지 가지 못하고 실패했다면 일행은 아무 안내도 받지 않았으므로 정정 안내도 보내지 않는다
    private fun Errand.notifiedCompanions(): Boolean = steps.lastOrNull()?.state == StepState.DONE

    // 제안 시각은 시각만 있으므로 약속 날짜 기준으로 붙이고, 자정을 넘기면 다음 날로 본다
    private fun proposedDateTime(d: ErrandDetail.LateArrival): LocalDateTime {
        val sameDay = d.appointment.toLocalDate().atTime(d.proposedTime)
        return if (sameDay.isBefore(d.appointment)) sameDay.plusDays(1) else sameDay
    }

    private fun nextOccurrence(time: LocalTime): LocalDateTime {
        val today = now().toLocalDate().atTime(time)
        return if (today.isBefore(now().minusMinutes(1))) today.plusDays(1) else today
    }
}
