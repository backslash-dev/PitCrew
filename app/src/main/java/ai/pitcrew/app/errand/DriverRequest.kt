package ai.pitcrew.app.errand

import ai.pitcrew.app.data.ErrandService
import ai.pitcrew.app.domain.ApprovalChannel
import ai.pitcrew.app.domain.Errand
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.ErrandState
import ai.pitcrew.app.domain.MeetupMode
import ai.pitcrew.app.gleo.GleoActionHandler
import ai.pitcrew.app.gleo.GleoFunction
import ai.pitcrew.app.gleo.GleoRequest
import ai.pitcrew.app.gleo.GleoResponse
import ai.pitcrew.app.gleo.GleoStatus
import org.json.JSONObject
import kotlin.reflect.KClass

/** 확인(confirmed)은 직전에 이 함수로 물은 같은 종류의 작업에만 받아들인다. */
class DriverRequestHandler(
    private val interpreter: CommandInterpreter,
    private val errands: ErrandService,
    private val undo: CancelLastErrandHandler,
    private val lateArrival: GleoActionHandler,
    private val pickup: GleoActionHandler,
    private val meetup: MeetupUseCase,
    private val mail: MailReplyUseCase,
    private val charging: ChargeAndDineUseCase,
) : GleoActionHandler {
    private val function = GleoFunction.HANDLE_PITCREW_REQUEST
    @Volatile private var askedId: String? = null

    override suspend fun handle(request: GleoRequest): GleoResponse {
        val text = request.params.optString("request").trim()
        val confirmed = request.params.optBoolean("confirmed", false)
        if (text.isEmpty()) {
            return GleoResponse.asking(function, "Please tell PitCrew what to do in the driver's own words.", listOf(mapOf("missingField" to "request")))
        }
        val command = interpreter.interpret(text)
        if (confirmed) asked(command)?.let { return approve(it) }
        return when (command) {
            VoiceCommand.Undo -> undo.handle(GleoRequest(function, JSONObject().put("confirmed", confirmed)))
            is VoiceCommand.LateArrival -> delegate(
                lateArrival,
                GleoRequest(GleoFunction.NOTIFY_LATE_ARRIVAL, JSONObject().put("appointmentTime", command.appointment.toClockTime())),
            )
            VoiceCommand.LateArrivalWithoutTime ->
                GleoResponse.asking(function, "The appointment time is required.", listOf(mapOf("missingField" to "appointmentTime")))
            is VoiceCommand.Pickup -> delegate(
                pickup,
                GleoRequest(GleoFunction.ORDER_PICKUP_ON_ROUTE, JSONObject().put("item", command.item).apply { command.storeName?.let { put("storeName", it) } }),
            )
            VoiceCommand.PickupWithoutItem ->
                GleoResponse.asking(function, "The food or drink to pick up is required.", listOf(mapOf("missingField" to "item")))
            is VoiceCommand.ChargeAndDine -> charge(command.partySize)
            is VoiceCommand.Meetup -> meet(command)
            is VoiceCommand.MailReply -> reply(command.instruction)
            VoiceCommand.Unknown -> GleoResponse.fail(
                function,
                "PitCrew did not understand the request. It can meet a contact halfway, reply to an email, find charging with a restaurant waitlist, or undo the last errand.",
            )
        }
    }

    // 지각·픽업 처리기가 만든 제안도 이 함수의 확인으로 승인할 수 있게 기억한다
    private suspend fun delegate(handler: GleoActionHandler, request: GleoRequest): GleoResponse {
        val response = handler.handle(request).copy(function = function)
        if (response.status == GleoStatus.ASKING) {
            remember(errands.errands.value.firstOrNull { it.state == ErrandState.AWAITING_APPROVAL && it.detail::class == kindOf(request.function) })
        }
        return response
    }

    private suspend fun charge(partySize: Int): GleoResponse = when (val outcome = charging.run(partySize)) {
        ChargeOutcome.NoRoute -> GleoResponse.fail(function, "There is no active navigation route, so PitCrew cannot find a charging stop on the way.")
        ChargeOutcome.NavigationUnavailable -> GleoResponse.fail(function, "The navigation service is not available.")
        ChargeOutcome.NoCharger -> GleoResponse.fail(function, "No charging station with an open stall and a nearby restaurant was found on the route.")
        is ChargeOutcome.NotNeeded -> GleoResponse.success(function, "The battery is at ${outcome.batteryPercent} percent, so charging is not needed now.")
        is ChargeOutcome.AwaitingApproval -> {
            val d = outcome.errand.detail as ErrandDetail.ChargeAndDine
            remember(outcome.errand)
            GleoResponse.asking(
                function,
                "PitCrew can add ${d.charger.name} as a charging stop for about ${d.chargeMinutes} minutes and join the waitlist at ${d.restaurant.name} for ${d.partySize}. User approval is required.",
                listOf(mapOf("charger" to d.charger.name, "restaurant" to d.restaurant.name, "chargeMinutes" to d.chargeMinutes)),
            )
        }
    }

    private suspend fun meet(command: VoiceCommand.Meetup): GleoResponse = when (val outcome = meetup.propose(command.contactName, command.time)) {
        is MeetupOutcome.UnknownContact -> GleoResponse.asking(
            function,
            "PitCrew needs the name of a contact registered in the PitCrew app to arrange the meeting.",
            listOf(mapOf("missingField" to "contact")),
        )
        is MeetupOutcome.Unavailable -> GleoResponse.fail(function, "The contact is not available at that time, so no meeting was arranged.", listOf(mapOf("reason" to outcome.reason)))
        is MeetupOutcome.AwaitingApproval -> {
            remember(outcome.errand)
            GleoResponse.asking(function, "${outcome.errand.meetupProposal()} User approval is required.", listOf(outcome.errand.meetupData()))
        }
        is MeetupOutcome.Finished -> finished(outcome.errand)
    }

    private suspend fun reply(instruction: String): GleoResponse = when (val outcome = mail.draft(instruction)) {
        MailReplyOutcome.NoMail -> GleoResponse.fail(function, "There is no unanswered email to reply to.")
        is MailReplyOutcome.AwaitingReview -> {
            val d = outcome.errand.detail as ErrandDetail.MailReply
            GleoResponse.success(
                function,
                "PitCrew drafted a reply to the email from ${d.mail.senderName}. For safety it will be sent only after the driver reviews it on the PitCrew screen when the car is stopped.",
                listOf(mapOf("from" to d.mail.senderName, "subject" to d.mail.subject, "draft" to d.draft)),
            )
        }
    }

    private suspend fun approve(errand: Errand): GleoResponse {
        askedId = null
        return finished(errands.approve(errand.id, ApprovalChannel.VOICE))
    }

    private fun finished(errand: Errand): GleoResponse = when (errand.state) {
        ErrandState.COMPLETED -> GleoResponse.success(function, "PitCrew finished it: ${errand.doneDescription()}.", listOfNotNull((errand.detail as? ErrandDetail.Meetup)?.let { errand.meetupData() }))
        ErrandState.IN_PROGRESS -> GleoResponse.success(function, "PitCrew is processing it. The result will be shown in the PitCrew app.")
        ErrandState.DECLINED -> GleoResponse.fail(function, "The user declined this on the screen.")
        ErrandState.EXPIRED -> GleoResponse.fail(function, "The proposal expired. Please ask again.")
        else -> GleoResponse.fail(function, "It failed: ${errand.failureReason ?: "unknown error"}.")
    }

    private fun remember(errand: Errand?) {
        askedId = errand?.id
    }

    private fun asked(command: VoiceCommand): Errand? {
        val id = askedId ?: return null
        val errand = errands.errands.value.firstOrNull { it.id == id && it.state == ErrandState.AWAITING_APPROVAL } ?: return null
        val kind = when (command) {
            is VoiceCommand.Meetup -> ErrandDetail.Meetup::class
            is VoiceCommand.ChargeAndDine -> ErrandDetail.ChargeAndDine::class
            is VoiceCommand.LateArrival, VoiceCommand.LateArrivalWithoutTime -> ErrandDetail.LateArrival::class
            is VoiceCommand.Pickup, VoiceCommand.PickupWithoutItem -> ErrandDetail.Pickup::class
            // Gleo가 "응"만 넘기면 해석이 안 되므로 물었던 작업을 그대로 승인한다
            VoiceCommand.Unknown -> errand.detail::class
            else -> return null
        }
        return errand.takeIf { it.detail::class == kind }
    }

    private fun kindOf(function: GleoFunction): KClass<out ErrandDetail>? = when (function) {
        GleoFunction.NOTIFY_LATE_ARRIVAL -> ErrandDetail.LateArrival::class
        GleoFunction.ORDER_PICKUP_ON_ROUTE -> ErrandDetail.Pickup::class
        GleoFunction.HANDLE_PITCREW_REQUEST -> null
    }

    private fun Errand.meetupProposal(): String {
        val d = detail as ErrandDetail.Meetup
        return when (d.mode) {
            MeetupMode.AGENT -> "PitCrew checked with ${d.contact.name}'s agent: meet at ${d.place?.name} at ${d.meetAt?.toLocalTime()?.toClockTime()}, " +
                "about ${d.userTravelMinutes} minutes for the driver and ${d.peerTravelMinutes} minutes for ${d.contact.name}. PitCrew will book it and confirm with their agent."
            MeetupMode.SMS -> "${d.contact.name} has no agent, so PitCrew will text them to ask their location and time, then book a place fair for both and text them the details."
        }
    }

    private fun Errand.meetupData(): Map<String, Any> {
        val d = detail as ErrandDetail.Meetup
        return buildMap {
            put("contact", d.contact.name)
            put("mode", d.mode.name)
            d.place?.let { put("place", it.name) }
            d.meetAt?.let { put("meetTime", it.toLocalTime().toClockTime()) }
        }
    }

    private fun Errand.doneDescription(): String = when (val d = detail) {
        is ErrandDetail.Meetup -> "booked ${d.place?.name ?: "the meeting place"} at ${d.meetAt?.toLocalTime()?.toClockTime()} and shared it with ${d.contact.name}"
        is ErrandDetail.ChargeAndDine -> "joined the waitlist at ${d.restaurant.name} and added ${d.charger.name} as a stop"
        is ErrandDetail.LateArrival -> "changed the reservation to ${d.proposedTime.toClockTime()}"
        is ErrandDetail.Pickup -> "ordered ${d.item} at ${d.store.displayName} and added it as a stop"
        is ErrandDetail.MailReply -> "sent the reply to ${d.mail.senderName}"
    }
}
