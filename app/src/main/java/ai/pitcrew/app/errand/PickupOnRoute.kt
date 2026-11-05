package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Store
import ai.pitcrew.app.gleo.GleoActionHandler
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoResponse
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.platform.SdkUnavailableException
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

object PickupPlanner {
    private const val ROUND_MINUTES = 5L

    /** 매장이 경로 중간쯤에 있다고 보고, 도착과 조리 완료 중 늦은 시각을 5분 단위로 올린다. */
    fun readyAt(store: Store, now: LocalDateTime, remainSeconds: Int): LocalTime {
        val arriveAtStore = now.plusSeconds(remainSeconds.coerceAtLeast(0) / 2L).plusMinutes(store.detourMinutes.toLong())
        val prepared = now.plusMinutes(store.prepMinutes.toLong())
        val ready = maxOf(arriveAtStore, prepared).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
        val remainder = ready.minute % ROUND_MINUTES
        return (if (remainder == 0L) ready else ready.plusMinutes(ROUND_MINUTES - remainder)).toLocalTime()
    }
}

class PickupOnRouteHandler(
    private val fetchDestination: suspend () -> Destination?,
    private val stores: StoreCatalog,
    private val errands: ErrandService,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : GleoActionHandler {
    private val function = GleoFunction.ORDER_PICKUP_ON_ROUTE

    override suspend fun handle(request: GleoRequest): GleoResponse {
        val item = request.params.optString("item").trim()
        if (item.isEmpty()) {
            return GleoResponse.asking(
                function,
                "The food or drink to pick up is required.",
                listOf(mapOf("missingField" to "item")),
            )
        }

        val destination = try {
            fetchDestination()
        } catch (e: SdkUnavailableException) {
            return GleoResponse.fail(function, "The navigation service is not available, so the route is unknown.")
        } ?: return GleoResponse.fail(function, "There is no active navigation route to find a pickup store along.")

        val storeName = request.params.optString("storeName").trim().takeIf { it.isNotEmpty() }
        val store = stores.findOnRoute(storeName)
            ?: return GleoResponse.fail(function, "No pickup store matching the request was found along the route.")

        val readyAt = PickupPlanner.readyAt(store, now(), destination.remainTimeSeconds)
        val errand = errands.propose(ErrandDetail.Pickup(item, store, readyAt))
        val data = listOf(
            mapOf(
                "item" to item,
                "store" to store.displayName,
                "pickupTime" to readyAt.toClockTime(),
                "detourMinutes" to store.detourMinutes,
            ),
        )

        if (!request.params.optBoolean("confirmed", false)) {
            return GleoResponse.asking(
                function,
                "User approval is required to order $item from ${store.displayName} for pickup at ${readyAt.toClockTime()}, " +
                    "which adds about ${store.detourMinutes} minutes to the route.",
                data,
            )
        }

        val result = errands.approve(errand.id, ApprovalChannel.VOICE)
        return when (result.state) {
            ErrandState.COMPLETED -> GleoResponse.success(
                function,
                "Ordered $item from ${store.displayName} for pickup at ${readyAt.toClockTime()} and added the store as a waypoint.",
                data,
            )
            ErrandState.DECLINED -> GleoResponse.fail(function, "The user declined this order on the screen.")
            else -> GleoResponse.fail(function, "Placing the pickup order failed: ${result.failureReason ?: "unknown error"}.")
        }
    }
}
