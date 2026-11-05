package ai.pitcrew.app.platform

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BatteryClient(private val context: Context) : AutoCloseable {
    private val _status = MutableStateFlow<SdkStatus>(SdkStatus.Connecting)
    val status: StateFlow<SdkStatus> = _status.asStateFlow()

    private val _stateOfChargePercent = MutableStateFlow<Int?>(null)
    val stateOfChargePercent: StateFlow<Int?> = _stateOfChargePercent.asStateFlow()

    private var vehicle: Vehicle? = null

    private val listener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(percent: Int?) = onStateOfCharge(percent)
        override fun onFailed(e: Exception) = fail(e)
    }

    fun start() {
        runCatching {
            val v = Vehicle(context).also { vehicle = it }
            v.initialize()
            v.getEvBattery().getEvBatteryStateOfCharge(::onStateOfCharge, ::fail)
            v.getEvBattery().registerEvBatteryStateOfCharge(listener)
        }.onFailure(::fail)
    }

    private fun onStateOfCharge(percent: Int?) {
        _status.value = SdkStatus.Ready
        _stateOfChargePercent.value = percent
    }

    private fun fail(e: Throwable) {
        Log.w(TAG, "battery unavailable", e)
        _status.value = SdkStatus.Failed(e.message ?: e.javaClass.simpleName)
    }

    override fun close() {
        vehicle?.let { v ->
            runCatching { v.getEvBattery().unregisterEvBatteryStateOfCharge(listener) }
            runCatching { v.release() }
        }
        vehicle = null
    }

    private companion object {
        const val TAG = "BatteryClient"
    }
}
