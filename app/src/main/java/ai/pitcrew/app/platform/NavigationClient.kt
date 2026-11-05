package ai.pitcrew.app.platform

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.DestinationInfo
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

    private val listener = object : NaviHelperEventListener {
        override fun onRouteStarted(info: RouteStartInfo) = refreshDestination()
        override fun onRoutingCompleted() = refreshDestination()
        override fun onRouteCancelled() { _destination.value = null }
        override fun onRouteEnded() { _destination.value = null }
        override fun onDestinationChanged() = refreshDestination()
        override fun onDestinationInfo(info: DestinationInfo) {
            val destination = info.toDestination()
            _destination.value = destination
            destinationReplies.tryEmit(destination)
        }
        override fun onWayPointInfo(list: List<WaypointInfo>) {}
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

    override fun close() {
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
    }

    private companion object {
        const val TAG = "NavigationClient"
    }
}

// 경로가 없으면 SDK가 null 대신 이름이 비고 거리가 0인 값을 준다
internal fun DestinationInfo.toDestination(): Destination? =
    if (name.isBlank() && remainDistance <= 0) null else Destination(name, remainTime, remainDistance)
