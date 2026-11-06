package ai.pitcrew.app.platform

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

data class WaypointStatus(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val remainTimeSeconds: Int,
    val remainDistanceMeters: Int,
) {
    /** 내비가 이름을 POI 이름으로 바꿔 돌려줄 수 있어 좌표가 가까워도 같은 곳으로 본다. */
    fun isAt(name: String, latitude: Double, longitude: Double): Boolean =
        this.name == name || (abs(this.latitude - latitude) < SAME_PLACE_DEGREES && abs(this.longitude - longitude) < SAME_PLACE_DEGREES)

    private companion object {
        const val SAME_PLACE_DEGREES = 0.002
    }
}

data class Destination(
    val name: String,
    // 문서에 단위가 없음. 에뮬레이터 실측값(약 1시간 경로에 3600)으로 초 단위로 판단
    val remainTimeSeconds: Int,
    val remainDistanceMeters: Int,
)

class NavigationClient(context: Context) : AutoCloseable {
    private val navi = NaviHelper(context)

    private val _status = MutableStateFlow<SdkStatus>(SdkStatus.Connecting)
    val status: StateFlow<SdkStatus> = _status.asStateFlow()

    private val _destination = MutableStateFlow<Destination?>(null)
    val destination: StateFlow<Destination?> = _destination.asStateFlow()

    private val destinationReplies = MutableSharedFlow<Destination?>(extraBufferCapacity = 1)

    private val _waypoints = MutableStateFlow<List<WaypointStatus>>(emptyList())
    val waypoints: StateFlow<List<WaypointStatus>> = _waypoints.asStateFlow()

    private val waypointReplies = MutableSharedFlow<List<WaypointStatus>>(extraBufferCapacity = 1)

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) = refreshDestination()
        override fun onRoutingCompleted() = refreshDestination()
        override fun onRouteCancelled() {
            _destination.value = null
            _waypoints.value = emptyList()
        }
        override fun onRouteEnded() {
            _destination.value = null
            _waypoints.value = emptyList()
        }
        override fun onDestinationChanged() = refreshDestination()
        override fun onDestinationInfo(info: DestinationInfo) {
            val destination = info.toDestination()
            _destination.value = destination
            destinationReplies.tryEmit(destination)
        }
        override fun onWayPointInfo(list: List<WaypointInfo>) {
            val waypoints = list.map { WaypointStatus(it.name, it.latitude, it.longitude, it.remainTime, it.remainDistance) }
            _waypoints.value = waypoints
            waypointReplies.tryEmit(waypoints)
        }
        override fun onRouteStateInfo(info: RouteStateInfo) {}
        override fun onTripInfo(info: TripInfo) {}
        override fun onTBTInfo(list: List<TBTInfo>) {}
        override fun onNavigationStatus(status: NavigationStatus) {}
        override fun onError(code: NaviErrorCode) {
            Log.w(TAG, "navi error $code")
        }
    }

    fun start() {
        runCatching {
            navi.initialize(
                {
                    _status.value = SdkStatus.Ready
                    refreshDestination()
                },
                {
                    Log.e(TAG, "navi init failed", it)
                    _status.value = SdkStatus.Failed(it.message ?: it.javaClass.simpleName)
                },
            )
            navi.addListener(listener)
        }.onFailure {
            Log.e(TAG, "navi init failed", it)
            _status.value = SdkStatus.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    fun refreshDestination() {
        if (_status.value != SdkStatus.Ready) return
        runCatching { navi.getDestinationInfo() }.onFailure { Log.w(TAG, "destination query failed", it) }
    }

    fun refreshWaypoints() {
        if (_status.value != SdkStatus.Ready) return
        runCatching { navi.getWaypointInfo() }.onFailure { Log.w(TAG, "waypoint query failed", it) }
    }

    /**
     * 내비에 현재 목적지를 물어 응답을 기다린다. 경로가 없으면 null.
     * @throws SdkUnavailableException 내비 SDK 초기화에 실패했을 때
     */
    suspend fun fetchDestination(): Destination? {
        val status = status.first { it != SdkStatus.Connecting }
        if (status is SdkStatus.Failed) throw SdkUnavailableException("navigation", status.reason)
        return coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) { destinationReplies.first() }
            navi.getDestinationInfo()
            reply.await()
        }
    }

    /** 목적지로 경로 안내를 요청한다. 결과 콜백이 없어 요청이 예외 없이 전달됐는지만 확인한다. */
    fun requestRoute(name: String, latitude: Double, longitude: Double): Boolean {
        if (_status.value != SdkStatus.Ready) return false
        return runCatching {
            navi.requestRoute(RouteInfo(latitude, longitude, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false))
        }.onFailure { Log.w(TAG, "request route failed", it) }.isSuccess
    }

    /** 경유지 추가는 결과 콜백이 없어 요청이 예외 없이 전달됐는지만 확인한다. 실패는 onError로 로그에 남는다. */
    fun addWaypoint(name: String, latitude: Double, longitude: Double): Boolean {
        if (_status.value != SdkStatus.Ready) return false
        return runCatching {
            navi.addWaypoint(
                RequestWaypointInfo(
                    latitude = latitude,
                    longitude = longitude,
                    poiName = name,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    address = "",
                    poiSubId = "",
                ),
            )
        }.onFailure { Log.w(TAG, "add waypoint failed", it) }.isSuccess
    }

    /**
     * 추가했던 경유지를 뺀다. SDK는 순번(첫째·둘째)으로만 지우므로 현재 경유지 목록에서 위치를 찾는다.
     * 목록에 없으면 이미 빠진 것으로 본다. 목록을 받지 못했거나 셋째 이후면 false.
     */
    suspend fun removeWaypoint(name: String, latitude: Double, longitude: Double): Boolean {
        if (_status.value != SdkStatus.Ready) return false
        val current = withTimeoutOrNull(WAYPOINT_REPLY_MILLIS) {
            coroutineScope {
                val reply = async(start = CoroutineStart.UNDISPATCHED) { waypointReplies.first() }
                navi.getWaypointInfo()
                reply.await()
            }
        } ?: return false
        val target = when (current.indexOfFirst { it.isAt(name, latitude, longitude) }) {
            -1 -> return true
            0 -> WaypointIndex.FIRST
            1 -> WaypointIndex.SECOND
            else -> return false
        }
        return runCatching { navi.removeWaypoint(target) }.onFailure { Log.w(TAG, "remove waypoint failed", it) }.isSuccess
    }

    override fun close() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    private companion object {
        const val TAG = "NavigationClient"
        const val WAYPOINT_REPLY_MILLIS = 2_000L
    }
}

// 경로가 없으면 SDK가 null 대신 이름이 비고 거리가 0인 값을 준다
internal fun DestinationInfo.toDestination(): Destination? =
    if (name.isBlank() && remainDistance <= 0) null else Destination(name, remainTime, remainDistance)
