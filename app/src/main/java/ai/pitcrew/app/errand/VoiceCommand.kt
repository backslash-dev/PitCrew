package ai.pitcrew.app.errand

import java.time.LocalTime

sealed interface VoiceCommand {
    data class LateArrival(val appointment: LocalTime) : VoiceCommand
    data object LateArrivalWithoutTime : VoiceCommand
    data class Pickup(val item: String, val storeName: String?) : VoiceCommand
    data object PickupWithoutItem : VoiceCommand
    data class ChargeAndDine(val partySize: Int) : VoiceCommand
    data object Undo : VoiceCommand
    /** [contactName]은 등록된 일행 이름(성 포함). 못 찾으면 null. */
    data class Meetup(val contactName: String?, val time: LocalTime?) : VoiceCommand
    data class MailReply(val instruction: String) : VoiceCommand
    data object Unknown : VoiceCommand
}

fun interface CommandInterpreter {
    suspend fun interpret(text: String): VoiceCommand
}

/** 서버 전 규칙 기반 해석기. 겹치면 앞 규칙이 이기고, Gleo가 영어로 옮겨 넘기기도 해 영어도 받는다. */
class RuleBasedInterpreter(
    private val storeNames: suspend () -> List<String>,
    private val contactNames: suspend () -> List<String> = { emptyList() },
) : CommandInterpreter {
    override suspend fun interpret(text: String): VoiceCommand =
        parse(text, runCatching { storeNames() }.getOrDefault(emptyList()), runCatching { contactNames() }.getOrDefault(emptyList()))

    companion object {
        private val NATIVE_HOURS = listOf(
            "열두" to 12, "열한" to 11, "열" to 10, "아홉" to 9, "여덟" to 8, "일곱" to 7,
            "여섯" to 6, "다섯" to 5, "네" to 4, "세" to 3, "두" to 2, "한" to 1,
        )
        private val HOUR = "(\\d{1,2}|${NATIVE_HOURS.joinToString("|") { it.first }})"
        // "한 시간"의 "한 시"를 1시로 읽지 않게 "시간"은 시각으로 보지 않는다
        private val TIME = Regex("(오전|아침|오후|저녁|밤)?\\s*${HOUR}\\s*시(?!간)\\s*(반|(\\d{1,2})\\s*분)?")
        private val LATE = Regex("늦|지각")
        private val ORDER = Regex("픽업|주문|포장|사\\s*(다|가|줘)")
        private val UNDO = Regex("취소|되돌려|되돌리|없던\\s*일|물러|(?i)\\b(undo|cancel|revert)\\b")
        private val CHARGE = Regex("충전|(?i)\\bcharg")
        private val MAIL = Regex("메일|답장|회신|(?i)\\b(e-?mail|reply|respond)")
        private val HALFWAY = Regex("중간|(?i)\\b(halfway|midway|middle)\\b")
        private val MEET = Regex("만나|보자|볼래|약속\\s*잡|(?i)\\bmeet")
        private val PARTY = Regex("(\\d{1,2}|${NATIVE_HOURS.joinToString("|") { it.first }})\\s*명")
        private val PARTY_WORDS = listOf("혼자" to 1, "둘이" to 2, "셋이" to 3, "넷이" to 4)
        private val MENU = listOf(
            "바닐라라테", "카페라테", "카푸치노", "콜드브루", "아메리카노", "라테", "라떼", "녹차",
            "샌드위치", "베이글", "식빵", "크루아상", "케이크", "도시락", "김밥", "주먹밥",
        )

        fun parse(text: String, storeNames: List<String>, contactNames: List<String> = emptyList()): VoiceCommand {
            val normalized = text.trim()
            if (UNDO.containsMatchIn(normalized)) return VoiceCommand.Undo
            if (MAIL.containsMatchIn(normalized)) return VoiceCommand.MailReply(normalized)
            val contact = spokenContact(normalized, contactNames)
            // "중간에 커피 주문해"는 픽업이므로, 일행 이름이 있거나 주문하는 말이 아닐 때만 약속 조율로 본다
            if (HALFWAY.containsMatchIn(normalized) && (contact != null || !ORDER.containsMatchIn(normalized))) {
                return VoiceCommand.Meetup(contact, parseTime(normalized))
            }
            if (CHARGE.containsMatchIn(normalized)) return VoiceCommand.ChargeAndDine(parsePartySize(normalized) ?: 1)
            if (LATE.containsMatchIn(normalized)) {
                return parseTime(normalized)?.let { VoiceCommand.LateArrival(it) } ?: VoiceCommand.LateArrivalWithoutTime
            }
            if (ORDER.containsMatchIn(normalized)) {
                val compact = normalized.replace(" ", "")
                val item = MENU.firstOrNull { compact.contains(it) } ?: return VoiceCommand.PickupWithoutItem
                val store = storeNames.firstOrNull { compact.contains(it.replace(" ", "")) }
                return VoiceCommand.Pickup(item, store)
            }
            // "민수랑 7시에 만나자"처럼 중간이라는 말이 없어도 일행과 만나자고 하면 약속 조율로 본다
            if (contact != null && MEET.containsMatchIn(normalized)) return VoiceCommand.Meetup(contact, parseTime(normalized))
            return VoiceCommand.Unknown
        }

        /** 두 사람 이상 걸리면 긴 이름(더 구체적인 쪽)을 고른다. */
        internal fun spokenContact(text: String, contactNames: List<String>): String? {
            val compact = text.replace(" ", "")
            return contactNames
                .mapNotNull { name ->
                    val full = name.replace(" ", "")
                    when {
                        compact.contains(full) -> name to full.length + 1
                        full.length >= 3 && compact.contains(full.drop(1)) -> name to full.length
                        else -> null
                    }
                }
                .maxByOrNull { it.second }?.first
        }

        internal fun parsePartySize(text: String): Int? {
            PARTY_WORDS.firstOrNull { text.contains(it.first) }?.let { return it.second }
            val count = PARTY.find(text)?.groupValues?.get(1) ?: return null
            return (count.toIntOrNull() ?: NATIVE_HOURS.first { it.first == count }.second).takeIf { it in 1..20 }
        }

        internal fun parseTime(text: String): LocalTime? {
            val match = TIME.find(text) ?: return null
            val (period, hourText, half, minuteText) = match.destructured
            var hour = hourText.toIntOrNull() ?: NATIVE_HOURS.first { it.first == hourText }.second
            if (hour !in 0..23) return null
            if (period in setOf("오후", "저녁", "밤") && hour < 12) hour += 12
            if (period in setOf("오전", "아침") && hour == 12) hour = 0
            val minute = when {
                half == "반" -> 30
                minuteText.isNotEmpty() -> minuteText.toInt()
                else -> 0
            }
            if (minute !in 0..59) return null
            return LocalTime.of(hour, minute)
        }
    }
}
