package ai.pitcrew.app

import ai.pitcrew.app.data.AppPreferences
import ai.pitcrew.app.data.ChargerCatalog
import ai.pitcrew.app.data.ContactRepository
import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.MailService
import ai.pitcrew.app.data.MeetupService
import ai.pitcrew.app.data.OrderHistory
import ai.pitcrew.app.data.ReservationRepository
import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.data.WaypointEditor
import ai.pitcrew.app.data.local.FileErrandStore
import ai.pitcrew.app.data.mock.InMemoryContactRepository
import ai.pitcrew.app.data.mock.InMemoryReservationRepository
import ai.pitcrew.app.data.mock.MockChargerCatalog
import ai.pitcrew.app.data.mock.MockMailService
import ai.pitcrew.app.data.mock.MockMeetupService
import ai.pitcrew.app.data.mock.MockOrderHistory
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.data.mock.SampleData
import ai.pitcrew.app.data.remote.PitCrewApi
import ai.pitcrew.app.data.remote.RemoteChargerCatalog
import ai.pitcrew.app.data.remote.RemoteContactRepository
import ai.pitcrew.app.data.remote.RemoteMailService
import ai.pitcrew.app.data.remote.RemoteMeetupService
import ai.pitcrew.app.data.remote.RemoteOrderHistory
import ai.pitcrew.app.data.remote.RemoteReservationRepository
import ai.pitcrew.app.data.remote.RemoteStepExecutor
import ai.pitcrew.app.data.remote.RemoteStoreCatalog
import ai.pitcrew.app.errand.CancelLastErrandHandler
import ai.pitcrew.app.errand.ChargeAndDineUseCase
import ai.pitcrew.app.errand.DriverRequestHandler
import ai.pitcrew.app.errand.MailReplyUseCase
import ai.pitcrew.app.errand.MeetupUseCase
import ai.pitcrew.app.errand.LateArrivalHandler
import ai.pitcrew.app.errand.LateArrivalUseCase
import ai.pitcrew.app.errand.PickupOnRouteHandler
import ai.pitcrew.app.errand.PickupUseCase
import ai.pitcrew.app.errand.RuleBasedInterpreter
import ai.pitcrew.app.errand.UndoUseCase
import ai.pitcrew.app.gleo.GleoActionDispatcher
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.platform.PlatformServices
import ai.pitcrew.app.ui.voice.ProactiveAssistant
import ai.pitcrew.app.ui.voice.VoiceAnnouncer
import ai.pitcrew.app.ui.voice.VoiceCommandController
import ai.pitcrew.app.ui.voice.VoiceConfirmer
import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.time.LocalDateTime

class PitCrewApplication : Application() {
    // 놓친 예외 하나로 앱 전체가 종료되지 않게 기록만 한다
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("PitCrewApplication", "uncaught in app scope", e) },
    )

    val platform: PlatformServices by lazy { PlatformServices(this) }
    val preferences: AppPreferences by lazy { AppPreferences(this) }

    /** 서버 주소가 없으면(release 기본값) 앱 안 목업으로 동작한다. */
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
    private val chargers: ChargerCatalog by lazy { api?.let(::RemoteChargerCatalog) ?: MockChargerCatalog() }
    private val mockMeetups: MockMeetupService by lazy { MockMeetupService({ reservations.reservations.value }) }
    private val mockMail: MockMailService by lazy { MockMailService.demo(LocalDateTime.now()) }
    private val meetups: MeetupService by lazy { api?.let(::RemoteMeetupService) ?: mockMeetups }
    val mails: MailService by lazy { api?.let(::RemoteMailService) ?: mockMail }
    private val orderHistory: OrderHistory by lazy { api?.let(::RemoteOrderHistory) ?: MockOrderHistory.routine(LocalDateTime.now()) }

    private val waypoints = object : WaypointEditor {
        override suspend fun add(name: String, latitude: Double, longitude: Double) = platform.navigation.addWaypoint(name, latitude, longitude)
        override suspend fun remove(name: String, latitude: Double, longitude: Double) = platform.navigation.removeWaypoint(name, latitude, longitude)
    }

    val errands: ErrandService by lazy {
        val remote = api
        val executor = if (remote != null) {
            RemoteStepExecutor(remote, reloadReservations = { reservations.refresh() }, waypoints = waypoints, reloadMails = { mails.refreshNow() })
        } else {
            MockStepExecutor(waypoints, mockMeetups, reservations, mockMail)
        }
        // 목업은 처리 시간이 없으므로 진행이 보이도록 단계 사이를 띄운다
        ErrandRunner(executor, stepDelayMillis = if (remote != null) 0 else 600, workScope = scope, store = FileErrandStore(File(filesDir, "errands.json")))
    }

    val lateArrival: LateArrivalUseCase by lazy {
        LateArrivalUseCase(platform.navigation::fetchDestination, errands, reservations, contacts)
    }
    val pickup: PickupUseCase by lazy { PickupUseCase(platform.navigation::fetchDestination, stores, errands) }
    val charging: ChargeAndDineUseCase by lazy {
        ChargeAndDineUseCase(platform.navigation::fetchDestination, chargers, { platform.battery.stateOfChargePercent.value }, errands)
    }
    val undo: UndoUseCase by lazy { UndoUseCase(errands) }
    val meetup: MeetupUseCase by lazy { MeetupUseCase(contacts, meetups, { platform.location.location.value }, errands) }
    val mailReply: MailReplyUseCase by lazy { MailReplyUseCase(mails, errands) }

    private val interpreter: RuleBasedInterpreter by lazy {
        RuleBasedInterpreter(
            storeNames = { stores.candidatesOnRoute().map { it.displayName } },
            contactNames = { contacts.ensureLoaded(); contacts.contacts.value.map { it.name } },
        )
    }

    val voiceCommands: VoiceCommandController by lazy {
        VoiceCommandController(
            speech = platform.voiceInput,
            interpreter = interpreter,
            lateArrival = lateArrival,
            pickup = pickup,
            charging = charging,
            undo = undo,
            meetup = meetup,
            mail = mailReply,
            errands = errands,
            confirmer = confirmer,
            driving = { platform.drivingState.state.value.requiresDistractionOptimization != false },
            scope = scope,
        )
    }

    val announcer: VoiceAnnouncer by lazy { VoiceAnnouncer(errands, platform.navigation, platform.voiceOutput, scope) }

    val confirmer: VoiceConfirmer by lazy { VoiceConfirmer(platform.voiceOutput, platform.voiceInput) }

    val assistant: ProactiveAssistant by lazy {
        ProactiveAssistant(
            scope = scope,
            enabled = preferences.proactive,
            fetchDestination = platform.navigation::fetchDestination,
            batteryPercent = { platform.battery.stateOfChargePercent.value },
            reservations = reservations,
            orders = orderHistory,
            stores = stores,
            errands = errands,
            lateArrival = lateArrival,
            pickup = pickup,
            charging = charging,
            mails = mails,
            mailReply = mailReply,
            confirmer = confirmer,
        )
    }

    private val lateHandler by lazy { LateArrivalHandler(lateArrival) }
    private val pickupHandler by lazy { PickupOnRouteHandler(pickup) }

    val gleo: GleoActionDispatcher by lazy {
        GleoActionDispatcher(
            mapOf(
                GleoFunction.NOTIFY_LATE_ARRIVAL to lateHandler,
                GleoFunction.ORDER_PICKUP_ON_ROUTE to pickupHandler,
                GleoFunction.HANDLE_PITCREW_REQUEST to DriverRequestHandler(
                    interpreter = interpreter,
                    errands = errands,
                    undo = CancelLastErrandHandler(undo),
                    lateArrival = lateHandler,
                    pickup = pickupHandler,
                    meetup = meetup,
                    mail = mailReply,
                    charging = charging,
                ),
            ),
        )
    }
}
