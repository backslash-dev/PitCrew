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

fun dayLabel(date: LocalDate, today: LocalDate): String? = when (date) {
    today -> null
    today.plusDays(1) -> "내일"
    today.minusDays(1) -> "어제"
    else -> date.format(DateTimeFormatter.ofPattern("M월 d일"))
}

/** 받침에 맞춘 목적격 조사. 한글로 끝나지 않으면 받침 없는 쪽으로 본다. */
fun String.withObjectParticle(): String {
    val last = trimEnd().lastOrNull() ?: return this
    val hasFinal = last in '가'..'힣' && (last - '가') % 28 != 0
    return this + if (hasFinal) "을" else "를"
}
