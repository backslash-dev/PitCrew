package ai.pitcrew.app.platform

sealed interface SdkStatus {
    data object Connecting : SdkStatus
    data object Ready : SdkStatus
    data class Failed(val reason: String) : SdkStatus
}

class SdkUnavailableException(val sdk: String, reason: String) : IllegalStateException("$sdk unavailable: $reason")
