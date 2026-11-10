package ai.pitcrew.app.data.local

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.MockChargerCatalog
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.StepState
import ai.pitcrew.app.testing.FakeWaypoints
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDateTime
import java.time.LocalTime

class ErrandStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private var now = LocalDateTime.of(2026, 11, 10, 18, 0)
    private val late = ErrandDetail.LateArrival(
        "r1", "온담 한식당", now.plusMinutes(30), now.plusMinutes(45), 15, LocalTime.of(18, 50),
        listOf(Contact("c1", "김민수", "010-2481-3307")), followUp = true,
    )
    private val pickup = ErrandDetail.Pickup("아메리카노", MockStoreCatalog.DEFAULT_STORES.first(), LocalTime.of(18, 20))
    private val charger = MockChargerCatalog.DEFAULT_CHARGERS.first()
    private val charge = ErrandDetail.ChargeAndDine(charger, charger.restaurants.first(), 2, null, 30, LocalTime.of(18, 24))

    @Test
    fun `세 종류 작업을 그대로 저장하고 읽는다`() = runTest {
        val runner = ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now })
        runner.approve(runner.propose(late, ErrandOrigin.SUGGESTED).id, ApprovalChannel.VOICE)
        runner.propose(pickup)
        runner.propose(charge)
        val saved = runner.errands.value
        assertEquals(saved, ErrandJson.decode(ErrandJson.encode(saved)))
    }

    @Test
    fun `다시 시작하면 진행 중이던 작업은 결과 미확인 실패로, 되돌리던 작업은 되돌리기 실패로 바꾼다`() {
        val store = FileErrandStore(folder.newFile("errands.json"))
        val base = ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now }).propose(pickup)
        val inProgress = base.copy(
            state = ErrandState.IN_PROGRESS,
            reference = "order-1",
            steps = base.steps.mapIndexed { i, s -> if (i == 1) s.copy(state = StepState.CURRENT) else s },
        )
        val undoing = base.copy(id = "u", state = ErrandState.COMPLETED, finishedAt = now, undoing = true)
        store.save(listOf(inProgress, undoing))

        now = now.plusMinutes(1)
        val restored = ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now }, store = store).errands.value
        val failed = restored.single { it.id == inProgress.id }
        assertEquals(ErrandState.FAILED, failed.state)
        assertEquals(StepState.FAILED, failed.steps[1].state)
        assertTrue(failed.canUndo(now))
        val undo = restored.single { it.id == "u" }
        assertFalse(undo.undoing)
        assertEquals("앱이 다시 시작돼 되돌리기 결과를 확인하지 못했어요", undo.undoNote)
    }

    @Test
    fun `파일이 깨졌거나 2주 넘은 기록은 버린다`() {
        val file = folder.newFile("errands.json").apply { writeText("{broken") }
        assertTrue(FileErrandStore(file).load().isEmpty())

        val store = FileErrandStore(file)
        val old = ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now.minusDays(15) }).propose(pickup)
        store.save(listOf(old))
        assertTrue(ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now }, store = store).errands.value.isEmpty())
    }
}
