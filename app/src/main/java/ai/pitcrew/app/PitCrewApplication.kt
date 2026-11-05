package ai.pitcrew.app

import ai.pitcrew.app.platform.PlatformServices
import android.app.Application

class PitCrewApplication : Application() {
    val platform: PlatformServices by lazy { PlatformServices(this).also { it.start() } }
}
