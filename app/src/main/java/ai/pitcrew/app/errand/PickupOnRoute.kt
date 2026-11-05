package ai.pitcrew.app.errand

import ai.pitcrew.app.gleo.GleoActionHandler
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoResponse
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.platform.SdkUnavailableException

class PickupOnRouteHandler(
    private val fetchDestination: suspend () -> Destination?,
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

        return GleoResponse.fail(
            function,
            "Searching for pickup stores along the route is not available yet.",
            listOf(
                buildMap {
                    put("item", item)
                    request.params.optString("storeName").trim().takeIf { it.isNotEmpty() }?.let { put("storeName", it) }
                    put("destination", destination.name)
                    put("remainMinutes", destination.remainTimeSeconds / 60)
                },
            ),
        )
    }
}
