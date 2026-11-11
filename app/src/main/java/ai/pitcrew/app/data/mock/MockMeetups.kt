package ai.pitcrew.app.data.mock

import ai.pitcrew.app.data.MeetupPlan
import ai.pitcrew.app.data.MeetupService
import ai.pitcrew.app.data.MeetupUnavailableException
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.MeetupPlace
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.errand.MeetupPlanner
import java.time.Duration
import java.time.LocalDateTime

/** 서버 목업과 같은 사람·장소를 쓴다. */
class MockMeetupService(
    private val reservations: () -> List<Reservation>,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) : MeetupService {
    data class Peer(val hasAgent: Boolean, val latitude: Double, val longitude: Double)

    override suspend fun plan(contact: Contact, latitude: Double?, longitude: Double?, preferredAt: LocalDateTime?): MeetupPlan {
        val peer = peerOf(contact)
        if (!peer.hasAgent) return MeetupPlan(MeetupMode.SMS, null, null, null, null)
        return plan(contact, peer, latitude, longitude, preferredAt, MeetupMode.AGENT)
    }

    fun replied(contact: Contact, latitude: Double?, longitude: Double?, preferredAt: LocalDateTime?): MeetupPlan =
        plan(contact, peerOf(contact), latitude, longitude, preferredAt, MeetupMode.SMS)

    private fun plan(contact: Contact, peer: Peer, latitude: Double?, longitude: Double?, preferredAt: LocalDateTime?, mode: MeetupMode): MeetupPlan {
        val userLat = latitude ?: DEFAULT_ORIGIN_LAT
        val userLon = longitude ?: DEFAULT_ORIGIN_LON
        val place = MeetupPlanner.fairestPlace(PLACES, userLat, userLon, peer.latitude, peer.longitude)
            ?: throw MeetupUnavailableException("만날 장소를 찾지 못했어요")
        val userTravel = MeetupPlanner.travelMinutes(userLat, userLon, place.latitude, place.longitude)
        val peerTravel = MeetupPlanner.travelMinutes(peer.latitude, peer.longitude, place.latitude, place.longitude)
        val at = now()
        val meetAt = MeetupPlanner.meetAt(at, preferredAt, maxOf(userTravel, peerTravel), at, reservations().map { it.time })
        if (Duration.between(at, meetAt) > FREE_FOR) {
            throw MeetupUnavailableException(if (preferredAt != null) "${contact.name}님이 그 시간에는 어렵다고 해요" else "${contact.name}님이 오늘은 시간이 안 된다고 해요")
        }
        return MeetupPlan(mode, place, meetAt, userTravel, peerTravel)
    }

    // 등록하지 않은 사람은 에이전트가 없다고 보고 서울역 근처에서 답한다고 가정한다
    private fun peerOf(contact: Contact): Peer = PEERS[contact.id] ?: Peer(hasAgent = false, latitude = 37.5547, longitude = 126.9707)

    companion object {
        // 위치를 모르면 강남역에서 출발한다고 본다
        const val DEFAULT_ORIGIN_LAT = 37.4979
        const val DEFAULT_ORIGIN_LON = 127.0276
        private val FREE_FOR: Duration = Duration.ofHours(6)

        private val PEERS = mapOf(
            "c-minsu" to Peer(hasAgent = true, latitude = 37.3948, longitude = 127.1112),
            "c-seoyeon" to Peer(hasAgent = false, latitude = 37.5563, longitude = 126.9220),
            "c-junho" to Peer(hasAgent = true, latitude = 37.5133, longitude = 127.1001),
        )

        val PLACES = listOf(
            MeetupPlace("mp-yangjae", "양재 숲속 카페", 37.4846, 127.0342),
            MeetupPlace("mp-sadang", "사당 모퉁이 식당", 37.4765, 126.9816),
            MeetupPlace("mp-sinchon", "신촌 온기 카페", 37.5551, 126.9368),
            MeetupPlace("mp-seongsu", "성수 붉은벽 카페", 37.5446, 127.0557),
            MeetupPlace("mp-pangyo", "판교 테라스 키친", 37.3947, 127.1112),
            MeetupPlace("mp-jamsil", "잠실 호숫가 카페", 37.5112, 127.0981),
            MeetupPlace("mp-yeouido", "여의도 강변 식당", 37.5219, 126.9245),
            MeetupPlace("mp-gangnam", "강남 정원 카페", 37.4981, 127.0276),
        )
    }
}
