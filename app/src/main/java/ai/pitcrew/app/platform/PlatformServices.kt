package ai.pitcrew.app.platform

import android.content.Context

/** Gleo 방송으로 깨어났을 때 응답 시간을 줄이려고 SDK는 처음 쓸 때 시작한다. */
class PlatformServices(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val started = mutableListOf<AutoCloseable>()

    val drivingState by component { DrivingStateMonitor(appContext).also { it.start() } }
    val battery by component { BatteryClient(appContext).also { it.start() } }
    val navigation by component { NavigationClient(appContext).also { it.start() } }
    val location by component { LocationClient(appContext).also { it.start() } }
    val voiceInput by component { VoiceInput(appContext).also { it.start() } }
    val voiceOutput by component { VoiceOutput(appContext).also { it.start() } }

    fun startAll() {
        drivingState
        battery
        navigation
        location
        voiceInput
        voiceOutput
    }

    override fun close() {
        synchronized(started) {
            started.forEach { runCatching { it.close() } }
            started.clear()
        }
    }

    private fun <T : AutoCloseable> component(create: () -> T) = lazy {
        create().also { synchronized(started) { started += it } }
    }
}
