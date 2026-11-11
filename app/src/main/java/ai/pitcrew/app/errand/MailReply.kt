package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.MailService
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.Mail

sealed interface MailReplyOutcome {
    data object NoMail : MailReplyOutcome
    data class AwaitingReview(val errand: Errand) : MailReplyOutcome
}

class MailReplyUseCase(private val mails: MailService, private val errands: ErrandService) {
    suspend fun target(instruction: String? = null): Mail? {
        mails.refreshNow()
        val inbox = mails.mails.value
        val spoken = instruction?.replace(" ", "").orEmpty()
        return inbox.filter { mail -> mail.senderName.let { spoken.contains(it) || (it.length >= 3 && spoken.contains(it.drop(1))) } }
            .maxByOrNull { it.receivedAt }
            ?: inbox.maxByOrNull { it.receivedAt }
    }

    suspend fun draft(instruction: String, mail: Mail? = null, origin: ErrandOrigin = ErrandOrigin.REQUESTED): MailReplyOutcome {
        val target = mail ?: target(instruction) ?: return MailReplyOutcome.NoMail
        val text = instruction.trim()
        val draft = mails.draft(target, text)
        return MailReplyOutcome.AwaitingReview(errands.propose(ErrandDetail.MailReply(target, text, draft), origin))
    }
}
