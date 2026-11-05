package ai.pitcrew.app.ui.format

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

fun LocalTime.hhmm(): String = format(HHMM)
fun LocalDateTime.hhmm(): String = format(HHMM)

fun minutesLabel(minutes: Long): String = when {
    minutes < 60 -> "${minutes}분"
    minutes % 60 == 0L -> "${minutes / 60}시간"
    else -> "${minutes / 60}시간 ${minutes % 60}분"
}

fun distanceLabel(meters: Int): String =
    if (meters < 1000) "${meters}m" else "%.1fkm".format(meters / 1000.0).replace(".0km", "km")

fun relativeLabel(time: LocalDateTime, now: LocalDateTime): String {
    val minutes = Duration.between(time, now).toMinutes()
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        time.toLocalDate() == now.toLocalDate() -> time.hhmm()
        else -> time.format(DateTimeFormatter.ofPattern("M월 d일 HH:mm"))
    }
}

/** 오늘이면 null, 그 외에는 "내일"·"어제"·"M월 d일". */
fun dayLabel(date: LocalDate, today: LocalDate): String? = when (date) {
    today -> null
    today.plusDays(1) -> "내일"
    today.minusDays(1) -> "어제"
    else -> date.format(DateTimeFormatter.ofPattern("M월 d일"))
}
