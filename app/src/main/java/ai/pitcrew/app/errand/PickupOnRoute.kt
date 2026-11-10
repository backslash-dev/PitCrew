package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
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

sealed interface PickupOutcome {
    data object NoRoute : PickupOutcome
    data object NavigationUnavailable : PickupOutcome
    data object NoStore : PickupOutcome
    data class AwaitingApproval(val errand: Errand) : PickupOutcome
    data class Finished(val errand: Errand) : PickupOutcome
}

/** 경로상 픽업 주문. Gleo·앱 음성 입력·매장 변경이 같이 쓴다. */
class PickupUseCase(
    private val fetchDestination: suspend () -> Destination?,
    private val stores: StoreCatalog,
    private val errands: ErrandService,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    suspend fun run(item: String, storeName: String? = null, confirmed: Boolean = false, channel: ApprovalChannel = ApprovalChannel.VOICE): PickupOutcome {
        val store = stores.findOnRoute(storeName) ?: return PickupOutcome.NoStore
        return order(item, store, confirmed, channel)
    }

    /** 승인 전 제안의 매장을 바꾼다. 픽업 시각은 새 매장 기준으로 다시 계산한다. */
    suspend fun switchStore(item: String, store: Store): PickupOutcome = order(item, store, confirmed = false, channel = ApprovalChannel.TOUCH)

    suspend fun candidates(): List<Store> = stores.candidatesOnRoute()

    /** 자주 시키는 메뉴를 PitCrew가 먼저 제안한다. */
    suspend fun suggest(item: String, store: Store): PickupOutcome =
        order(item, store, confirmed = false, channel = ApprovalChannel.VOICE, origin = ErrandOrigin.SUGGESTED)

    private suspend fun order(
        item: String,
        store: Store,
        confirmed: Boolean,
        channel: ApprovalChannel,
        origin: ErrandOrigin = ErrandOrigin.REQUESTED,
    ): PickupOutcome {
        val destination = try {
            fetchDestination()
        } catch (e: SdkUnavailableException) {
            return PickupOutcome.NavigationUnavailable
        } ?: return PickupOutcome.NoRoute
        // 화면에서 이미 승인한 뒤 Gleo에도 "응"이라고 한 경우·Gleo 재시도: 같은 메뉴를 방금 주문했으면 다시 주문하지 않는다
        if (confirmed) {
            errands.errands.value.firstOrNull { (it.detail as? ErrandDetail.Pickup)?.item == item && it.handledRecently(now()) }
                ?.let { return PickupOutcome.Finished(it) }
        }
        // 같은 메뉴를 먼저 물은 제안이 없으면 확인이 붙어 와도(Gleo가 첫 요청부터 confirmed를 붙인 경우) 다시 묻는다
        val asked = errands.errands.value.any {
            it.state == ErrandState.AWAITING_APPROVAL && (it.detail as? ErrandDetail.Pickup)?.item == item
        }
        val readyAt = PickupPlanner.readyAt(store, now(), destination.remainTimeSeconds)
        val errand = errands.propose(ErrandDetail.Pickup(item, store, readyAt), origin)
        if (!confirmed || !asked) return PickupOutcome.AwaitingApproval(errand)
        return PickupOutcome.Finished(errands.approve(errand.id, channel))
    }
}

class PickupOnRouteHandler(private val useCase: PickupUseCase) : GleoActionHandler {
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
        val storeName = request.params.optString("storeName").trim().takeIf { it.isNotEmpty() }
        val confirmed = request.params.optBoolean("confirmed", false)

        return when (val outcome = useCase.run(item, storeName, confirmed, ApprovalChannel.VOICE)) {
            PickupOutcome.NavigationUnavailable ->
                GleoResponse.fail(function, "The navigation service is not available, so the route is unknown.")
            PickupOutcome.NoRoute ->
                GleoResponse.fail(function, "There is no active navigation route to find a pickup store along.")
            PickupOutcome.NoStore ->
                GleoResponse.fail(function, "No pickup store matching the request was found along the route.")
            is PickupOutcome.AwaitingApproval -> {
                val d = outcome.errand.detail as ErrandDetail.Pickup
                GleoResponse.asking(
                    function,
                    "User approval is required to order ${d.item} from ${d.store.displayName} for pickup at ${d.readyAt.toClockTime()}, " +
                        "which adds about ${d.store.detourMinutes} minutes to the route.",
                    d.toData(),
                )
            }
            is PickupOutcome.Finished -> {
                val d = outcome.errand.detail as ErrandDetail.Pickup
                when (outcome.errand.state) {
                    ErrandState.COMPLETED -> GleoResponse.success(
                        function,
                        "Ordered ${d.item} from ${d.store.displayName} for pickup at ${d.readyAt.toClockTime()} and added the store as a waypoint.",
                        d.toData(),
                    )
                    ErrandState.IN_PROGRESS -> GleoResponse.success(function, "This pickup order is already being processed. The result will be shown in the PitCrew app.")
                    ErrandState.DECLINED -> GleoResponse.fail(function, "The user declined this order on the screen.")
                    else -> GleoResponse.fail(function, "Placing the pickup order failed: ${outcome.errand.failureReason ?: "unknown error"}.")
                }
            }
        }
    }

    private fun ErrandDetail.Pickup.toData(): List<Map<String, Any>> = listOf(
        mapOf(
            "item" to item,
            "store" to store.displayName,
            "pickupTime" to readyAt.toClockTime(),
            "detourMinutes" to store.detourMinutes,
        ),
    )
}
