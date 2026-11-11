package ai.pitcrew.app.data

import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Charger
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.Mail
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.MeetupPlace
import ai.pitcrew.app.domain.PastOrder
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.domain.Store
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

interface ContactRepository {
    val contacts: StateFlow<List<Contact>>
    /** 마지막 동기화 실패 안내. 성공하면 null. */
    val error: StateFlow<String?>
    fun refresh()
    /** 판단 전에 빈 목록으로 오판하지 않게 한다. */
    suspend fun ensureLoaded()
    fun upsert(contact: Contact)
    fun delete(id: String)
}

interface ReservationRepository {
    val reservations: StateFlow<List<Reservation>>
    val error: StateFlow<String?>
    fun refresh()
    suspend fun ensureLoaded()
    /** 다른 기기·Gleo가 바꾼 예약 시각으로 판단할 때 쓴다. 실패하면 이전 목록을 유지한다. */
    suspend fun refreshNow()
    fun upsert(reservation: Reservation)
    fun delete(id: String)
}

interface StoreCatalog {
    /** 이름이 없으면 우회 시간이 가장 짧은 곳. */
    suspend fun findOnRoute(storeName: String?): Store?

    suspend fun candidatesOnRoute(): List<Store>
}

interface ChargerCatalog {
    suspend fun onRoute(): List<Charger>
}

interface OrderHistory {
    /** 취소한 주문도 함께 준다. */
    suspend fun recent(): List<PastOrder>
}

/** 문자로 묻기 전에는 장소·시각이 없다. */
data class MeetupPlan(
    val mode: MeetupMode,
    val place: MeetupPlace?,
    val meetAt: LocalDateTime?,
    val userTravelMinutes: Int?,
    val peerTravelMinutes: Int?,
)

class MeetupUnavailableException(message: String) : Exception(message)

interface MeetupService {
    /** 에이전트가 없으면 [MeetupMode.SMS]만 돌려준다. 아무것도 보내지 않는다. */
    suspend fun plan(contact: Contact, latitude: Double?, longitude: Double?, preferredAt: LocalDateTime?): MeetupPlan
}

interface MailService {
    val mails: StateFlow<List<Mail>>
    suspend fun refreshNow()
    suspend fun draft(mail: Mail, instruction: String): String
}

interface ErrandService {
    val errands: StateFlow<List<Errand>>

    fun propose(detail: ErrandDetail, origin: ErrandOrigin = ErrandOrigin.REQUESTED): Errand

    suspend fun approve(id: String, channel: ApprovalChannel): Errand

    fun decline(id: String)

    fun markArrivalAnnounced(id: String)

    fun expireStale()

    /** 되돌릴 수 없는 상태면 그대로 돌려준다. */
    suspend fun undo(id: String, channel: ApprovalChannel): Errand
}

fun ErrandService.latestUndoable(now: LocalDateTime): Errand? =
    errands.value.filter { it.canUndo(now) }.maxByOrNull { it.finishedAt!! }
