package ai.pitcrew.app.data

import ai.pitcrew.app.ui.reservations.normalizePhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactPhoneTest {
    @Test
    fun `휴대폰 번호를 하이픈 형식으로 맞춘다`() {
        assertEquals("010-1234-5678", normalizePhone("01012345678"))
        assertEquals("010-1234-5678", normalizePhone(" 010-1234-5678 "))
        assertEquals("011-123-4567", normalizePhone("0111234567"))
    }

    @Test
    fun `휴대폰 번호가 아니면 거부한다`() {
        assertNull(normalizePhone("02-123-4567"))
        assertNull(normalizePhone("010-12-3456"))
        assertNull(normalizePhone(""))
    }
}
