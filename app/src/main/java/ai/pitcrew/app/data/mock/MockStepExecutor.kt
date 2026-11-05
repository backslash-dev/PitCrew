package ai.pitcrew.app.data.mock

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.StepExecutor
import ai.pitcrew.app.data.StepResult
import ai.pitcrew.app.data.UndoResult
import ai.pitcrew.app.data.WaypointEditor
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail

/** 서버 없이 예약 변경·알림·주문·줄서기를 성공으로 흉내 낸다. 경유지 추가·삭제만 실제 내비를 부른다. */
class MockStepExecutor(private val waypoints: WaypointEditor) : StepExecutor {
    override suspend fun execute(errand: Errand, stepIndex: Int): StepResult {
        val d = errand.detail
        if (stepIndex != ErrandRunner.plannedSteps(d).size) return StepResult.Done
        val added = when (d) {
            is ErrandDetail.Pickup -> waypoints.add(d.store.displayName, d.store.latitude, d.store.longitude)
            is ErrandDetail.ChargeAndDine -> waypoints.add(d.charger.name, d.charger.latitude, d.charger.longitude)
            is ErrandDetail.LateArrival -> true
        }
        return if (added) StepResult.Done else StepResult.Failed(WAYPOINT_ADD_FAILED)
    }

    override suspend fun undo(errand: Errand): UndoResult {
        val removed = when (val d = errand.detail) {
            is ErrandDetail.Pickup -> waypoints.remove(d.store.displayName, d.store.latitude, d.store.longitude)
            is ErrandDetail.ChargeAndDine -> waypoints.remove(d.charger.name, d.charger.latitude, d.charger.longitude)
            is ErrandDetail.LateArrival -> true
        }
        return UndoResult.Undone(if (removed) null else WAYPOINT_REMOVE_FAILED)
    }

    companion object {
        const val WAYPOINT_ADD_FAILED = "내비게이션에 경유지를 넣지 못했어요"
        const val WAYPOINT_REMOVE_FAILED = "경유지는 내비게이션에서 직접 지워 주세요"
    }
}
