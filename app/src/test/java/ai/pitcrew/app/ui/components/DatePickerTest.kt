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
