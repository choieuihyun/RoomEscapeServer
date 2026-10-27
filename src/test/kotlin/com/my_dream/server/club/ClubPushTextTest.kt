package com.my_dream.server.club

import com.my_dream.server.domain.ClubEvent
import com.my_dream.server.domain.ClubEventSegment
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

/** 알림 문구 — 계약 §4.10 의 예시가 그대로 나오는지 */
class ClubPushTextTest {

    private fun event(title: String = "보드게임처럼", date: LocalDate? = LocalDate.of(2026, 10, 24), start: Int? = null, booked: Boolean = true, end: Int? = null) =
        ClubEvent(title, date, start, end, null, 4, booked, null, "u", Instant.EPOCH, Instant.EPOCH)

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

    @Test
    fun `오전·오후 일정 — 정확히 0~719 · 720~1439 이고 구간이 없을 때만`() {
        val am = event(title = "모임", start = 0, end = 719)
        val pm = event(title = "모임", start = 720, end = 1439)
        assertEquals("모임 · 오전", ClubPushText.reminder(am, emptyList()).second)
        assertEquals("모임 · 10/24(토) 오전", ClubPushText.booked(am, emptyList()).second)
        assertEquals("모임 · 10/24(토) 오후", ClubPushText.booked(pm, emptyList()).second)
        assertEquals("오후 (아직 예약 전이에요)", ClubPushText.reminder(event(title = "", start = 720, end = 1439, booked = false), emptyList()).second)
        // 끝이 조금이라도 다르면 시각이다 — 사람이 고른 "12:00~22:00" 을 "오후" 로 뭉개면 시각이 사라진다
        assertEquals("모임 · 12:00", ClubPushText.reminder(event(title = "모임", start = 720, end = 1320), emptyList()).second)
        assertEquals("모임 · 00:00", ClubPushText.reminder(event(title = "모임", start = 0), emptyList()).second)
        // 구간이 있으면 구간이 먼저다
        assertEquals("모임 · 10/24(토) 13:40", ClubPushText.booked(am, segs(am, 820 to "A")).second)
    }

    @Test
    fun `일정 댓글 — 쓴 사람 닉네임, 제목 또는 구간 · 첫 줄 앞 60자, 둘 다 없으면 댓글만`() {
        val (title, body) = ClubPushText.comment(event(title = "모임"), emptyList(), "닉나", "좋아요\n둘째 줄")
        assertEquals("닉나 님이 댓글을 남겼어요", title)
        assertEquals("모임 · 좋아요", body)
        val untitled = event(title = "")
        assertEquals("A → B · ${"🙂".repeat(60)}…", ClubPushText.comment(untitled, segs(untitled, 600 to "A", 700 to "B"), "닉", "🙂".repeat(61)).second, "코드 포인트로 자른다")
        assertEquals("안녕", ClubPushText.comment(untitled, emptyList(), "닉", "안녕").second, "앞에 ' · ' 가 홀로 남지 않는다")
    }

    @Test
    fun `참여 — 제목은 들어간 사람, 본문은 마감과 같은 표기에 수 · 정원이 없으면 수만`() {
        assertEquals("닉너 님이 참여했어요" to "보드게임처럼 · 10/24(토) · 3/4명", ClubPushText.joined(event(), emptyList(), "닉너", 3))
        val untitled = event(title = "")
        val open = ClubEvent("", null, null, null, null, null, true, null, "u", Instant.EPOCH, Instant.EPOCH)
        assertEquals("A · 10/24(토) · 2/4명", ClubPushText.joined(untitled, segs(untitled, 600 to "A"), "닉", 2).second)
        assertEquals("B · 날짜 미정 · 5명", ClubPushText.joined(open, segs(open, 600 to "B"), "닉", 5).second)
    }
}
