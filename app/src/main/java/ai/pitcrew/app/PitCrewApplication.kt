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
import ai.pitcrew.app.errand.LateArrivalUseCase
import ai.pitcrew.app.errand.PickupOnRouteHandler
import ai.pitcrew.app.errand.PickupUseCase
import ai.pitcrew.app.errand.RuleBasedInterpreter
import ai.pitcrew.app.gleo.GleoActionDispatcher
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.platform.PlatformServices
import ai.pitcrew.app.ui.voice.VoiceAnnouncer
import ai.pitcrew.app.ui.voice.VoiceCommandController
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

    val lateArrival: LateArrivalUseCase by lazy {
        LateArrivalUseCase(platform.navigation::fetchDestination, errands, reservations, contacts)
    }
    val pickup: PickupUseCase by lazy { PickupUseCase(platform.navigation::fetchDestination, stores, errands) }

    val voiceCommands: VoiceCommandController by lazy {
        VoiceCommandController(
            speech = platform.voiceInput,
            interpreter = RuleBasedInterpreter { stores.candidatesOnRoute().map { it.displayName } },
            lateArrival = lateArrival,
            pickup = pickup,
            scope = scope,
        )
    }

    val announcer: VoiceAnnouncer by lazy { VoiceAnnouncer(errands, platform.navigation, platform.voiceOutput, scope) }

    val gleo: GleoActionDispatcher by lazy {
        GleoActionDispatcher(
            mapOf(
                GleoFunction.NOTIFY_LATE_ARRIVAL to LateArrivalHandler(lateArrival),
                GleoFunction.ORDER_PICKUP_ON_ROUTE to PickupOnRouteHandler(pickup),
            ),
        )
    }
}
