package ai.pitcrew.app.gleo

import org.json.JSONArray
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GleoProtocolTest {
    private val pkg = "ai.pitcrew.app"

    @Test
    fun `함수명과 패키지명 접두 action을 모두 인식한다`() {
        assertEquals(GleoFunction.NOTIFY_LATE_ARRIVAL, GleoFunction.fromAction("NOTIFY_LATE_ARRIVAL", pkg))
        assertEquals(GleoFunction.ORDER_PICKUP_ON_ROUTE, GleoFunction.fromAction("ai.pitcrew.app.ORDER_PICKUP_ON_ROUTE", pkg))
        assertNull(GleoFunction.fromAction("ai.other.app.NOTIFY_LATE_ARRIVAL", pkg))
        assertNull(GleoFunction.fromAction(null, pkg))
    }

    @Test
    fun `JSON 객체 파라미터를 읽는다`() {
        assertEquals("19:00", GleoRequest.parseParams("""{"appointmentTime":"19:00"}""").getString("appointmentTime"))
    }

    @Test
    fun `따옴표로 감싼 파라미터를 읽는다`() {
        val escaped = "\"{\\\"title\\\":\\\"미팅\\\"}\""
        assertEquals("미팅", GleoRequest.parseParams(escaped).getString("title"))
        val docStyle = "\"{\"title\":\"미팅\", \"dateTime\":\"2025-02-20T06:00:00Z\"}\""
        assertEquals("2025-02-20T06:00:00Z", GleoRequest.parseParams(docStyle).getString("dateTime"))
    }

    @Test
    fun `파라미터가 없으면 빈 객체다`() {
        assertEquals(0, GleoRequest.parseParams(null).length())
        assertEquals(0, GleoRequest.parseParams("  ").length())
    }

    @Test(expected = JSONException::class)
    fun `객체가 아닌 파라미터는 거부한다`() {
        GleoRequest.parseParams("[1,2]")
    }

    @Test
    fun `콜백 data는 JSON 배열 문자열로 직렬화한다`() {
        val json = GleoResponse.asking(GleoFunction.NOTIFY_LATE_ARRIVAL, "Approval is required.", listOf(mapOf("delayMinutes" to 23L))).toJson()
        assertEquals("NOTIFY_LATE_ARRIVAL", json.getString("functionName"))
        assertEquals("asking", json.getString("status"))
        assertEquals(23, JSONArray(json.getString("data")).getJSONObject(0).getInt("delayMinutes"))
    }

    @Test
    fun `data가 없으면 필드를 넣지 않는다`() {
        assertFalse(GleoResponse.success(GleoFunction.NOTIFY_LATE_ARRIVAL, "Done.").toJson().has("data"))
    }
}
