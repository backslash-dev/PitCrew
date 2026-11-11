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
    fun `날짜 버튼은 오늘·내일을 앞에 붙인다`() {
        assertEquals("오늘 11월 10일 (화)", dateButtonLabel(today, today))
        assertEquals("내일 11월 11일 (수)", dateButtonLabel(today.plusDays(1), today))
        assertEquals("11월 12일 (목)", dateButtonLabel(today.plusDays(2), today))
    }
}
