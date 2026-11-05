package ai.pitcrew.app.data.remote

import ai.pitcrew.app.data.StepExecutor
import ai.pitcrew.app.data.StepResult
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.Store
import ai.pitcrew.app.ui.format.hhmm
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 단계마다 서버를 부른다. 지각: 예약 변경 요청 → 변경 확인 → 일행 알림. 픽업: 주문 → 준비 확인 → 경유지 추가(내비).
 * 실패하면 서버의 안내 문장을 그대로 작업 실패 사유로 쓴다.
 */
class RemoteStepExecutor(
    private val api: PitCrewApi,
    private val reloadReservations: suspend () -> Unit,
    private val addWaypoint: suspend (Store) -> Boolean,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : StepExecutor {
    override suspend fun execute(errand: Errand, stepIndex: Int): StepResult = try {
        when (val d = errand.detail) {
            is ErrandDetail.LateArrival -> lateArrival(d, stepIndex)
            is ErrandDetail.Pickup -> pickup(d, stepIndex)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StepResult.Failed(e.userMessage())
    }

    private suspend fun lateArrival(d: ErrandDetail.LateArrival, stepIndex: Int): StepResult = when (stepIndex) {
        1 -> {
            val reservationId = d.reservationId ?: return StepResult.Failed("등록된 예약이 없어요")
            val target = proposedDateTime(d)
            val updated = api.post("v1/reservations/$reservationId/reschedule", JSONObject().put("reservedAt", target.toInstantString()))
            if (updated.getString("reservedAt").toLocalDateTime() == target) StepResult.Done else StepResult.Failed("식당이 다른 시간으로 잡았어요")
        }
        2 -> {
            reloadReservations()
            StepResult.Done
        }
        3 -> {
            val message = "PitCrew 알림 · ${d.placeName ?: "약속"} 예약을 ${d.proposedTime.hhmm()}에 다시 잡았어요. 도착 예정 ${d.estimatedArrival.hhmm()}."
            val results = api.post(
                "v1/notifications",
                JSONObject().put("message", message).put("contactIds", JSONArray(d.companions.map { it.id })),
            ).getJSONArray("results").objects()
            val failed = results.count { it.getString("status") != "SENT" }
            if (failed == 0) StepResult.Done else StepResult.Failed("${failed}명에게 알림을 보내지 못했어요")
        }
        else -> StepResult.Done
    }

    private suspend fun pickup(d: ErrandDetail.Pickup, stepIndex: Int): StepResult = when (stepIndex) {
        1 -> {
            api.post(
                "v1/orders",
                JSONObject().put("storeId", d.store.id).put("item", d.item).put("pickupAt", nextOccurrence(d.readyAt).toInstantString()),
            )
            StepResult.Done
        }
        2 -> StepResult.Done
        3 -> if (addWaypoint(d.store)) StepResult.Done else StepResult.Failed("내비게이션에 경유지를 넣지 못했어요")
        else -> StepResult.Done
    }

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
