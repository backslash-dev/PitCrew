package ai.pitcrew.app.errand

import java.time.LocalTime

sealed interface VoiceCommand {
    data class LateArrival(val appointment: LocalTime) : VoiceCommand
    data object LateArrivalWithoutTime : VoiceCommand
    data class Pickup(val item: String, val storeName: String?) : VoiceCommand
    data object PickupWithoutItem : VoiceCommand
    data class ChargeAndDine(val partySize: Int) : VoiceCommand
    data object Undo : VoiceCommand
    data object Unknown : VoiceCommand
}

/** 말한 문장을 할 일로 바꾼다. 서버 LLM이 붙으면 그 구현으로 교체한다. */
fun interface CommandInterpreter {
    suspend fun interpret(text: String): VoiceCommand
}

/**
 * 서버 전 임시 해석기. 되돌리기("취소/되돌려"), 충전("충전 … N명"), 지각("N시 … 늦"), 픽업("메뉴 … 주문/픽업")을 규칙으로 알아듣는다.
 * 시각은 숫자와 한글 수사(한~열두)를 모두 받고, 오후·저녁·밤이면 12시간을 더한다.
 */
class RuleBasedInterpreter(private val storeNames: suspend () -> List<String>) : CommandInterpreter {
    override suspend fun interpret(text: String): VoiceCommand =
        parse(text, runCatching { storeNames() }.getOrDefault(emptyList()))

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
        private val UNDO = Regex("취소|되돌려|되돌리|없던\\s*일|물러")
        private val CHARGE = Regex("충전")
        private val PARTY = Regex("(\\d{1,2}|${NATIVE_HOURS.joinToString("|") { it.first }})\\s*명")
        private val PARTY_WORDS = listOf("혼자" to 1, "둘이" to 2, "셋이" to 3, "넷이" to 4)
        private val MENU = listOf(
            "바닐라라테", "카페라테", "카푸치노", "콜드브루", "아메리카노", "라테", "라떼", "녹차",
            "샌드위치", "베이글", "식빵", "크루아상", "케이크", "도시락", "김밥", "주먹밥",
        )

        fun parse(text: String, storeNames: List<String>): VoiceCommand {
            val normalized = text.trim()
            if (UNDO.containsMatchIn(normalized)) return VoiceCommand.Undo
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
            return VoiceCommand.Unknown
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
