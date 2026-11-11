package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.data.mock.InMemoryContactRepository
import ai.pitcrew.app.data.mock.InMemoryReservationRepository
import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.testing.FakeWaypoints
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Store
import java.io.IOException
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.data.mock.MockChargerCatalog
import ai.pitcrew.app.data.mock.MockMailService
import ai.pitcrew.app.data.mock.MockMeetupService
import ai.pitcrew.app.errand.MailReplyUseCase
import ai.pitcrew.app.errand.MeetupUseCase
import ai.pitcrew.app.errand.ChargeAndDineUseCase
import ai.pitcrew.app.errand.LateArrivalUseCase
import ai.pitcrew.app.errand.UndoUseCase
import ai.pitcrew.app.errand.PickupUseCase
import ai.pitcrew.app.errand.RuleBasedInterpreter
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.platform.SpeechResult
import ai.pitcrew.app.platform.SpeechSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCommandControllerTest {
    private class FakeSpeech : SpeechSource {
        override val listening = MutableStateFlow(false)
        override val result = MutableStateFlow<SpeechResult?>(null)
        var stopped = 0
        override fun listen(): Boolean { listening.value = true; result.value = null; return true }
        override fun stop() { stopped++; listening.value = false }
    }

    private val now = LocalDateTime.of(2026, 11, 5, 18, 40)
    private val route = Destination("강남역", 45 * 60, 13_000)
    private val errands = ErrandRunner(MockStepExecutor(FakeWaypoints { true }), now = { now }, stepDelayMillis = 0)
    private val speech = FakeSpeech()

    private fun controller(scope: CoroutineScope, stores: StoreCatalog = MockStoreCatalog()) = VoiceCommandController(
        speech = speech,
        interpreter = RuleBasedInterpreter({ emptyList() }),
        lateArrival = LateArrivalUseCase(
            { route },
            errands,
            InMemoryReservationRepository(listOf(Reservation("r1", "온담 한식당", LocalDateTime.of(2026, 11, 5, 19, 0), 2, emptyList()))),
            InMemoryContactRepository(),
        ) { now },
        pickup = PickupUseCase({ route }, stores, errands) { now },
        charging = ChargeAndDineUseCase({ route }, MockChargerCatalog(), { 18 }, errands) { now },
        undo = UndoUseCase(errands) { now },
        meetup = MeetupUseCase(InMemoryContactRepository(), MockMeetupService({ emptyList() }) { now }, { null }, errands) { now },
        mail = MailReplyUseCase(MockMailService(emptyList()), errands),
        scope = scope,
        listenTimeoutMillis = 5_000,
    )

    @Test
    fun `말이 끝나면 해석해 승인 대기 작업을 만든다`() = runTest {
        val c = controller(backgroundScope)
        c.start()
        runCurrent()
        speech.result.value = SpeechResult("일곱 시", false)
        runCurrent()
        assertEquals(VoiceCommandState.Listening("일곱 시"), c.state.value)
        speech.result.value = SpeechResult("저녁 일곱 시 예약 늦을 것 같아", true)
        runCurrent()
        val done = c.state.value as VoiceCommandState.Done
        assertTrue(done.needsApproval)
        assertEquals(ErrandState.AWAITING_APPROVAL, errands.errands.value.single().state)
        assertEquals(1, speech.stopped)
    }

    @Test
    fun `아무 말이 없으면 시간이 지나 다시 말해 달라고 한다`() = runTest {
        val c = controller(backgroundScope)
        c.start()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals("잘 못 들었어요. 다시 말해 주세요.", (c.state.value as VoiceCommandState.Done).message)
        assertTrue(errands.errands.value.isEmpty())
    }

    @Test
    fun `취소하면 인식을 멈추고 처음 상태로 돌아간다`() = runTest {
        val c = controller(backgroundScope)
        c.start()
        runCurrent()
        c.cancel()
        assertEquals(VoiceCommandState.Idle, c.state.value)
        assertTrue(speech.stopped >= 1)
    }

    @Test
    fun `서버에 연결하지 못해도 앱이 죽지 않고 안내한다`() = runTest {
        val offline = object : StoreCatalog {
            override suspend fun findOnRoute(storeName: String?): Store? = throw IOException("connect failed")
            override suspend fun candidatesOnRoute(): List<Store> = throw IOException("connect failed")
        }
        val c = controller(backgroundScope, offline)
        c.start()
        runCurrent()
        speech.result.value = SpeechResult("아메리카노 픽업해줘", true)
        runCurrent()
        assertEquals("처리하지 못했어요. 서버에 연결하지 못했어요", (c.state.value as VoiceCommandState.Done).message)
    }
}
