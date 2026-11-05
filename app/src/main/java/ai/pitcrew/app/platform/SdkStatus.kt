package ai.pitcrew.app.platform

sealed interface SdkStatus {
    data object Connecting : SdkStatus
    data object Ready : SdkStatus
    data class Failed(val reason: String) : SdkStatus
}
