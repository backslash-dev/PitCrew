package ai.pitcrew.app

import ai.pitcrew.app.errand.LateArrivalHandler
import ai.pitcrew.app.errand.PickupOnRouteHandler
import ai.pitcrew.app.gleo.GleoActionDispatcher
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.platform.PlatformServices
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PitCrewApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val platform: PlatformServices by lazy { PlatformServices(this) }

    val gleo: GleoActionDispatcher by lazy {
        val fetchDestination = platform.navigation::fetchDestination
        GleoActionDispatcher(
            mapOf(
                GleoFunction.NOTIFY_LATE_ARRIVAL to LateArrivalHandler(fetchDestination),
                GleoFunction.ORDER_PICKUP_ON_ROUTE to PickupOnRouteHandler(fetchDestination),
            ),
        )
    }
}
