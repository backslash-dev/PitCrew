package ai.pitcrew.app.ui.voice

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.InMemoryContactRepository
import ai.pitcrew.app.data.mock.InMemoryReservationRepository
import ai.pitcrew.app.data.mock.MockChargerCatalog
import ai.pitcrew.app.data.mock.MockOrderHistory
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.errand.ChargeAndDineUseCase
import ai.pitcrew.app.errand.LateArrivalUseCase
import ai.pitcrew.app.errand.PickupUseCase
import ai.pitcrew.app.platform.Destination
import ai.pitcrew.app.platform.Speaker
import ai.pitcrew.app.platform.SpeechResult
import ai.pitcrew.app.platform.SpeechSource
import ai.pitcrew.app.testing.FakeWaypoints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FakeSpeaker : Speaker {
    override val speaking = MutableStateFlow(false)
    val spoken = mutableListOf<String>()
    override fun speak(text: String): Boolean = spoken.add(text)
}

class FakeSpeech : SpeechSource {
    override val listening = MutableStateFlow(false)
    override val result = MutableStateFlow<SpeechResult?>(null)
    override fun listen(): Boolean { listening.value = true; result.value = null; return true }
    override fun stop() { listening.value = false }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ProactiveAssistantTest {
    private val now = LocalDateTime.of(2026, 11, 5, 18, 40)
    private var route: Destination? = Destination("강남역", 45 * 60, 13_000)
    private var battery: Int? = null
    private val enabled = MutableStateFlow(true)
    private val errands = ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now })
    private val dinner = Reservation("r1", "온담 한식당", LocalDateTime.of(2026, 11, 5, 19, 0), 3, listOf("c-minsu", "c-seoyeon"))
    private val reservations = InMemoryReservationRepository(listOf(dinner))
    private val speaker = FakeSpeaker()
    private val speech = FakeSpeech()
    private val confirmer = VoiceConfirmer(speaker, speech, listenMillis = 5_000, speakStartMillis = 100, speakMaxMillis = 100)

    private fun assistant(scope: CoroutineScope, orders: MockOrderHistory = MockOrderHistory(emptyList())) = ProactiveAssistant(
        scope = scope,
        enabled = enabled,
        fetchDestination = { route },
        batteryPercent = { battery },
        reservations = reservations,
        orders = orders,
        stores = MockStoreCatalog(),
        errands = errands,
        lateArrival = LateArrivalUseCase({ route }, errands, reservations, InMemoryContactRepository()) { now },
        pickup = PickupUseCase({ route }, MockStoreCatalog(), errands) { now },
        charging = ChargeAndDineUseCase({ route }, MockChargerCatalog(), { battery }, errands) { now },
        confirmer = confirmer,
        now = { now },
    )

    /** 질문을 말하는 중에 [answer]로 답한다. null이면 답하지 않는다. */
    private suspend fun TestScope.tickAnswering(assistant: ProactiveAssistant, answer: String?) = async { assistant.tick() }.let { result ->
        runCurrent()
        answer?.let(confirmer::answer)
        advanceUntilIdle()
        result.await()
    }

    @Test
    fun `늦는 예약을 먼저 묻고 응이라고 하면 바로 처리한다`() = runTest {
        val a = assistant(backgroundScope)
        val asked = tickAnswering(a, "응 그렇게 해줘")!!
        assertEquals(ErrandOrigin.SUGGESTED, asked.origin)
        assertEquals("지금 가면 온담 한식당 예약보다 25분 늦어요. 예약을 19:30에 다시 잡고 일행 2명에게 알릴까요?", speaker.spoken.single())
        assertEquals(ErrandState.COMPLETED, errands.errands.value.single().state)
    }

    @Test
    fun `같은 예약은 다시 묻지 않고, 미룬 시각에도 또 늦으면 다시 묻는다`() = runTest {
        val a = assistant(backgroundScope)
        tickAnswering(a, "응")
        assertNull(tickAnswering(a, null))
        reservations.upsert(dinner.copy(time = LocalDateTime.of(2026, 11, 5, 19, 30)))
        route = Destination("강남역", 60 * 60, 13_000)
        val again = tickAnswering(a, "아니")!!
        assertTrue((again.detail as ErrandDetail.LateArrival).followUp)
        assertTrue(speaker.spoken.last().startsWith("길이 더 막혀 온담 한식당 예약에 또 10분 늦어요."))
        assertEquals(ErrandState.DECLINED, errands.errands.value.first().state)
    }

    @Test
    fun `답이 없으면 제안을 카드로 남기고 그동안은 다른 것을 묻지 않는다`() = runTest {
        reservations.delete("r1")
        battery = 18
        val a = assistant(backgroundScope)
        val asked = tickAnswering(a, null)!!
        assertTrue(asked.detail is ErrandDetail.ChargeAndDine)
        assertEquals(ErrandState.AWAITING_APPROVAL, errands.errands.value.single().state)
        assertEquals("배터리가 18% 남았어요. 이음차지 양재에서 18분쯤 충전하는 동안 소복 국밥에 줄을 서 둘까요?", speaker.spoken.single())
        assertNull(tickAnswering(a, null))
    }

    @Test
    fun `배터리 제안은 충전해서 다시 오르기 전까지 한 번만 한다`() = runTest {
        reservations.delete("r1")
        battery = 18
        val a = assistant(backgroundScope)
        tickAnswering(a, "아니")
        assertNull(tickAnswering(a, null))
        battery = 35
        tickAnswering(a, null)
        battery = 15
        assertTrue(tickAnswering(a, "아니")!!.detail is ErrandDetail.ChargeAndDine)
    }

    @Test
    fun `자주 시키던 시간이면 단골 메뉴를 권한다`() = runTest {
        reservations.delete("r1")
        val a = assistant(backgroundScope, MockOrderHistory.routine(now))
        val asked = tickAnswering(a, "응")!!
        assertEquals("오늘도 세모 커피 역삼점에서 아메리카노 픽업할까요? 들르면 19:05에 받을 수 있어요.", speaker.spoken.single())
        assertEquals(ErrandState.COMPLETED, errands.errands.value.single { it.id == asked.id }.state)
        assertNull(tickAnswering(a, null))
    }

    @Test
    fun `꺼져 있거나 경로가 없으면 묻지 않는다`() = runTest {
        val a = assistant(backgroundScope)
        enabled.value = false
        assertNull(tickAnswering(a, null))
        enabled.value = true
        route = null
        assertNull(tickAnswering(a, null))
        assertTrue(speaker.spoken.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceConfirmerTest {
    @Test
    fun `승낙과 거절 말을 가린다`() {
        listOf("응", "네 해줘", "그래", "좋아요", "부탁해", "응응").forEach { assertEquals(it, Answer.YES, AnswerClassifier.classify(it)) }
        listOf("아니", "안 해줘", "괜찮아", "하지 마", "나중에").forEach { assertEquals(it, Answer.NO, AnswerClassifier.classify(it)) }
        listOf("", "어떡하지", "음…").forEach { assertEquals(it, Answer.NONE, AnswerClassifier.classify(it)) }
    }

    @Test
    fun `마이크로 들은 말을 답으로 쓰고 끝나면 듣기를 멈춘다`() = runTest {
        val speech = FakeSpeech()
        val confirmer = VoiceConfirmer(FakeSpeaker(), speech, listenMillis = 5_000, speakStartMillis = 100, speakMaxMillis = 100)
        val answer = async { confirmer.ask("바꿀까요?") }
        // 질문을 말한 뒤(최대 0.2초) 듣는 중에 답한다. 듣기 제한(5초)을 넘기지 않게 시간을 조금만 보낸다
        advanceTimeBy(500)
        assertTrue(confirmer.listening.value)
        speech.result.value = SpeechResult("네 바꿔줘", isFinal = true)
        runCurrent()
        assertEquals(Answer.YES, answer.await())
        assertEquals(false, speech.listening.value)
        assertEquals(false, confirmer.listening.value)
    }

    @Test
    fun `다른 음성 입력이 듣는 중이면 묻지 않는다`() = runTest {
        val speech = FakeSpeech().apply { listening.value = true }
        val speaker = FakeSpeaker()
        assertNull(VoiceConfirmer(speaker, speech).ask("바꿀까요?"))
        assertTrue(speaker.spoken.isEmpty())
    }
}
