package ai.pitcrew.app.platform

import android.content.Context

class PlatformServices(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext

    val drivingState = DrivingStateMonitor(appContext)
    val battery = BatteryClient(appContext)
    val navigation = NavigationClient(appContext)
    val location = LocationClient(appContext)
    val voiceInput = VoiceInput(appContext)
    val voiceOutput = VoiceOutput(appContext)

    private val components: List<AutoCloseable> = listOf(drivingState, battery, navigation, location, voiceInput, voiceOutput)

    fun start() {
        drivingState.start()
        battery.start()
        navigation.start()
        location.start()
        voiceInput.start()
        voiceOutput.start()
    }

    override fun close() {
        components.forEach { runCatching { it.close() } }
    }
}
