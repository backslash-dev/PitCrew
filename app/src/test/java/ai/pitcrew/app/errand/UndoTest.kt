package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.StepExecutor
import ai.pitcrew.app.data.StepResult
import ai.pitcrew.app.data.UndoResult
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoStatus
import ai.pitcrew.app.testing.FakeWaypoints
import ai.pitcrew.app.ui.errand.outcomeLine
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class ErrandUndoTest {
    private var now = LocalDateTime.of(2026, 11, 5, 18, 0)
    private val store = MockStoreCatalog.DEFAULT_STORES.first()
    private val pickup = ErrandDetail.Pickup("아메리카노", store, LocalTime.of(18, 20))
    private val waypoints = FakeWaypoints()

    @Test
    fun `첫 단계가 남긴 식별자를 기록하고 되돌릴 때 넘긴다`() = runTest {
        var undoneWith: String? = null
        val executor = object : StepExecutor {
            override suspend fun execute(errand: Errand, stepIndex: Int) = if (stepIndex == 1) StepResult.Recorded("order-1") else StepResult.Done
            override suspend fun undo(errand: Errand): UndoResult {
                undoneWith = errand.reference
                return UndoResult.Undone()
            }
        }
        val runner = ErrandRunner(executor, now = { now })
        val done = runner.approve(runner.propose(pickup).id, ApprovalChannel.TOUCH)
        assertEquals("order-1", done.reference)
        val undone = runner.undo(done.id, ApprovalChannel.TOUCH)
        assertEquals("order-1", undoneWith)
        assertEquals(ErrandState.CANCELLED, undone.state)
        assertEquals(ApprovalChannel.TOUCH, undone.undoneVia)
        assertEquals(ErrandRunner.UNDO_STEP, undone.steps.last().title)
    }

    @Test
    fun `되돌리면 넣었던 경유지를 빼고, 못 빼면 안내를 남긴다`() = runTest {
        val runner = ErrandRunner(MockStepExecutor(waypoints), now = { now })
        val done = runner.approve(runner.propose(pickup).id, ApprovalChannel.TOUCH)
        assertEquals(listOf(store.displayName), waypoints.added)
        assertEquals(ErrandState.CANCELLED, runner.undo(done.id, ApprovalChannel.VOICE).state)
        assertEquals(listOf(store.displayName), waypoints.removed)

        var removable = true
        val stuck = ErrandRunner(MockStepExecutor(FakeWaypoints { removable }), now = { now })
        val second = stuck.approve(stuck.propose(pickup).id, ApprovalChannel.TOUCH)
        removable = false
        val partial = stuck.undo(second.id, ApprovalChannel.TOUCH)
        assertEquals(ErrandState.CANCELLED, partial.state)
        assertEquals(MockStepExecutor.WAYPOINT_REMOVE_FAILED, partial.undoNote)
        assertEquals("주문을 취소했어요. 경유지는 내비게이션에서 직접 지워 주세요", partial.outcomeLine())
        assertEquals("주문을 취소하고 경유지를 뺐어요", runner.errands.value.single().outcomeLine())
    }

    @Test
    fun `3분이 지나면 되돌리지 않고 이유를 남긴다`() = runTest {
        val runner = ErrandRunner(MockStepExecutor(waypoints), now = { now })
        val done = runner.approve(runner.propose(pickup).id, ApprovalChannel.TOUCH)
        now = now.plusMinutes(4)
        val after = runner.undo(done.id, ApprovalChannel.TOUCH)
        assertEquals(ErrandState.COMPLETED, after.state)
        assertEquals("끝낸 지 3분이 지나 되돌릴 수 없어요", after.undoNote)
        assertTrue(waypoints.removed.isEmpty())
    }

    @Test
    fun `되돌리기에 실패하면 완료 상태로 남고 사유를 보인다`() = runTest {
        val executor = object : StepExecutor {
            override suspend fun execute(errand: Errand, stepIndex: Int) = StepResult.Done
            override suspend fun undo(errand: Errand) = UndoResult.Failed("매장에서 이미 조리를 시작해 취소할 수 없습니다")
        }
        val runner = ErrandRunner(executor, now = { now })
        val done = runner.approve(runner.propose(pickup).id, ApprovalChannel.TOUCH)
        val after = runner.undo(done.id, ApprovalChannel.TOUCH)
        assertEquals(ErrandState.COMPLETED, after.state)
        assertFalse(after.undoing)
        assertEquals("매장에서 이미 조리를 시작해 취소할 수 없습니다", after.undoNote)
    }

    @Test
    fun `화면과 음성이 동시에 되돌려도 한 번만 실행한다`() = runTest {
        var calls = 0
        val executor = object : StepExecutor {
            override suspend fun execute(errand: Errand, stepIndex: Int) = StepResult.Done
            override suspend fun undo(errand: Errand): UndoResult {
                calls++
                return UndoResult.Undone()
            }
        }
        val runner = ErrandRunner(executor, now = { now })
        val id = runner.approve(runner.propose(pickup).id, ApprovalChannel.TOUCH).id
        val a = async { runner.undo(id, ApprovalChannel.TOUCH) }
        val b = async { runner.undo(id, ApprovalChannel.VOICE) }
        a.await(); b.await()
        assertEquals(1, calls)
    }
}

class ErrandLifecycleTest {
    private var now = LocalDateTime.of(2026, 11, 5, 18, 0)
    private val pickup = ErrandDetail.Pickup("아메리카노", MockStoreCatalog.DEFAULT_STORES.first(), LocalTime.of(18, 20))
    private val late = ErrandDetail.LateArrival("r1", "온담 한식당", now.plusMinutes(30), now.plusMinutes(45), 15, LocalTime.of(18, 50), emptyList())

    @Test
    fun `답 없는 제안은 10분이 지나거나 픽업 시각이 지나면 만료되고 승인되지 않는다`() = runTest {
        val runner = ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now })
        val lateId = runner.propose(late).id
        val pickupId = runner.propose(pickup).id
        now = now.plusMinutes(9)
        runner.expireStale()
        assertEquals(ErrandState.AWAITING_APPROVAL, runner.errands.value.single { it.id == lateId }.state)
        now = now.plusMinutes(1)
        assertEquals(ErrandState.EXPIRED, runner.approve(lateId, ApprovalChannel.TOUCH).state)
        runner.expireStale()
        assertEquals(ErrandState.EXPIRED, runner.errands.value.single { it.id == pickupId }.state)
    }

    @Test
    fun `기다리던 쪽이 취소돼도 승인한 작업은 끝까지 실행한다`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val executor = object : StepExecutor {
            override suspend fun execute(errand: Errand, stepIndex: Int): StepResult {
                gate.await()
                return StepResult.Done
            }
        }
        val runner = ErrandRunner(executor, now = { now }, workScope = backgroundScope)
        val id = runner.propose(pickup).id
        val waiting = launch { runner.approve(id, ApprovalChannel.VOICE) }
        testScheduler.runCurrent()
        waiting.cancel()
        gate.complete(Unit)
        // backgroundScope 작업은 advanceUntilIdle이 기다리지 않으므로 예약된 작업을 직접 돌린다
        testScheduler.runCurrent()
        assertEquals(ErrandState.COMPLETED, runner.errands.value.single().state)
    }

    @Test
    fun `서버 처리 뒤 실패한 작업은 되돌릴 수 있다`() = runTest {
        val executor = object : StepExecutor {
            override suspend fun execute(errand: Errand, stepIndex: Int) =
                if (stepIndex == 1) StepResult.Recorded("order-1") else StepResult.Failed("내비게이션에 경유지를 넣지 못했어요")
            override suspend fun undo(errand: Errand) = UndoResult.Undone()
        }
        val runner = ErrandRunner(executor, now = { now })
        val failed = runner.approve(runner.propose(pickup).id, ApprovalChannel.TOUCH)
        assertEquals(ErrandState.FAILED, failed.state)
        assertTrue(failed.canUndo(now))
        assertEquals(ErrandState.CANCELLED, runner.undo(failed.id, ApprovalChannel.TOUCH).state)
    }
}

class CancelLastErrandHandlerTest {
    private val now = LocalDateTime.of(2026, 11, 5, 18, 0)
    private val errands = ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now })
    private val handler = CancelLastErrandHandler(UndoUseCase(errands) { now })
    private val pickup = ErrandDetail.Pickup("아메리카노", MockStoreCatalog.DEFAULT_STORES.first(), LocalTime.of(18, 20))

    private fun request(confirmed: Boolean?) = GleoRequest(
        GleoFunction.HANDLE_PITCREW_REQUEST,
        JSONObject().apply { if (confirmed != null) put("confirmed", confirmed) },
    )

    @Test
    fun `되돌릴 작업이 없으면 실패로 응답한다`() = runTest {
        assertEquals(GleoStatus.FAIL, handler.handle(request(null)).status)
    }

    @Test
    fun `무엇을 되돌릴지 먼저 묻고 확인하면 되돌린다`() = runTest {
        val done = errands.approve(errands.propose(pickup).id, ApprovalChannel.VOICE)
        val asking = handler.handle(request(null))
        assertEquals(GleoStatus.ASKING, asking.status)
        assertTrue(asking.message.contains("cancel the 아메리카노 pickup order at 세모 커피 역삼점"))
        assertEquals(ErrandState.COMPLETED, errands.errands.value.single { it.id == done.id }.state)

        val success = handler.handle(request(true))
        assertEquals(GleoStatus.SUCCESS, success.status)
        assertEquals(ErrandState.CANCELLED, errands.errands.value.single().state)
        assertEquals(GleoStatus.FAIL, handler.handle(request(true)).status)
    }

    @Test
    fun `알리지 않고 바로 확인으로 온 요청은 먼저 무엇을 되돌릴지 묻는다`() = runTest {
        errands.approve(errands.propose(pickup).id, ApprovalChannel.VOICE)
        assertEquals(GleoStatus.ASKING, handler.handle(request(true)).status)
        assertEquals(ErrandState.COMPLETED, errands.errands.value.single().state)
        assertEquals(GleoStatus.SUCCESS, handler.handle(request(true)).status)
    }
}
