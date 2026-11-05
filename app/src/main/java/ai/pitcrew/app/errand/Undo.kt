package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.latestUndoable
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.gleo.GleoActionHandler
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoResponse
import java.time.LocalDateTime

sealed interface UndoOutcome {
    data object NothingToUndo : UndoOutcome
    data class AwaitingConfirmation(val errand: Errand) : UndoOutcome
    data class Finished(val errand: Errand) : UndoOutcome
}

/** 방금 끝낸 작업 되돌리기. 운전 중 오인식으로 잘못 승인한 경우의 안전장치다. */
class UndoUseCase(private val errands: ErrandService, private val now: () -> LocalDateTime = LocalDateTime::now) {
    suspend fun run(confirmed: Boolean, channel: ApprovalChannel): UndoOutcome {
        val target = errands.latestUndoable(now()) ?: return UndoOutcome.NothingToUndo
        if (!confirmed) return UndoOutcome.AwaitingConfirmation(target)
        return UndoOutcome.Finished(errands.undo(target.id, channel))
    }
}

class CancelLastErrandHandler(private val useCase: UndoUseCase) : GleoActionHandler {
    private val function = GleoFunction.CANCEL_LAST_ERRAND

    override suspend fun handle(request: GleoRequest): GleoResponse {
        val confirmed = request.params.optBoolean("confirmed", false)
        return when (val outcome = useCase.run(confirmed, ApprovalChannel.VOICE)) {
            UndoOutcome.NothingToUndo -> GleoResponse.fail(
                function,
                "There is no PitCrew errand completed in the last few minutes that can still be undone.",
            )
            is UndoOutcome.AwaitingConfirmation -> GleoResponse.asking(
                function,
                "User approval is required to ${outcome.errand.undoDescription()}.",
                listOf(outcome.errand.toData()),
            )
            is UndoOutcome.Finished -> when (outcome.errand.state) {
                ErrandState.CANCELLED -> GleoResponse.success(
                    function,
                    buildString {
                        append("Done: PitCrew will ${outcome.errand.undoDescription()}.")
                        if (outcome.errand.undoNote != null) append(" Part of it could not be completed, and the details are shown in the PitCrew app.")
                    },
                    listOf(outcome.errand.toData()),
                )
                else -> GleoResponse.fail(function, "Undoing the errand failed: ${outcome.errand.undoNote ?: "unknown error"}.")
            }
        }
    }

    private fun Errand.undoDescription(): String = when (val d = detail) {
        is ErrandDetail.LateArrival -> buildString {
            append("restore the reservation${d.placeName?.let { " at $it" }.orEmpty()} to ${d.appointment.toLocalTime().toClockTime()}")
            if (d.companions.isNotEmpty()) append(" and send a correction to ${d.companions.size} companions")
        }
        is ErrandDetail.Pickup -> "cancel the ${d.item} pickup order at ${d.store.displayName} and remove the waypoint"
        is ErrandDetail.ChargeAndDine -> "cancel the waitlist at ${d.restaurant.name} and remove the charging stop at ${d.charger.name}"
    }

    private fun Errand.toData(): Map<String, Any> = when (val d = detail) {
        is ErrandDetail.LateArrival -> buildMap {
            put("type", "reservationChange")
            d.placeName?.let { put("place", it) }
            put("restoreTime", d.appointment.toLocalTime().toClockTime())
        }
        is ErrandDetail.Pickup -> mapOf("type" to "pickupOrder", "item" to d.item, "store" to d.store.displayName)
        is ErrandDetail.ChargeAndDine -> mapOf("type" to "chargeAndDine", "restaurant" to d.restaurant.name, "charger" to d.charger.name)
    }
}
