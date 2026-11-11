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

/** [waitMinutes]는 지금 줄을 서면 예상되는 대기 시간. */
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

data class MeetupPlace(val id: String, val name: String, val latitude: Double, val longitude: Double)

enum class MeetupMode { AGENT, SMS }

/** [summary]는 운전 중 읽어 줄 짧은 요약. */
data class Mail(
    val id: String,
    val senderName: String,
    val senderEmail: String,
    val subject: String,
    val summary: String,
    val receivedAt: LocalDateTime,
)

data class PastOrder(
    val storeId: String,
    val storeName: String,
    val item: String,
    val pickupAt: LocalDateTime,
    val cancelled: Boolean,
)

enum class ErrandState { AWAITING_APPROVAL, IN_PROGRESS, COMPLETED, DECLINED, FAILED, CANCELLED, EXPIRED }

enum class ApprovalChannel { VOICE, TOUCH }

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
        /** 이미 한 번 미룬 예약에 또 늦는 경우. */
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

    /** SMS면 장소·시각이 답장 뒤 정해져 승인 전 [place]·[meetAt]은 null. 좌표는 출발 위치. */
    data class Meetup(
        val contact: Contact,
        val mode: MeetupMode,
        val place: MeetupPlace?,
        val meetAt: LocalDateTime?,
        val userTravelMinutes: Int?,
        val peerTravelMinutes: Int?,
        val preferredAt: LocalDateTime?,
        val latitude: Double?,
        val longitude: Double?,
    ) : ErrandDetail

    data class MailReply(val mail: Mail, val instruction: String, val draft: String) : ErrandDetail
}

/** 보낸 메일은 되돌릴 수 없어 음성 승인 대신 화면에서 확인하게 한다. */
val ErrandDetail.needsTouchApproval: Boolean get() = this is ErrandDetail.MailReply

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
    /** 되돌릴 때 쓰는 서버 쪽 id. 목업은 null. */
    val reference: String? = null,
    val undoing: Boolean = false,
    val undoneVia: ApprovalChannel? = null,
    /** 되돌리기 일부 실패 안내 또는 되돌리기 실패 사유. */
    val undoNote: String? = null,
) {
    val isActive: Boolean get() = state == ErrandState.AWAITING_APPROVAL || state == ErrandState.IN_PROGRESS

    /** 서버에서 일부 처리된 뒤 실패한 작업도 되돌릴 수 있다. */
    val undoable: Boolean
        get() = detail !is ErrandDetail.MailReply && (state == ErrandState.COMPLETED || (state == ErrandState.FAILED && reference != null))

    /** 정차를 기다리는 메일 초안은 오래 남아 있어도 다른 제안을 막지 않는다. */
    val blocksSuggestions: Boolean get() = isActive && !(state == ErrandState.AWAITING_APPROVAL && detail.needsTouchApproval)

    fun canUndo(now: LocalDateTime): Boolean =
        undoable && !undoing && finishedAt != null && Duration.between(finishedAt, now) <= UNDO_WINDOW

    /** 같은 요청이 다시 들어오면 새로 실행하지 않고 이 결과를 돌려준다. */
    fun handledRecently(now: LocalDateTime): Boolean = when (state) {
        ErrandState.IN_PROGRESS -> true
        ErrandState.COMPLETED, ErrandState.FAILED -> finishedAt != null && Duration.between(finishedAt, now) <= DUPLICATE_WINDOW
        else -> false
    }

    /** 답 없는 제안은 지난 시각으로 실행되거나 다른 제안을 막는다. 픽업은 준비 시각이 지나면 만료. */
    fun isStale(now: LocalDateTime): Boolean {
        // 메일 초안은 차를 세울 때까지 기다려야 하므로 짧게 만료시키지 않는다
        val ttl = if (detail is ErrandDetail.MailReply) DRAFT_TTL else PROPOSAL_TTL
        if (Duration.between(createdAt, now) >= ttl) return true
        val d = detail as? ErrandDetail.Pickup ?: return false
        val ready = createdAt.toLocalDate().atTime(d.readyAt).let { if (it.isBefore(createdAt)) it.plusDays(1) else it }
        return !now.isBefore(ready)
    }

    companion object {
        /** 매장이 조리를 시작하기 전, 식당이 자리를 다시 내주기 전에 무를 수 있는 시간. */
        val UNDO_WINDOW: Duration = Duration.ofMinutes(3)
        val PROPOSAL_TTL: Duration = Duration.ofMinutes(10)
        val DRAFT_TTL: Duration = Duration.ofHours(12)
        val DUPLICATE_WINDOW: Duration = Duration.ofMinutes(5)
    }
}
