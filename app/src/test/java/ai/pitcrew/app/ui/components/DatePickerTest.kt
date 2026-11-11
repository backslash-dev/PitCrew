package ai.pitcrew.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DatePickerTest {
    @Test
    fun `달력은 일요일부터 시작하고 앞뒤 빈칸을 채운다`() {
        // 2026년 11월 1일은 일요일
        val november = monthWeeks(YearMonth.of(2026, 11))
        assertEquals(LocalDate.of(2026, 11, 1), november.first().first())
        assertEquals(5, november.size)
        assertNull(november.last().last())
        // 2026년 12월 1일은 화요일이라 일·월이 빈칸
        val december = monthWeeks(YearMonth.of(2026, 12))
        assertNull(december.first()[0])
        assertNull(december.first()[1])
        assertEquals(LocalDate.of(2026, 12, 1), december.first()[2])
        december.forEach { assertEquals(7, it.size) }
    }
}

class TimeLabelTest {
    @Test
    fun `시각은 오전·오후와 12시간제로 읽고 정각은 분을 뺀다`() {
        assertEquals("오전 12시", timeLabel(java.time.LocalTime.of(0, 0)))
        assertEquals("오전 9시 30분", timeLabel(java.time.LocalTime.of(9, 30)))
        assertEquals("오후 12시 10분", timeLabel(java.time.LocalTime.of(12, 10)))
        assertEquals("오후 6시", timeLabel(java.time.LocalTime.of(18, 0)))
    }
}
