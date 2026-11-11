package ai.pitcrew.app.errand

import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val TIME_PATTERN = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")
private val WIRE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

fun parseClockTime(value: String?): LocalTime? {
    val match = TIME_PATTERN.matchEntire(value?.trim() ?: return null) ?: return null
    return LocalTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
}

fun LocalTime.toClockTime(): String = format(WIRE_FORMAT)
