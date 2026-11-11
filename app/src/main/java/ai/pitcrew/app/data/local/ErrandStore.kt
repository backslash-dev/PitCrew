package ai.pitcrew.app.data.local

import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Charger
import ai.pitcrew.app.domain.Contact
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandOrigin
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.ErrandStep
import ai.pitcrew.app.domain.Mail
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.domain.MeetupPlace
import ai.pitcrew.app.domain.NearbyRestaurant
import ai.pitcrew.app.domain.StepState
import ai.pitcrew.app.domain.Store
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.LocalTime

interface ErrandStore {
    fun load(): List<Errand>
    fun save(errands: List<Errand>)
}

/** 임시 파일에 쓴 뒤 교체해 도중 종료돼도 깨지지 않게 한다. 읽지 못하면 빈 목록으로 시작한다. */
class FileErrandStore(private val file: File) : ErrandStore {
    override fun load(): List<Errand> {
        if (!file.exists()) return emptyList()
        return try {
            ErrandJson.decode(file.readText())
        } catch (e: IOException) {
            Log.w(TAG, "errand store read failed", e)
            emptyList()
        } catch (e: JSONException) {
            Log.w(TAG, "errand store corrupted, starting empty", e)
            emptyList()
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "errand store has unknown values, starting empty", e)
            emptyList()
        }
    }

    @Synchronized
    override fun save(errands: List<Errand>) {
        try {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(ErrandJson.encode(errands))
            if (!temp.renameTo(file)) throw IOException("rename failed: $temp -> $file")
        } catch (e: IOException) {
            Log.w(TAG, "errand store write failed", e)
        }
    }

    private companion object {
        const val TAG = "FileErrandStore"
    }
}

object ErrandJson {
    private const val VERSION = 1

    fun encode(errands: List<Errand>): String =
        JSONObject().put("version", VERSION).put("errands", JSONArray(errands.map(::errand))).toString()

    /** @throws JSONException 형식이 맞지 않을 때 */
    fun decode(text: String): List<Errand> {
        val root = JSONObject(text)
        if (root.optInt("version") != VERSION) return emptyList()
        val list = root.getJSONArray("errands")
        return (0 until list.length()).map { errand(list.getJSONObject(it)) }
    }

    private fun errand(e: Errand) = JSONObject()
        .put("id", e.id)
        .put("createdAt", e.createdAt.toString())
        .put("state", e.state.name)
        .put("detail", detail(e.detail))
        .put("steps", JSONArray(e.steps.map { s -> JSONObject().put("title", s.title).put("state", s.state.name).putOpt("at", s.at?.toString()) }))
        .put("origin", e.origin.name)
        .putOpt("approvedVia", e.approvedVia?.name)
        .putOpt("finishedAt", e.finishedAt?.toString())
        .putOpt("failureReason", e.failureReason)
        .putOpt("arrivalAnnouncedAt", e.arrivalAnnouncedAt?.toString())
        .putOpt("reference", e.reference)
        .put("undoing", e.undoing)
        .putOpt("undoneVia", e.undoneVia?.name)
        .putOpt("undoNote", e.undoNote)

    private fun errand(o: JSONObject) = Errand(
        id = o.getString("id"),
        createdAt = LocalDateTime.parse(o.getString("createdAt")),
        state = ErrandState.valueOf(o.getString("state")),
        detail = detail(o.getJSONObject("detail")),
        steps = o.getJSONArray("steps").let { a ->
            (0 until a.length()).map { i ->
                val s = a.getJSONObject(i)
                ErrandStep(s.getString("title"), StepState.valueOf(s.getString("state")), s.optStringOrNull("at")?.let(LocalDateTime::parse))
            }
        },
        origin = ErrandOrigin.valueOf(o.getString("origin")),
        approvedVia = o.optStringOrNull("approvedVia")?.let(ApprovalChannel::valueOf),
        finishedAt = o.optStringOrNull("finishedAt")?.let(LocalDateTime::parse),
        failureReason = o.optStringOrNull("failureReason"),
        arrivalAnnouncedAt = o.optStringOrNull("arrivalAnnouncedAt")?.let(LocalDateTime::parse),
        reference = o.optStringOrNull("reference"),
        undoing = o.optBoolean("undoing"),
        undoneVia = o.optStringOrNull("undoneVia")?.let(ApprovalChannel::valueOf),
        undoNote = o.optStringOrNull("undoNote"),
    )

    private fun detail(d: ErrandDetail): JSONObject = when (d) {
        is ErrandDetail.LateArrival -> JSONObject()
            .put("type", "late")
            .putOpt("reservationId", d.reservationId)
            .putOpt("placeName", d.placeName)
            .put("appointment", d.appointment.toString())
            .put("estimatedArrival", d.estimatedArrival.toString())
            .put("delayMinutes", d.delayMinutes)
            .put("proposedTime", d.proposedTime.toString())
            .put("companions", JSONArray(d.companions.map(::contact)))
            .put("followUp", d.followUp)
        is ErrandDetail.Pickup -> JSONObject()
            .put("type", "pickup")
            .put("item", d.item)
            .put("store", store(d.store))
            .put("readyAt", d.readyAt.toString())
        is ErrandDetail.ChargeAndDine -> JSONObject()
            .put("type", "charge")
            .put("charger", charger(d.charger))
            .put("restaurant", restaurant(d.restaurant))
            .put("partySize", d.partySize)
            .putOpt("batteryPercent", d.batteryPercent)
            .put("chargeMinutes", d.chargeMinutes)
            .put("arriveAt", d.arriveAt.toString())
        is ErrandDetail.Meetup -> JSONObject()
            .put("type", "meetup")
            .put("contact", contact(d.contact))
            .put("mode", d.mode.name)
            .putOpt("place", d.place?.let { JSONObject().put("id", it.id).put("name", it.name).put("latitude", it.latitude).put("longitude", it.longitude) })
            .putOpt("meetAt", d.meetAt?.toString())
            .putOpt("userTravelMinutes", d.userTravelMinutes)
            .putOpt("peerTravelMinutes", d.peerTravelMinutes)
            .putOpt("preferredAt", d.preferredAt?.toString())
            .putOpt("latitude", d.latitude)
            .putOpt("longitude", d.longitude)
        is ErrandDetail.MailReply -> JSONObject()
            .put("type", "mail")
            .put("mail", JSONObject()
                .put("id", d.mail.id).put("senderName", d.mail.senderName).put("senderEmail", d.mail.senderEmail)
                .put("subject", d.mail.subject).put("summary", d.mail.summary).put("receivedAt", d.mail.receivedAt.toString()))
            .put("instruction", d.instruction)
            .put("draft", d.draft)
    }

    private fun detail(o: JSONObject): ErrandDetail = when (val type = o.getString("type")) {
        "late" -> ErrandDetail.LateArrival(
            reservationId = o.optStringOrNull("reservationId"),
            placeName = o.optStringOrNull("placeName"),
            appointment = LocalDateTime.parse(o.getString("appointment")),
            estimatedArrival = LocalDateTime.parse(o.getString("estimatedArrival")),
            delayMinutes = o.getLong("delayMinutes"),
            proposedTime = LocalTime.parse(o.getString("proposedTime")),
            companions = o.getJSONArray("companions").let { a -> (0 until a.length()).map { i -> contact(a.getJSONObject(i)) } },
            followUp = o.optBoolean("followUp"),
        )
        "pickup" -> ErrandDetail.Pickup(o.getString("item"), store(o.getJSONObject("store")), LocalTime.parse(o.getString("readyAt")))
        "charge" -> ErrandDetail.ChargeAndDine(
            charger = charger(o.getJSONObject("charger")),
            restaurant = restaurant(o.getJSONObject("restaurant")),
            partySize = o.getInt("partySize"),
            batteryPercent = if (o.has("batteryPercent")) o.getInt("batteryPercent") else null,
            chargeMinutes = o.getInt("chargeMinutes"),
            arriveAt = LocalTime.parse(o.getString("arriveAt")),
        )
        "meetup" -> ErrandDetail.Meetup(
            contact = contact(o.getJSONObject("contact")),
            mode = MeetupMode.valueOf(o.getString("mode")),
            place = o.optJSONObject("place")?.let { MeetupPlace(it.getString("id"), it.getString("name"), it.getDouble("latitude"), it.getDouble("longitude")) },
            meetAt = o.optStringOrNull("meetAt")?.let(LocalDateTime::parse),
            userTravelMinutes = if (o.has("userTravelMinutes")) o.getInt("userTravelMinutes") else null,
            peerTravelMinutes = if (o.has("peerTravelMinutes")) o.getInt("peerTravelMinutes") else null,
            preferredAt = o.optStringOrNull("preferredAt")?.let(LocalDateTime::parse),
            latitude = if (o.has("latitude")) o.getDouble("latitude") else null,
            longitude = if (o.has("longitude")) o.getDouble("longitude") else null,
        )
        "mail" -> o.getJSONObject("mail").let { m ->
            ErrandDetail.MailReply(
                mail = Mail(
                    m.getString("id"), m.getString("senderName"), m.getString("senderEmail"),
                    m.getString("subject"), m.getString("summary"), LocalDateTime.parse(m.getString("receivedAt")),
                ),
                instruction = o.getString("instruction"),
                draft = o.getString("draft"),
            )
        }
        else -> throw IllegalArgumentException("unknown errand type $type")
    }

    private fun contact(c: Contact) = JSONObject().put("id", c.id).put("name", c.name).put("phone", c.phone)

    private fun contact(o: JSONObject) = Contact(o.getString("id"), o.getString("name"), o.getString("phone"))

    private fun store(s: Store) = JSONObject()
        .put("id", s.id).put("name", s.name).put("branch", s.branch)
        .put("latitude", s.latitude).put("longitude", s.longitude)
        .put("detourMinutes", s.detourMinutes).put("prepMinutes", s.prepMinutes)

    private fun store(o: JSONObject) = Store(
        o.getString("id"), o.getString("name"), o.getString("branch"),
        o.getDouble("latitude"), o.getDouble("longitude"), o.getInt("detourMinutes"), o.getInt("prepMinutes"),
    )

    private fun charger(c: Charger) = JSONObject()
        .put("id", c.id).put("name", c.name).put("latitude", c.latitude).put("longitude", c.longitude)
        .put("detourMinutes", c.detourMinutes).put("powerKw", c.powerKw).put("availableStalls", c.availableStalls)
        .put("restaurants", JSONArray(c.restaurants.map(::restaurant)))

    private fun charger(o: JSONObject) = Charger(
        o.getString("id"), o.getString("name"), o.getDouble("latitude"), o.getDouble("longitude"),
        o.getInt("detourMinutes"), o.getInt("powerKw"), o.getInt("availableStalls"),
        o.getJSONArray("restaurants").let { a -> (0 until a.length()).map { restaurant(a.getJSONObject(it)) } },
    )

    private fun restaurant(r: NearbyRestaurant) =
        JSONObject().put("id", r.id).put("name", r.name).put("walkMinutes", r.walkMinutes).put("waitMinutes", r.waitMinutes)

    private fun restaurant(o: JSONObject) = NearbyRestaurant(o.getString("id"), o.getString("name"), o.getInt("walkMinutes"), o.getInt("waitMinutes"))

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
