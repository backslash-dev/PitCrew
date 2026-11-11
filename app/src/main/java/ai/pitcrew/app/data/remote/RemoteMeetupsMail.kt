package ai.pitcrew.app.data.remote

import ai.pitcrew.app.data.MailService
import ai.pitcrew.app.data.MeetupPlan
import ai.pitcrew.app.data.MeetupService
import ai.pitcrew.app.data.MeetupUnavailableException
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Mail
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.MeetupPlace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.LocalDateTime

class RemoteMeetupService(private val api: PitCrewApi) : MeetupService {
    override suspend fun plan(contact: Contact, latitude: Double?, longitude: Double?, preferredAt: LocalDateTime?): MeetupPlan = try {
        api.post("v1/meetups/plan", meetupRequest(contact.id, latitude, longitude, preferredAt)).toMeetupPlan()
    } catch (e: ApiException) {
        if (e.code in UNAVAILABLE_CODES) throw MeetupUnavailableException(e.message ?: "만날 수 있는 시간이 없어요") else throw e
    }

    private companion object {
        val UNAVAILABLE_CODES = setOf("PEER_UNAVAILABLE", "NO_PLACE")
    }
}

internal fun meetupRequest(contactId: String, latitude: Double?, longitude: Double?, preferredAt: LocalDateTime?): JSONObject =
    JSONObject().put("contactId", contactId).apply {
        if (latitude != null && longitude != null) put("latitude", latitude).put("longitude", longitude)
        preferredAt?.let { put("preferredAt", it.toInstantString()) }
    }

internal fun JSONObject.toMeetupPlan(): MeetupPlan {
    val place = optJSONObject("place")?.let { MeetupPlace(it.getString("id"), it.getString("name"), it.getDouble("latitude"), it.getDouble("longitude")) }
    return MeetupPlan(
        mode = MeetupMode.valueOf(getString("mode")),
        place = place,
        meetAt = optString("meetAt").takeIf { it.isNotEmpty() }?.toLocalDateTime(),
        userTravelMinutes = if (has("userTravelMinutes")) getInt("userTravelMinutes") else null,
        peerTravelMinutes = if (has("peerTravelMinutes")) getInt("peerTravelMinutes") else null,
    )
}

class RemoteMailService(private val api: PitCrewApi) : MailService {
    private val _mails = MutableStateFlow<List<Mail>>(emptyList())
    override val mails: StateFlow<List<Mail>> = _mails.asStateFlow()

    override suspend fun refreshNow() {
        _mails.value = api.get("v1/mails", mapOf("unreplied" to "true")).getJSONArray("mails").objects().map {
            val from = it.getJSONObject("from")
            Mail(
                id = it.getString("id"),
                senderName = from.getString("name"),
                senderEmail = from.getString("email"),
                subject = it.getString("subject"),
                summary = it.getString("summary"),
                receivedAt = it.getString("receivedAt").toLocalDateTime(),
            )
        }
    }

    override suspend fun draft(mail: Mail, instruction: String): String =
        api.post("v1/mails/${mail.id}/draft", JSONObject().put("instruction", instruction.take(MAX_INSTRUCTION))).getString("draft")

    private companion object {
        const val MAX_INSTRUCTION = 200
    }
}
