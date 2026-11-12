package ai.pitcrew.app.ui.voice

import kotlinx.coroutines.test.TestScope
import ai.pitcrew.app.domain.ApprovalChannel
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
    private val speaker = FakeSpeaker()
    private val confirmer = VoiceConfirmer(speaker, speech, listenMillis = 5_000, speakStartMillis = 100, speakMaxMillis = 100)
    private var driving = false

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
        errands = errands,
        confirmer = confirmer,
        driving = { driving },
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

    // 컨트롤러가 backgroundScope에서 돌아 advanceUntilIdle이 그 안의 대기 시간을 진행시키지 않는다
    private fun TestScope.settle() {
        advanceTimeBy(10_000)
        runCurrent()
    }

    private fun TestScope.say(c: VoiceCommandController, text: String) {
        c.start()
        runCurrent()
        speech.result.value = SpeechResult(text, true)
        runCurrent()
    }

    @Test
    fun `주행 중에 말로 시키면 그 자리에서 음성으로 묻고 응이라고 하면 처리한다`() = runTest {
        driving = true
        val c = controller(backgroundScope)
        say(c, "가는 길에 아메리카노 픽업해줘")
        confirmer.answer("응 그렇게 해줘")
        settle()
        val errand = errands.errands.value.single()
        assertEquals(ErrandState.COMPLETED, errand.state)
        assertEquals(ApprovalChannel.VOICE, errand.approvedVia)
        assertTrue(speaker.spoken.first().endsWith("받도록 주문할까요?"))
        assertTrue((c.state.value as VoiceCommandState.Done).message.contains("주문하고 경유지에 넣었어요"))
    }

    @Test
    fun `주행 중 아니라고 하면 거절하고, 답이 없으면 카드로 남긴다`() = runTest {
        driving = true
        val c = controller(backgroundScope)
        say(c, "가는 길에 아메리카노 픽업해줘")
        confirmer.answer("아니 됐어")
        settle()
        assertEquals(ErrandState.DECLINED, errands.errands.value.single().state)

        c.dismiss()
        say(c, "가는 길에 라테 픽업해줘")
        settle()
        assertEquals(ErrandState.AWAITING_APPROVAL, errands.errands.value.first().state)
        assertTrue((c.state.value as VoiceCommandState.Done).message.startsWith("답이 없어 카드로 남겨 뒀어요"))
    }

    @Test
    fun `주차 중에는 묻지 않고 화면 승인을 기다린다`() = runTest {
        val c = controller(backgroundScope)
        say(c, "가는 길에 아메리카노 픽업해줘")
        settle()
        assertEquals(ErrandState.AWAITING_APPROVAL, errands.errands.value.single().state)
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun `주행 중 방금 거 취소해라고 하면 무엇을 되돌릴지 묻고 응이면 되돌린다`() = runTest {
        driving = true
        val c = controller(backgroundScope)
        say(c, "가는 길에 아메리카노 픽업해줘")
        confirmer.answer("응")
        settle()
        c.dismiss()
        say(c, "방금 거 취소해")
        assertTrue((c.state.value as VoiceCommandState.Confirming).question.endsWith("되돌릴까요?"))
        confirmer.answer("응")
        settle()
        assertEquals(ErrandState.CANCELLED, errands.errands.value.single().state)
        assertEquals(ApprovalChannel.VOICE, errands.errands.value.single().undoneVia)
    }
}
