package com.my_dream.server.club

import com.my_dream.server.domain.ClubEvent
import com.my_dream.server.domain.ClubEventSegment
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

/** 알림 문구 — 계약 §4.10 의 예시가 그대로 나오는지 */
class ClubPushTextTest {

    private fun event(title: String = "보드게임처럼", date: LocalDate? = LocalDate.of(2026, 10, 24), start: Int? = null, booked: Boolean = true) =
        ClubEvent(title, date, start, null, null, 4, booked, null, "u", Instant.EPOCH, Instant.EPOCH)

    private fun segs(e: ClubEvent, vararg s: Pair<Int, String>) = s.mapIndexed { i, (t, n) -> ClubEventSegment(e, i, n, null, null, t, null) }

    @Test
    fun `시각은 24시간제, 자정을 넘으면 다음날`() {
        assertEquals("09:05", ClubPushText.time(545))
        assertEquals("다음날 00:30", ClubPushText.time(1470))
        assertEquals("10/24(토)", ClubPushText.day(LocalDate.of(2026, 10, 24)))
        assertEquals("날짜 미정", ClubPushText.day(null))
    }

    @Test
    fun `전날 알림 — 제목 · 구간, 예약 전이면 꼬리`() {
        val e = event()
        assertEquals(
            "내일 일정이 있어요" to "보드게임처럼 · 13:40 비밀의 화원 → 15:20 셜록의 서재",
            ClubPushText.reminder(e, segs(e, 820 to "비밀의 화원", 920 to "셜록의 서재")),
        )
        val open = event(title = "", booked = false)
        assertEquals(
            "13:40 A → 15:20 B → 17:00 C 외 1 (아직 예약 전이에요)",
            ClubPushText.reminder(open, segs(open, 820 to "A", 920 to "B", 1020 to "C", 1120 to "D")).second,
        )
        assertEquals("모임 · 19:00", ClubPushText.reminder(event(title = "모임", start = 1140), emptyList()).second)
        assertEquals("모임 · 하루 종일", ClubPushText.reminder(event(title = "모임"), emptyList()).second)
    }

    @Test
    fun `마감과 예약 완료`() {
        val e = event(title = "")
        val s = segs(e, 1470 to "심야")
        assertEquals("일정 자리가 다 찼어요" to "심야 · 10/24(토) · 4/4명", ClubPushText.full(e, s, 4))
        assertEquals("예약이 끝났어요" to "심야 · 10/24(토) 다음날 00:30", ClubPushText.booked(e, s))
        assertEquals("보드게임처럼 · 날짜 미정", ClubPushText.booked(event(date = null), emptyList()).second, "첫 시각이 없으면 뺀다")
    }
}
