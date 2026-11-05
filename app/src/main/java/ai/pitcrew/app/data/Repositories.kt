package ai.pitcrew.app.data

import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.domain.Store
import kotlinx.coroutines.flow.StateFlow

interface ContactRepository {
    val contacts: StateFlow<List<Contact>>
    fun upsert(contact: Contact)
    fun delete(id: String)
}

interface ReservationRepository {
    val reservations: StateFlow<List<Reservation>>
    fun upsert(reservation: Reservation)
    fun delete(id: String)
}

interface StoreCatalog {
    /** 경로상 매장을 찾는다. 이름이 없으면 우회 시간이 가장 짧은 곳. */
    fun findOnRoute(storeName: String?): Store?

    /** 우회 시간이 짧은 순서의 경로상 매장 후보. */
    fun candidatesOnRoute(): List<Store>
}

/** 승인이 필요한 차 밖 작업(예약 변경·픽업 주문)을 만들고 실행한다. 서버가 붙으면 구현만 교체한다. */
interface ErrandService {
    val errands: StateFlow<List<Errand>>

    fun propose(detail: ErrandDetail): Errand

    /** 실행이 끝날 때까지 기다리고 최종 상태를 돌려준다. */
    suspend fun approve(id: String, channel: ApprovalChannel): Errand

    fun decline(id: String)

    fun markArrivalAnnounced(id: String)
}
