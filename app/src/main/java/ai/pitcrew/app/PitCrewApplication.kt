package ai.pitcrew.app

import ai.pitcrew.app.data.AppPreferences
import ai.pitcrew.app.data.ContactRepository
import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.ReservationRepository
import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.data.mock.InMemoryContactRepository
import ai.pitcrew.app.data.mock.InMemoryReservationRepository
import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.data.remote.PitCrewApi
import ai.pitcrew.app.data.remote.RemoteContactRepository
import ai.pitcrew.app.data.remote.RemoteReservationRepository
import ai.pitcrew.app.data.remote.RemoteStepExecutor
import ai.pitcrew.app.data.remote.RemoteStoreCatalog
import ai.pitcrew.app.domain.Store
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

    /** 서버 주소가 없으면(release 기본값) 앱 안 목업으로 동작한다. debug는 에뮬레이터 호스트의 로컬 서버를 쓴다. */
    private val api: PitCrewApi? by lazy {
        BuildConfig.SERVER_URL.takeIf { it.isNotBlank() }?.let { PitCrewApi(it, BuildConfig.API_TOKEN) }
    }

    val contacts: ContactRepository by lazy {
        api?.let { RemoteContactRepository(it, scope).also(RemoteContactRepository::refresh) } ?: InMemoryContactRepository()
    }
    val reservations: ReservationRepository by lazy {
        api?.let { RemoteReservationRepository(it, scope).also(RemoteReservationRepository::refresh) }
            ?: InMemoryReservationRepository(SampleData.reservations(LocalDateTime.now()))
    }
    val stores: StoreCatalog by lazy { api?.let(::RemoteStoreCatalog) ?: MockStoreCatalog() }
    val errands: ErrandService by lazy {
        val addWaypoint: suspend (Store) -> Boolean = { store -> platform.navigation.addWaypoint(store.displayName, store.latitude, store.longitude) }
        val remote = api
        val executor = if (remote != null) {
            RemoteStepExecutor(remote, reloadReservations = { reservations.refresh() }, addWaypoint = addWaypoint)
        } else {
            MockStepExecutor(addWaypoint)
        }
        // 목업은 처리 시간이 없으므로 진행이 보이도록 단계 사이를 띄운다
        ErrandRunner(executor, stepDelayMillis = if (remote != null) 0 else 600)
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
