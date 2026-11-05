package ai.pitcrew.app.platform

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GeoPoint(val latitude: Double, val longitude: Double, val heading: Float)

class LocationClient(private val context: Context) : AutoCloseable {
    private val fused = FusedLocation(context)
    private var started = false

    private val _status = MutableStateFlow<SdkStatus>(SdkStatus.Connecting)
    val status: StateFlow<SdkStatus> = _status.asStateFlow()

    private val _location = MutableStateFlow<GeoPoint?>(null)
    val location: StateFlow<GeoPoint?> = _location.asStateFlow()

    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            _status.value = SdkStatus.Ready
            _location.value = GeoPoint(location.latitude, location.longitude, location.heading)
        }

        override fun onError(exception: Exception) {
            Log.w(TAG, "location error", exception)
            _status.value = SdkStatus.Failed(exception.message ?: exception.javaClass.simpleName)
        }
    }

    fun hasPermission(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** 런타임 권한 허용 후 다시 불러도 된다. 이미 시작했으면 아무것도 하지 않는다. */
    fun start() {
        if (started) return
        if (!hasPermission()) {
            _status.value = SdkStatus.Failed("위치 권한 없음")
            return
        }
        runCatching {
            if (!fused.isSupported()) {
                _status.value = SdkStatus.Failed("fused location not supported")
                return
            }
            fused.initialize()
            fused.registerFusedLocationCallback(listener)
            started = true
            _status.value = SdkStatus.Connecting
        }.onFailure {
            Log.e(TAG, "location init failed", it)
            _status.value = SdkStatus.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    override fun close() {
        if (!started) return
        runCatching { fused.unregisterFusedLocationCallback(listener) }
        runCatching { fused.release() }
        started = false
    }

    companion object {
        // Pleos 위치 서비스가 dangerous로 정의한 권한이라 런타임 요청이 필요하다
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
        private const val TAG = "LocationClient"
    }
}
