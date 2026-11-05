package ai.pitcrew.app.data.remote

import ai.pitcrew.app.data.ContactRepository
import ai.pitcrew.app.data.ReservationRepository
import ai.pitcrew.app.data.StoreCatalog
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.domain.Store
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "RemoteRepositories"
private val INSTANT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

/** 기기 시간대의 시각을 서버가 받는 오프셋 포함 ISO-8601로 바꾼다. */
fun LocalDateTime.toInstantString(zone: ZoneId = ZoneId.systemDefault()): String = atZone(zone).format(INSTANT_FORMAT)

fun String.toLocalDateTime(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    OffsetDateTime.parse(this).atZoneSameInstant(zone).toLocalDateTime()

/**
 * 화면은 바로 바뀌고 서버 저장은 뒤에서 한다. 저장에 실패하면 서버 상태로 다시 맞추고 [error]로 알린다.
 */
private abstract class SyncedList<T>(private val scope: CoroutineScope) {
    protected val items = MutableStateFlow<List<T>>(emptyList())
    protected val lastError = MutableStateFlow<String?>(null)
    @Volatile private var loaded = false

    protected abstract suspend fun load(): List<T>

    fun reload() {
        scope.launch { reloadNow() }
    }

    suspend fun reloadNow() {
        try {
            items.value = load()
            loaded = true
            lastError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "reload failed", e)
            lastError.value = e.userMessage()
        }
    }

    suspend fun ensureLoaded() {
        if (!loaded) reloadNow()
    }

    protected fun write(optimistic: (List<T>) -> List<T>, call: suspend () -> Unit) {
        items.update(optimistic)
        scope.launch {
            try {
                call()
                lastError.value = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "write failed", e)
                lastError.value = e.userMessage()
                reloadNow()
            }
        }
    }
}

class RemoteContactRepository(private val api: PitCrewApi, scope: CoroutineScope) : ContactRepository {
    private val list = object : SyncedList<Contact>(scope) {
        val flow get() = items.asStateFlow()
        val errorFlow get() = lastError.asStateFlow()
        override suspend fun load(): List<Contact> =
            api.get("v1/contacts").getJSONArray("contacts").objects().map { Contact(it.getString("id"), it.getString("name"), it.getString("phone")) }
        fun save(contact: Contact) = write({ l -> (l.filterNot { it.id == contact.id } + contact).sortedBy { it.name } }) {
            api.put("v1/contacts/${contact.id}", JSONObject().put("name", contact.name).put("phone", contact.phone))
        }
        fun remove(id: String) = write({ l -> l.filterNot { it.id == id } }) { api.delete("v1/contacts/$id") }
    }

    override val contacts: StateFlow<List<Contact>> = list.flow
    override val error: StateFlow<String?> = list.errorFlow
    override fun refresh() = list.reload()
    override suspend fun ensureLoaded() = list.ensureLoaded()
    override fun upsert(contact: Contact) = list.save(contact)
    override fun delete(id: String) = list.remove(id)
}

class RemoteReservationRepository(private val api: PitCrewApi, scope: CoroutineScope) : ReservationRepository {
    private val list = object : SyncedList<Reservation>(scope) {
        val flow get() = items.asStateFlow()
        val errorFlow get() = lastError.asStateFlow()
        override suspend fun load(): List<Reservation> =
            api.get("v1/reservations").getJSONArray("reservations").objects().map { it.toReservation() }
        fun save(reservation: Reservation) = write({ l -> (l.filterNot { it.id == reservation.id } + reservation).sortedBy { it.time } }) {
            api.put(
                "v1/reservations/${reservation.id}",
                JSONObject()
                    .put("placeName", reservation.placeName)
                    .put("reservedAt", reservation.time.toInstantString())
                    .put("partySize", reservation.partySize)
                    .put("companionIds", JSONArray(reservation.companionIds)),
            )
        }
        fun remove(id: String) = write({ l -> l.filterNot { it.id == id } }) { api.delete("v1/reservations/$id") }
    }

    override val reservations: StateFlow<List<Reservation>> = list.flow
    override val error: StateFlow<String?> = list.errorFlow
    override fun refresh() = list.reload()
    suspend fun refreshNow() = list.reloadNow()
    override suspend fun ensureLoaded() = list.ensureLoaded()
    override fun upsert(reservation: Reservation) = list.save(reservation)
    override fun delete(id: String) = list.remove(id)
}

class RemoteStoreCatalog(private val api: PitCrewApi) : StoreCatalog {
    override suspend fun findOnRoute(storeName: String?): Store? =
        stores(storeName?.takeIf { it.isNotBlank() }).firstOrNull()

    override suspend fun candidatesOnRoute(): List<Store> = stores(null)

    private suspend fun stores(name: String?): List<Store> =
        api.get("v1/stores", if (name != null) mapOf("name" to name) else emptyMap())
            .getJSONArray("stores").objects().map {
                Store(
                    id = it.getString("id"),
                    name = it.getString("name"),
                    branch = it.getString("branch"),
                    latitude = it.getDouble("latitude"),
                    longitude = it.getDouble("longitude"),
                    detourMinutes = it.getInt("detourMinutes"),
                    prepMinutes = it.getInt("prepMinutes"),
                )
            }
}

internal fun JSONObject.toReservation() = Reservation(
    id = getString("id"),
    placeName = getString("placeName"),
    time = getString("reservedAt").toLocalDateTime(),
    partySize = getInt("partySize"),
    companionIds = getJSONArray("companionIds").let { ids -> (0 until ids.length()).map(ids::getString) },
)

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
