package ai.pitcrew.app.platform

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class InteractionMode { TOUCH, VOICE_ONLY }

data class DrivingState(
    val gear: Gear = Gear.UNKNOWN,
    val requiresDistractionOptimization: Boolean? = null,
) {
    // 규제 상태를 모르면 주행 중으로 간주한다. 신호대기(D, 0km/h)도 규제 대상이라 기어만으로 판단하지 않는다.
    val interactionMode: InteractionMode
        get() = if (requiresDistractionOptimization == false) InteractionMode.TOUCH else InteractionMode.VOICE_ONLY
}
