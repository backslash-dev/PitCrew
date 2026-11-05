package ai.pitcrew.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class DrivingStateTest {
    @Test
    fun `규제 해제 상태에서만 터치를 허용한다`() {
        assertEquals(InteractionMode.TOUCH, DrivingState(Gear.PARK, requiresDistractionOptimization = false).interactionMode)
    }

    @Test
    fun `신호대기처럼 D단 규제 상태면 음성만 허용한다`() {
        assertEquals(InteractionMode.VOICE_ONLY, DrivingState(Gear.DRIVE, requiresDistractionOptimization = true).interactionMode)
    }

    @Test
    fun `규제 상태를 모르면 P단이어도 음성만 허용한다`() {
        assertEquals(InteractionMode.VOICE_ONLY, DrivingState(Gear.PARK, requiresDistractionOptimization = null).interactionMode)
    }

    @Test
    fun `수동 단수 기어는 주행으로 본다`() {
        // AAOS VehicleGear: PARK=4, DRIVE=8, REVERSE=2, NEUTRAL=1, UNKNOWN=0, 3단=64
        assertEquals(Gear.DRIVE, gearOf(64))
        assertEquals(Gear.DRIVE, gearOf(8))
        assertEquals(Gear.PARK, gearOf(4))
        assertEquals(Gear.REVERSE, gearOf(2))
        assertEquals(Gear.UNKNOWN, gearOf(0))
    }
}
