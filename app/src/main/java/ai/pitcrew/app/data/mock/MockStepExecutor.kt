package ai.pitcrew.app.data.mock

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.StepExecutor
import ai.pitcrew.app.data.StepResult
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.Store

/** 서버 없이 예약 변경·알림·주문을 성공으로 흉내 낸다. 경유지 추가만 실제 내비를 부른다. */
class MockStepExecutor(private val addWaypoint: suspend (Store) -> Boolean) : StepExecutor {
    override suspend fun execute(errand: Errand, stepIndex: Int): StepResult = when (val d = errand.detail) {
        is ErrandDetail.Pickup ->
            if (stepIndex == ErrandRunner.plannedSteps(d).size && !addWaypoint(d.store)) StepResult.Failed("내비게이션에 경유지를 넣지 못했어요") else StepResult.Done
        is ErrandDetail.LateArrival -> StepResult.Done
    }
}
