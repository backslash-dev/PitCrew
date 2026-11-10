package ai.pitcrew.app.ui

import ai.pitcrew.app.PitCrewApplication
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.errand.PickupOutcome
import ai.pitcrew.app.errand.PickupPlanner
import ai.pitcrew.app.ui.errand.StoreOption
import ai.pitcrew.app.ui.components.NavRail
import ai.pitcrew.app.ui.components.RailItem
import ai.pitcrew.app.ui.diagnostics.DiagnosticsScreen
import ai.pitcrew.app.ui.driving.DrivingScreen
import ai.pitcrew.app.ui.errand.ErrandDetailScreen
import ai.pitcrew.app.ui.history.HistoryScreen
import ai.pitcrew.app.ui.home.HomeActions
import ai.pitcrew.app.ui.home.HomeScreen
import ai.pitcrew.app.ui.home.HomeState
import ai.pitcrew.app.ui.onboarding.OnboardingScreen
import ai.pitcrew.app.ui.reservations.ContactEditScreen
import ai.pitcrew.app.ui.reservations.ReservationEditScreen
import ai.pitcrew.app.ui.reservations.ReservationsScreen
import ai.pitcrew.app.ui.settings.SettingsScreen
import ai.pitcrew.app.ui.theme.PcTheme
import ai.pitcrew.app.ui.theme.windowWidthOf
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EventNote
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDateTime

private object Routes {
    const val HOME = "home"
    const val RESERVATIONS = "reservations"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val DIAGNOSTICS = "diagnostics"
    const val ERRAND = "errand/{id}"
    const val RESERVATION_EDIT = "reservation?id={id}"
    const val CONTACT_EDIT = "contact?id={id}"
    val topLevel = listOf(HOME, RESERVATIONS, HISTORY, SETTINGS)

    /** 하위 화면도 속한 탭을 레일에 표시한다. 작업 상세는 홈과 기록 어디서든 열리므로 홈으로 둔다. */
    fun sectionOf(route: String): String = when {
        route in topLevel -> route
        route.startsWith("reservation") || route.startsWith("contact") -> RESERVATIONS
        route == DIAGNOSTICS -> SETTINGS
        else -> HOME
    }

    fun errand(id: String) = "errand/$id"
    fun reservation(id: String?) = if (id == null) "reservation" else "reservation?id=$id"
    fun contact(id: String?) = if (id == null) "contact" else "contact?id=$id"
}

private const val CLOCK_TICK_MILLIS = 30_000L

@Composable
fun PitCrewApp(app: PitCrewApplication, locationGranted: Boolean, onRequestLocation: () -> Unit) {
    val onboarded by app.preferences.onboarded.collectAsStateWithLifecycle()
    if (!onboarded) {
        OnboardingScreen(locationGranted, onRequestLocation, onFinish = { app.preferences.setOnboarded(true) })
        return
    }

    val driving by app.platform.drivingState.state.collectAsStateWithLifecycle()
    val destination by app.platform.navigation.destination.collectAsStateWithLifecycle()
    val errands by app.errands.errands.collectAsStateWithLifecycle()
    val reservations by app.reservations.reservations.collectAsStateWithLifecycle()
    val contacts by app.contacts.contacts.collectAsStateWithLifecycle()
    val voice by app.voiceCommands.state.collectAsStateWithLifecycle()
    val listening by app.confirmer.listening.collectAsStateWithLifecycle()
    val proactive by app.preferences.proactive.collectAsStateWithLifecycle()
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            delay(CLOCK_TICK_MILLIS)
            value = LocalDateTime.now()
        }
    }

    // 규제 상태를 모를 때(차량 서비스 연결·재연결 중)도 주행 중일 수 있으므로 입력 화면 대신 주행 화면을 보인다
    if (driving.requiresDistractionOptimization != false) {
        DrivingScreen(destination, errands, now, listening)
        return
    }

    val nav = rememberNavController()
    val uiScope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val pending = errands.count { it.state == ErrandState.AWAITING_APPROVAL }
    val approve: (String, ApprovalChannel) -> Unit = { id, channel ->
        app.scope.launch {
            runCatching { app.errands.approve(id, channel) }.onFailure { Log.e("PitCrewApp", "approve failed", it) }
        }
    }
    val undo: (String) -> Unit = { id ->
        app.scope.launch {
            runCatching { app.errands.undo(id, ApprovalChannel.TOUCH) }.onFailure { Log.e("PitCrewApp", "undo failed", it) }
        }
    }

    Row(Modifier.fillMaxSize().background(PcTheme.colors.background)) {
        NavRail(
            items = listOf(
                RailItem(Routes.HOME, "홈", Icons.Rounded.Home, badge = pending),
                RailItem(Routes.RESERVATIONS, "예약", Icons.Rounded.EventNote),
                RailItem(Routes.HISTORY, "기록", Icons.Rounded.History),
                RailItem(Routes.SETTINGS, "설정", Icons.Rounded.Settings),
            ),
            selectedRoute = currentRoute?.let(Routes::sectionOf),
            onSelect = { route -> nav.navigateTopLevel(route) },
        )
        BoxWithConstraints(Modifier.weight(1f)) {
            val width = windowWidthOf(maxWidth.value)
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME) {
                    HomeScreen(
                        HomeState(driving, destination, errands, reservations, contacts, now, voice, listening),
                        HomeActions(
                            openErrand = { nav.navigate(Routes.errand(it)) },
                            approve = { approve(it, ApprovalChannel.TOUCH) },
                            decline = app.errands::decline,
                            openReservation = { nav.navigate(Routes.reservation(it)) },
                            openReservations = { nav.navigateTopLevel(Routes.RESERVATIONS) },
                            openHistory = { nav.navigateTopLevel(Routes.HISTORY) },
                            startVoice = app.voiceCommands::start,
                            cancelVoice = app.voiceCommands::cancel,
                            dismissVoice = app.voiceCommands::dismiss,
                            undo = undo,
                        ),
                        width,
                    )
                }
                composable(Routes.ERRAND, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                    val id = entry.arguments?.getString("id")
                    val errand = errands.firstOrNull { it.id == id }
                    val storeOptions by produceState(emptyList<StoreOption>(), errand?.id, destination?.remainTimeSeconds, now) {
                        val remain = destination?.remainTimeSeconds
                        value = if (remain == null || errand?.detail !is ErrandDetail.Pickup) {
                            emptyList()
                        } else {
                            runCatching { app.pickup.candidates() }.getOrDefault(emptyList()).map { StoreOption(it, PickupPlanner.readyAt(it, now, remain)) }
                        }
                    }
                    ErrandDetailScreen(
                        errand = errand,
                        mode = driving.interactionMode,
                        width = width,
                        onBack = { nav.popBackStack() },
                        onApprove = approve,
                        onDecline = app.errands::decline,
                        now = now,
                        listening = listening,
                        onUndo = undo,
                        storeOptions = storeOptions,
                        onSwitchStore = { store ->
                            val item = (errand?.detail as? ErrandDetail.Pickup)?.item ?: return@ErrandDetailScreen
                            uiScope.launch {
                                // 같은 픽업 제안은 새 제안으로 바뀌므로 새 작업 화면으로 갈아탄다
                                val outcome = app.pickup.switchStore(item, store)
                                if (outcome is PickupOutcome.AwaitingApproval) {
                                    nav.navigate(Routes.errand(outcome.errand.id)) { popUpTo(Routes.ERRAND) { inclusive = true } }
                                }
                            }
                        },
                    )
                }
                composable(Routes.RESERVATIONS) {
                    val syncError by app.reservations.error.collectAsStateWithLifecycle()
                    val contactError by app.contacts.error.collectAsStateWithLifecycle()
                    ReservationsScreen(
                        reservations, contacts,
                        syncError = syncError ?: contactError,
                        onRetry = { app.reservations.refresh(); app.contacts.refresh() },
                        onOpenReservation = { nav.navigate(Routes.reservation(it)) },
                        onNewReservation = { nav.navigate(Routes.reservation(null)) },
                        onOpenContact = { nav.navigate(Routes.contact(it)) },
                        onNewContact = { nav.navigate(Routes.contact(null)) },
                    )
                }
                composable(Routes.RESERVATION_EDIT, arguments = listOf(optionalId())) { entry ->
                    val id = entry.arguments?.getString("id")
                    ReservationEditScreen(
                        reservation = reservations.firstOrNull { it.id == id },
                        contacts = contacts,
                        now = now,
                        onSave = { app.reservations.upsert(it); nav.popBackStack() },
                        onDelete = { app.reservations.delete(it); nav.popBackStack() },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.CONTACT_EDIT, arguments = listOf(optionalId())) { entry ->
                    val id = entry.arguments?.getString("id")
                    ContactEditScreen(
                        contact = contacts.firstOrNull { it.id == id },
                        onSave = { app.contacts.upsert(it); nav.popBackStack() },
                        onDelete = { contactId ->
                            app.contacts.delete(contactId)
                            reservations.filter { contactId in it.companionIds }.forEach {
                                app.reservations.upsert(it.copy(companionIds = it.companionIds - contactId))
                            }
                            nav.popBackStack()
                        },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable(Routes.HISTORY) {
                    HistoryScreen(errands, now, onOpen = { nav.navigate(Routes.errand(it)) })
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        proactive = proactive,
                        onToggleProactive = { app.preferences.setProactive(!proactive) },
                        onReplayOnboarding = { app.preferences.setOnboarded(false) },
                        onOpenDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                    )
                }
                composable(Routes.DIAGNOSTICS) {
                    DiagnosticsScreen(app.platform, app.gleo, onBack = { nav.popBackStack() })
                }
            }
        }
    }
}

private fun optionalId() = navArgument("id") {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

private fun NavHostController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(Routes.HOME) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
