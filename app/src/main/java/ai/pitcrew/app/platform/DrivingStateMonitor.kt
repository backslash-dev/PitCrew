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

    fun start() {
        runCatching {
            val connected = Car.createCar(context) ?: error("car service unavailable")
            car = connected
            startGear(connected)
            startUxRestrictions(connected)
            _status.value = SdkStatus.Ready
        }.onFailure {
            Log.e(TAG, "driving state unavailable", it)
            _status.value = SdkStatus.Failed(it.message ?: it.javaClass.simpleName)
        }
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
