package ai.pitcrew.app.data.mock

import ai.pitcrew.app.data.MailService
import ai.pitcrew.app.domain.Mail
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime

class MockMailService(seed: List<Mail>) : MailService {
    private val _mails = MutableStateFlow(seed)
    override val mails: StateFlow<List<Mail>> = _mails.asStateFlow()

    override suspend fun refreshNow() = Unit

    override suspend fun draft(mail: Mail, instruction: String): String = MailDrafts.draft(mail.senderName, instruction)

    fun markReplied(id: String) = _mails.update { list -> list.filterNot { it.id == id } }

    companion object {
        fun demo(now: LocalDateTime): MockMailService = MockMailService(
            listOf(
                Mail(
                    id = "mail-demo-1",
                    senderName = "정하윤",
                    senderEmail = "hayoon.jung@example.com",
                    subject = "내일 주간 회의 시간 변경",
                    summary = "내일 주간 회의를 10시에서 11시로 옮겨도 될지 묻는 메일이에요",
                    receivedAt = now.minusMinutes(2),
                ),
            ),
        )
    }
}

/** 서버 목업과 같은 문장을 쓴다. */
object MailDrafts {
    private const val SIGNATURE = "— 운전 중 PitCrew로 보냄"
    private val ACCEPT = Regex("괜찮|좋아|좋다|그렇게|오케이|가능|돼|된다")
    private val DECLINE = Regex("어렵|안\\s*돼|안\\s*된|힘들|불가")
    private val LATER = Regex("나중에|이따|도착하고|도착해서|운전\\s*중")
    private val LATE = Regex("늦")

    fun draft(senderName: String, instruction: String): String {
        val body = when {
            DECLINE.containsMatchIn(instruction) -> "${senderName}님, 말씀하신 일정은 어려울 것 같습니다. 다른 시간을 다시 맞춰 주시면 감사하겠습니다."
            LATE.containsMatchIn(instruction) -> "${senderName}님, 조금 늦을 것 같습니다. 도착하는 대로 연락드리겠습니다."
            LATER.containsMatchIn(instruction) -> "${senderName}님, 운전 중이라 도착하는 대로 자세히 답장드리겠습니다."
            ACCEPT.containsMatchIn(instruction) -> "${senderName}님, 말씀하신 대로 진행해 주세요. 감사합니다."
            else -> "${senderName}님, 메일 확인했습니다. 도착하는 대로 자세히 답장드리겠습니다."
        }
        return "$body\n$SIGNATURE"
    }
}
