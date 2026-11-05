package ai.pitcrew.app.domain

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

enum class ErrandState { AWAITING_APPROVAL, IN_PROGRESS, COMPLETED, DECLINED, FAILED }

enum class ApprovalChannel { VOICE, TOUCH }

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
    ) : ErrandDetail

    data class Pickup(
        val item: String,
        val store: Store,
        val readyAt: LocalTime,
    ) : ErrandDetail
}

data class Errand(
    val id: String,
    val createdAt: LocalDateTime,
    val state: ErrandState,
    val detail: ErrandDetail,
    val steps: List<ErrandStep>,
    val approvedVia: ApprovalChannel? = null,
    val finishedAt: LocalDateTime? = null,
    val failureReason: String? = null,
) {
    val isActive: Boolean get() = state == ErrandState.AWAITING_APPROVAL || state == ErrandState.IN_PROGRESS
}
