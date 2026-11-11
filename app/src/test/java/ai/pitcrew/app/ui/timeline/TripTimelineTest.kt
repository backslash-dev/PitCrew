package ai.pitcrew.app.ui.timeline

import ai.pitcrew.app.data.ErrandRunner
import ai.pitcrew.app.data.mock.MockStepExecutor
import ai.pitcrew.app.data.mock.MockStoreCatalog
import ai.pitcrew.app.domain.ErrandDetail
import ai.pitcrew.app.domain.Reservation
import ai.pitcrew.app.testing.FakeWaypoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

class TripTimelineTest {
    private val now = LocalDateTime.of(2026, 11, 11, 11, 30)
    private fun reservation(id: String, place: String, at: LocalDateTime) = Reservation(id, place, at, 2, emptyList())

    @Test
    fun `도착보다 이른 약속은 늦는 분을 붙이고 위치는 지금부터 도착 사이 비율이다`() {
        val arrival = now.plusMinutes(50)
        val t = TripTimeline.build(now, arrival, listOf(reservation("r1", "온담 한식당", now.plusMinutes(20))), emptyList())
        val mark = t.marks.single()
        assertEquals(0.4f, mark.fraction, 0.001f)
        assertEquals(30, mark.stops.single().lateMinutes)
        assertEquals(TripTimeline.Headline("온담 한식당 11:50에 30분 늦어요", late = true), t.headline())
        assertFalse(t.zoomed)
    }

    @Test
    fun `가까운 지점은 점 하나로 묶고, 문장은 가장 이른 늦는 약속과 나머지 수로 말한다`() {
        val arrival = now.plusMinutes(50)
        val t = TripTimeline.build(
            now, arrival,
            listOf(
                reservation("r1", "온담", now.plusMinutes(20)),
                reservation("r2", "팀 미팅", now.plusMinutes(20)),
                reservation("r3", "병원", now.plusMinutes(21)),
            ),
            emptyList(),
        )
        val mark = t.marks.single()
        assertTrue(mark.cluster)
        assertEquals(TripTimeline.Headline("온담 11:50에 30분 늦어요 · 2곳 더", late = true), t.headline())
    }

    @Test
    fun `도착이 멀면 다음 1시간만 펼치고 그 뒤 지점은 이후 개수로 줄인다`() {
        val arrival = now.plusHours(3)
        val t = TripTimeline.build(
            now, arrival,
            listOf(reservation("r1", "세모", now.plusMinutes(30)), reservation("r2", "회의", now.plusMinutes(150))),
            emptyList(),
        )
        assertTrue(t.zoomed)
        assertEquals(0.4f, t.marks.single().fraction, 0.001f)
        assertEquals(1, t.laterCount)
    }

    @Test
    fun `경로와 무관한 먼 약속은 빼고 픽업은 준비 시각에 둔다`() {
        val arrival = now.plusMinutes(50)
        val runner = ErrandRunner(MockStepExecutor(FakeWaypoints()), now = { now })
        runner.propose(ErrandDetail.Pickup("아메리카노", MockStoreCatalog.DEFAULT_STORES.first(), LocalTime.of(11, 45)))
        val t = TripTimeline.build(now, arrival, listOf(reservation("r9", "내일 저녁", now.plusDays(1))), runner.errands.value)
        val stop = t.marks.single().stops.single()
        assertEquals(StopKind.PICKUP, stop.kind)
        assertEquals(TripTimeline.Headline("다음 · 픽업 11:45", late = false), t.headline())
    }
}
