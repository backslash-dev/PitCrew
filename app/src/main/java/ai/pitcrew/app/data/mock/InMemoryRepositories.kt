package ai.pitcrew.app.data.mock

import ai.pitcrew.app.data.ContactRepository
import ai.pitcrew.app.data.ReservationRepository
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Reservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class InMemoryContactRepository(seed: List<Contact> = SampleData.contacts) : ContactRepository {
    private val _contacts = MutableStateFlow(seed)
    override val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    override fun upsert(contact: Contact) = _contacts.update { list ->
        (list.filterNot { it.id == contact.id } + contact).sortedBy { it.name }
    }

    override fun delete(id: String) = _contacts.update { list -> list.filterNot { it.id == id } }
}

class InMemoryReservationRepository(seed: List<Reservation>) : ReservationRepository {
    private val _reservations = MutableStateFlow(seed.sortedBy { it.time })
    override val reservations: StateFlow<List<Reservation>> = _reservations.asStateFlow()

    override fun upsert(reservation: Reservation) = _reservations.update { list ->
        (list.filterNot { it.id == reservation.id } + reservation).sortedBy { it.time }
    }

    override fun delete(id: String) = _reservations.update { list -> list.filterNot { it.id == id } }
}

/** 서버 연동 전 시연용 데이터. 예약 시각은 앱 실행 시각 기준으로 잡아 언제 시연해도 '오늘'이 된다. */
object SampleData {
    val contacts = listOf(
        Contact("c-minsu", "김민수", "010-2481-3307"),
        Contact("c-seoyeon", "이서연", "010-7716-0452"),
        Contact("c-junho", "박준호", "010-5093-8821"),
    )

    fun reservations(now: LocalDateTime): List<Reservation> {
        val dinner = now.plusMinutes(50).let { it.truncatedTo(ChronoUnit.HOURS).plusMinutes(if (it.minute >= 30) 60 else 30) }
        return listOf(
            Reservation("r-dinner", "온담 한식당", dinner, partySize = 3, companionIds = listOf("c-minsu", "c-seoyeon")),
            Reservation("r-meeting", "세모 커피 판교점", dinner.plusHours(2), partySize = 2, companionIds = listOf("c-junho")),
        )
    }
}
