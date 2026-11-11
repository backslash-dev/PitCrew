package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ContactRepository
import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.data.MeetupService
import ai.pitcrew.app.data.MeetupUnavailableException
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.MeetupPlace
import ai.pitcrew.app.platform.GeoPoint
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 서버와 같은 규칙이다. 서버 없이 시연할 때만 앱이 직접 쓴다. */
object MeetupPlanner {
    private const val CITY_KMH = 30.0
    private const val BUFFER_MINUTES = 5
    private const val STEP_MINUTES = 10L
    private val CONFLICT: Duration = Duration.ofMinutes(90)
    private val PUSH: Duration = Duration.ofMinutes(30)

    fun travelMinutes(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Int =
        ceil(haversineKm(fromLat, fromLon, toLat, toLon) / CITY_KMH * 60).toInt() + BUFFER_MINUTES

    fun fairestPlace(places: List<MeetupPlace>, userLat: Double, userLon: Double, peerLat: Double, peerLon: Double): MeetupPlace? =
        places.minWithOrNull(
            compareBy<MeetupPlace>(
                { maxOf(travelMinutes(userLat, userLon, it.latitude, it.longitude), travelMinutes(peerLat, peerLon, it.latitude, it.longitude)) },
                { travelMinutes(userLat, userLon, it.latitude, it.longitude) + travelMinutes(peerLat, peerLon, it.latitude, it.longitude) },
            ),
        )

    fun meetAt(now: LocalDateTime, preferredAt: LocalDateTime?, travelMinutes: Int, peerFreeFrom: LocalDateTime, busy: List<LocalDateTime>): LocalDateTime {
        var at = listOfNotNull(preferredAt, now.plusMinutes(travelMinutes.toLong()), peerFreeFrom).max().let(::roundUp)
        while (busy.any { Duration.between(it, at).abs() < CONFLICT }) at = at.plus(PUSH)
        return at
    }

    private fun roundUp(time: LocalDateTime): LocalDateTime {
        val base = time.truncatedTo(ChronoUnit.MINUTES)
        val remainder = base.minute % STEP_MINUTES
        val rounded = if (remainder == 0L) base else base.plusMinutes(STEP_MINUTES - remainder)
        return if (rounded.isBefore(time)) rounded.plusMinutes(STEP_MINUTES) else rounded
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * 6371.0 * asin(sqrt(a))
    }
}

internal fun nextOccurrence(time: LocalTime, now: LocalDateTime): LocalDateTime {
    val times = if (time.hour < 12) listOf(time, time.plusHours(12)) else listOf(time)
    return times.flatMap { t -> (0L..1L).map { now.toLocalDate().plusDays(it).atTime(t) } }
        .filter { it.isAfter(now) }
        .min()
}

/** "민수"처럼 성을 빼고 불러도 찾는다. 이름이 겹치면 찾지 못한 것으로 본다. */
internal fun findContact(contacts: List<Contact>, spoken: String?): Contact? {
    val name = spoken?.replace(" ", "")?.takeIf { it.isNotEmpty() } ?: return null
    contacts.firstOrNull { it.name.replace(" ", "") == name }?.let { return it }
    return contacts.filter { c -> c.name.length >= 3 && name == c.name.drop(1) }.singleOrNull()
}

sealed interface MeetupOutcome {
    data class UnknownContact(val spoken: String?) : MeetupOutcome
    data class Unavailable(val reason: String) : MeetupOutcome
    data class AwaitingApproval(val errand: Errand) : MeetupOutcome
    data class Finished(val errand: Errand) : MeetupOutcome
}

class MeetupUseCase(
    private val contacts: ContactRepository,
    private val meetups: MeetupService,
    private val location: () -> GeoPoint?,
    private val errands: ErrandService,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    suspend fun propose(contactName: String?, preferredTime: LocalTime?, origin: ErrandOrigin = ErrandOrigin.REQUESTED): MeetupOutcome {
        contacts.ensureLoaded()
        val contact = findContact(contacts.contacts.value, contactName) ?: return MeetupOutcome.UnknownContact(contactName)
        val preferredAt = preferredTime?.let { nextOccurrence(it, now()) }
        val here = location()
        val plan = try {
            meetups.plan(contact, here?.latitude, here?.longitude, preferredAt)
        } catch (e: MeetupUnavailableException) {
            return MeetupOutcome.Unavailable(e.message ?: "${contact.name}님과 맞는 시간이 없어요")
        }
        val errand = errands.propose(
            ErrandDetail.Meetup(
                contact = contact,
                mode = plan.mode,
                place = plan.place,
                meetAt = plan.meetAt,
                userTravelMinutes = plan.userTravelMinutes,
                peerTravelMinutes = plan.peerTravelMinutes,
                preferredAt = preferredAt,
                latitude = here?.latitude,
                longitude = here?.longitude,
            ),
            origin,
        )
        return MeetupOutcome.AwaitingApproval(errand)
    }

    suspend fun approve(errandId: String, channel: ApprovalChannel): MeetupOutcome =
        MeetupOutcome.Finished(errands.approve(errandId, channel))

    fun pending(): Errand? = errands.errands.value.firstOrNull { it.isActive && it.detail is ErrandDetail.Meetup }
}
