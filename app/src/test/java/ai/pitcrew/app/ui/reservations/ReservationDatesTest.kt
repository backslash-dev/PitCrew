package ai.pitcrew.app.ui.reservations

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReservationDatesTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun `오늘부터 두 달까지 고르고 지난 기존 예약은 그 날짜부터 고른다`() {
        assertEquals(today..today.plusDays(60), dateRange(today, null))
        assertEquals(today.minusDays(2)..today.plusDays(60), dateRange(today, today.minusDays(2)))
        assertEquals(today..today.plusDays(60), dateRange(today, today.plusDays(5)))
    }

    @Test
    fun `날짜 칩은 오늘·내일과 요일 붙은 날짜로 보인다`() {
        assertEquals("오늘", dateChipLabel(today, today))
        assertEquals("내일", dateChipLabel(today.plusDays(1), today))
        assertEquals("11월 12일 (목)", dateChipLabel(today.plusDays(2), today))
    }
}
