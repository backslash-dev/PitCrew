package ai.pitcrew.app.platform

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Vehicle SDK의 기어 API는 2.3.2에서 deprecated라 AAOS 표준 GEAR_SELECTION을 쓴다. */
class DrivingStateMonitor(private val context: Context) : AutoCloseable {
    private val _state = MutableStateFlow(DrivingState())
    val state: StateFlow<DrivingState> = _state.asStateFlow()

    private val _status = MutableStateFlow<SdkStatus>(SdkStatus.Connecting)
    val status: StateFlow<SdkStatus> = _status.asStateFlow()

    private var car: Car? = null
    private var propertyManager: CarPropertyManager? = null
    private var uxManager: CarUxRestrictionsManager? = null

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status == CarPropertyValue.STATUS_AVAILABLE) onGear(value.value as? Int)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(TAG, "gear property error area=$areaId")
        }
    }

    private val uxListener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener(::onUxRestrictions)

    // 리스너 없는 Car.createCar는 차량 서비스 재시작 시 앱을 죽인다. 연결은 기다리지 않는다
    fun start() {
        runCatching {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (ready) onCarReady(connected) else onCarLost()
            }
        }.onFailure {
            Log.e(TAG, "car service unavailable", it)
            _status.value = SdkStatus.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    private fun onCarReady(car: Car) {
        runCatching {
            startGear(car)
            startUxRestrictions(car)
            _status.value = SdkStatus.Ready
        }.onFailure {
            Log.e(TAG, "driving state unavailable", it)
            _status.value = SdkStatus.Failed(it.message ?: it.javaClass.simpleName)
        }
    }

    // 재연결 전까지는 규제 상태를 모르므로 음성 전용으로 떨어진다
    private fun onCarLost() {
        Log.w(TAG, "car service disconnected, waiting for reconnect")
        propertyManager = null
        uxManager = null
        _state.value = DrivingState()
        _status.value = SdkStatus.Connecting
    }

    @Suppress("DEPRECATION")
    private fun startGear(car: Car) {
        val manager = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            ?: error("property service unavailable")
        propertyManager = manager
        onGear(manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, GLOBAL_AREA))
        // subscribePropertyEvents는 API 35부터라 API 34 기기에서는 registerCallback을 써야 한다
        if (!manager.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
            error("gear subscription rejected")
        }
    }

    private fun startUxRestrictions(car: Car) {
        val manager = car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            ?: error("ux restriction service unavailable")
        uxManager = manager
        manager.registerListener(uxListener)
        manager.currentCarUxRestrictions?.let(::onUxRestrictions)
    }

    private fun onGear(value: Int?) {
        _state.update { it.copy(gear = value?.let(::gearOf) ?: Gear.UNKNOWN) }
    }

    private fun onUxRestrictions(restrictions: CarUxRestrictions) {
        _state.update { it.copy(requiresDistractionOptimization = restrictions.isRequiresDistractionOptimization) }
    }

    override fun close() {
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        runCatching { uxManager?.unregisterListener() }
        runCatching { car?.disconnect() }
        propertyManager = null
        uxManager = null
        car = null
    }

    private companion object {
        const val TAG = "DrivingStateMonitor"
        const val GLOBAL_AREA = 0
    }
}

internal fun gearOf(value: Int): Gear = when (value) {
    VehicleGear.GEAR_PARK -> Gear.PARK
    VehicleGear.GEAR_REVERSE -> Gear.REVERSE
    VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
    VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.DRIVE
}
