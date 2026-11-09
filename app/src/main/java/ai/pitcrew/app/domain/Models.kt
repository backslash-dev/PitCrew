package ai.pitcrew.app.domain

import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

data class Contact(val id: String, val name: String, val phone: String)

data class Reservation(
    val id: String,
    val placeName: String,
    val time: LocalDateTime,
    val partySize: Int,
    val companionIds: List<String>,
)

data class Store(
    val id: String,
    val name: String,
    val branch: String,
    val latitude: Double,
    val longitude: Double,
    val detourMinutes: Int,
    val prepMinutes: Int,
) {
    val displayName: String get() = "$name $branch"
}

/** 충전소에서 걸어갈 수 있는 식당. [waitMinutes]는 지금 줄을 서면 예상되는 대기 시간. */
data class NearbyRestaurant(val id: String, val name: String, val walkMinutes: Int, val waitMinutes: Int)

data class Charger(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val detourMinutes: Int,
    val powerKw: Int,
    val availableStalls: Int,
    val restaurants: List<NearbyRestaurant>,
)

data class PastOrder(
    val storeId: String,
    val storeName: String,
    val item: String,
    val pickupAt: LocalDateTime,
    val cancelled: Boolean,
)

/** EXPIRED는 승인 대기 중 답이 없어 넘어간 제안. */
enum class ErrandState { AWAITING_APPROVAL, IN_PROGRESS, COMPLETED, DECLINED, FAILED, CANCELLED, EXPIRED }

enum class ApprovalChannel { VOICE, TOUCH }

/** 사용자가 시킨 일인지, PitCrew가 상황을 보고 먼저 제안한 일인지. */
enum class ErrandOrigin { REQUESTED, SUGGESTED }

enum class StepState { DONE, CURRENT, PENDING, FAILED }

data class ErrandStep(val title: String, val state: StepState, val at: LocalDateTime? = null)

sealed interface ErrandDetail {
    data class LateArrival(
        val reservationId: String?,
        val placeName: String?,
        val appointment: LocalDateTime,
        val estimatedArrival: LocalDateTime,
        val delayMinutes: Long,
        val proposedTime: LocalTime,
        val companions: List<Contact>,
        /** 이미 한 번 미룬 예약에 또 늦는 경우. 일행에게 보내는 문장이 달라진다. */
        val followUp: Boolean = false,
    ) : ErrandDetail

    data class Pickup(
        val item: String,
        val store: Store,
        val readyAt: LocalTime,
    ) : ErrandDetail

    data class ChargeAndDine(
        val charger: Charger,
        val restaurant: NearbyRestaurant,
        val partySize: Int,
        val batteryPercent: Int?,
        val chargeMinutes: Int,
        val arriveAt: LocalTime,
    ) : ErrandDetail
}

data class Errand(
    val id: String,
    val createdAt: LocalDateTime,
    val state: ErrandState,
    val detail: ErrandDetail,
    val steps: List<ErrandStep>,
    val origin: ErrandOrigin = ErrandOrigin.REQUESTED,
    val approvedVia: ApprovalChannel? = null,
    val finishedAt: LocalDateTime? = null,
    val failureReason: String? = null,
    val arrivalAnnouncedAt: LocalDateTime? = null,
    /** 되돌릴 때 쓰는 서버 쪽 식별자(예약 변경·주문·줄서기 id). 목업은 null. */
    val reference: String? = null,
    val undoing: Boolean = false,
    val undoneVia: ApprovalChannel? = null,
    /** 되돌렸지만 일부(일행 정정 알림·경유지 삭제)를 못 했을 때의 안내, 또는 되돌리기 실패 사유. */
    val undoNote: String? = null,
) {
    val isActive: Boolean get() = state == ErrandState.AWAITING_APPROVAL || state == ErrandState.IN_PROGRESS

    /** 끝났거나, 서버에서 일부 처리된 뒤 실패해 되돌릴 기록이 있는 작업. */
    val undoable: Boolean get() = state == ErrandState.COMPLETED || (state == ErrandState.FAILED && reference != null)

    fun canUndo(now: LocalDateTime): Boolean =
        undoable && !undoing && finishedAt != null && Duration.between(finishedAt, now) <= UNDO_WINDOW

    /** 답이 없는 제안을 계속 두면 지난 시각으로 실행되거나 다른 제안을 막으므로 만료시킨다. 픽업은 준비 시각이 지나면 바로 만료. */
    fun isStale(now: LocalDateTime): Boolean {
        if (Duration.between(createdAt, now) >= PROPOSAL_TTL) return true
        val d = detail as? ErrandDetail.Pickup ?: return false
        val ready = createdAt.toLocalDate().atTime(d.readyAt).let { if (it.isBefore(createdAt)) it.plusDays(1) else it }
        return !now.isBefore(ready)
    }

    companion object {
        /** 매장이 조리를 시작하기 전, 식당이 자리를 다시 내주기 전에 무를 수 있는 시간. */
        val UNDO_WINDOW: Duration = Duration.ofMinutes(3)
        val PROPOSAL_TTL: Duration = Duration.ofMinutes(10)
    }
}
