package ai.pitcrew.app.errand

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class RuleBasedInterpreterTest {
    private val stores = listOf("세모 커피 역삼점", "노을 베이커리 서초점")
    private fun parse(text: String) = RuleBasedInterpreter.parse(text, stores)

    @Test
    fun `숫자 시각과 늦는다는 말이면 지각 대응이다`() {
        assertEquals(VoiceCommand.LateArrival(LocalTime.of(7, 0)), parse("7시 예약인데 늦을 것 같아"))
        assertEquals(VoiceCommand.LateArrival(LocalTime.of(19, 30)), parse("저녁 7시 반 약속 지각할 듯"))
        assertEquals(VoiceCommand.LateArrival(LocalTime.of(18, 40)), parse("18시 40분 예약 늦어"))
    }

    @Test
    fun `한글 수사 시각도 읽는다`() {
        assertEquals(VoiceCommand.LateArrival(LocalTime.of(11, 0)), parse("열한 시 예약 늦을 것 같아"))
        assertEquals(VoiceCommand.LateArrival(LocalTime.of(14, 30)), parse("오후 두시 반 미팅에 늦어"))
    }

    @Test
    fun `시각 없이 늦는다고만 하면 시각을 되묻는다`() {
        assertEquals(VoiceCommand.LateArrivalWithoutTime, parse("약속에 늦을 것 같아"))
    }

    @Test
    fun `메뉴와 주문 의도가 있으면 픽업이다`() {
        assertEquals(VoiceCommand.Pickup("아메리카노", null), parse("가는 길에 아메리카노 픽업 주문해줘"))
        assertEquals(VoiceCommand.Pickup("식빵", "노을 베이커리 서초점"), parse("노을베이커리 서초점에서 식빵 사줘"))
    }

    @Test
    fun `메뉴 없이 주문만 말하면 메뉴를 되묻는다`() {
        assertEquals(VoiceCommand.PickupWithoutItem, parse("가는 길에 픽업 주문해줘"))
    }

    @Test
    fun `해당 없는 말은 알아듣지 못한다`() {
        assertEquals(VoiceCommand.Unknown, parse("오늘 날씨 어때"))
    }

    @Test
    fun `취소하거나 되돌리라는 말이면 되돌리기다`() {
        assertEquals(VoiceCommand.Undo, parse("방금 거 취소해"))
        assertEquals(VoiceCommand.Undo, parse("아까 주문 되돌려줘"))
    }

    @Test
    fun `충전 말이면 인원과 함께 충전 줄서기다`() {
        assertEquals(VoiceCommand.ChargeAndDine(1), parse("충전하면서 밥 먹을래"))
        assertEquals(VoiceCommand.ChargeAndDine(2), parse("둘이 충전하는 동안 밥 먹자"))
        assertEquals(VoiceCommand.ChargeAndDine(3), parse("세 명 충전하면서 식사할래"))
    }

    @Test
    fun `한 시간 늦는다는 말의 한 시를 시각으로 읽지 않는다`() {
        assertEquals(VoiceCommand.LateArrival(LocalTime.of(7, 0)), parse("한 시간 늦을 것 같아, 7시 약속"))
        assertEquals(VoiceCommand.LateArrivalWithoutTime, parse("한 시간쯤 늦을 듯"))
    }
}
