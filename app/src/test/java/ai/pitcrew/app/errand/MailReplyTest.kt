package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.MailDrafts
import ai.pitcrew.app.data.mock.MockMailService
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.Mail
import ai.pitcrew.app.testing.FakeWaypoints
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MailReplyTest {
    private var now = LocalDateTime.of(2026, 11, 11, 17, 0)
    private fun mail(id: String, sender: String, minutesAgo: Long) =
        Mail(id, sender, "$id@example.com", "제목 $id", "요약 $id", now.minusMinutes(minutesAgo))
    private val mailbox = MockMailService(listOf(mail("m1", "정하윤", 30), mail("m2", "오세훈", 2)))
    private val errands = ErrandRunner(MockStepExecutor(FakeWaypoints(), mails = mailbox), now = { now })
    private val useCase = MailReplyUseCase(mailbox, errands)

    @Test
    fun `말한 의도에 맞는 답장 틀을 고르고 서명을 붙인다`() {
        assertTrue(MailDrafts.draft("정하윤", "괜찮다고 답장해").startsWith("정하윤님, 말씀하신 대로 진행해 주세요."))
        assertTrue(MailDrafts.draft("정하윤", "그건 안 된다고 해").contains("어려울 것 같습니다"))
        assertTrue(MailDrafts.draft("정하윤", "늦는다고 답장해").contains("조금 늦을 것 같습니다"))
        assertTrue(MailDrafts.draft("정하윤", "도착하고 답한다고 해").contains("도착하는 대로 자세히"))
        assertTrue(MailDrafts.draft("정하윤", "응").endsWith("— 운전 중 PitCrew로 보냄"))
    }

    @Test
    fun `보낸 사람 이름을 말하면 그 메일, 아니면 가장 최근 메일에 답한다`() = runTest {
        val named = useCase.draft("하윤 씨 메일에 괜찮다고 답장해") as MailReplyOutcome.AwaitingReview
        assertEquals("m1", (named.errand.detail as ErrandDetail.MailReply).mail.id)
        val latest = useCase.draft("방금 메일 괜찮다고 답장해") as MailReplyOutcome.AwaitingReview
        assertEquals("m2", (latest.errand.detail as ErrandDetail.MailReply).mail.id)
    }

    @Test
    fun `음성으로는 보내지 않고 화면에서 승인해야 보낸다`() = runTest {
        val errand = (useCase.draft("괜찮다고 답장해") as MailReplyOutcome.AwaitingReview).errand
        assertEquals(ErrandState.AWAITING_APPROVAL, errands.approve(errand.id, ApprovalChannel.VOICE).state)
        val sent = errands.approve(errand.id, ApprovalChannel.TOUCH)
        assertEquals(ErrandState.COMPLETED, sent.state)
        assertFalse(sent.canUndo(now))
        assertEquals(listOf("m1"), mailbox.mails.value.map { it.id })
    }

    @Test
    fun `정차를 기다리는 초안은 다른 제안을 막지 않고 한 시간이 지나도 남아 있다`() = runTest {
        val errand = (useCase.draft("괜찮다고 답장해") as MailReplyOutcome.AwaitingReview).errand
        assertFalse(errand.blocksSuggestions)
        now = now.plusHours(1)
        errands.expireStale()
        assertEquals(ErrandState.AWAITING_APPROVAL, errands.errands.value.single().state)
    }

    @Test
    fun `답장할 메일이 없으면 초안을 만들지 않는다`() = runTest {
        val empty = MailReplyUseCase(MockMailService(emptyList()), errands)
        assertEquals(MailReplyOutcome.NoMail, empty.draft("괜찮다고 답장해"))
    }
}
