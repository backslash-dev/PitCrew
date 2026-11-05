package ai.pitcrew.app

import ai.pitcrew.app.data.AppPreferences
import ai.pitcrew.app.data.ContactRepository
import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.ReservationRepository
import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.data.mock.InMemoryContactRepository
import ai.pitcrew.app.data.mock.InMemoryReservationRepository
import ai.pitcrew.app.data.mock.MockErrandService
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.data.mock.SampleData
import ai.pitcrew.app.errand.LateArrivalHandler
import ai.pitcrew.app.errand.PickupOnRouteHandler
import ai.pitcrew.app.gleo.GleoActionDispatcher
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.platform.PlatformServices
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.LocalDateTime

class PitCrewApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val platform: PlatformServices by lazy { PlatformServices(this) }
    val preferences: AppPreferences by lazy { AppPreferences(this) }

    val contacts: ContactRepository by lazy { InMemoryContactRepository() }
    val reservations: ReservationRepository by lazy { InMemoryReservationRepository(SampleData.reservations(LocalDateTime.now())) }
    val stores: StoreCatalog by lazy { MockStoreCatalog() }
    val errands: ErrandService by lazy {
        MockErrandService(addWaypoint = { store -> platform.navigation.addWaypoint(store.displayName, store.latitude, store.longitude) })
    }

    val gleo: GleoActionDispatcher by lazy {
        val fetchDestination = platform.navigation::fetchDestination
        GleoActionDispatcher(
            mapOf(
                GleoFunction.NOTIFY_LATE_ARRIVAL to LateArrivalHandler(fetchDestination, errands, reservations, contacts),
                GleoFunction.ORDER_PICKUP_ON_ROUTE to PickupOnRouteHandler(fetchDestination, stores, errands),
            ),
        )
    }
}
