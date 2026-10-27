package com.my_dream.server.club

import com.my_dream.server.domain.ClubEvent
import com.my_dream.server.domain.ClubEventSegment
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 동호회 알림 문구 (계약 §4.10). **만드는 자리를 여기 하나로** 모은다 — 세 알림이 같은 표기(날짜 `10/24(금)` ·
 * 자정 넘김 `다음날 00:30` · 구간 3개 + `외 n`)를 따로 만들면 하나만 고쳐지는 날이 온다.
 */
object ClubPushText {

    private val DAY = DateTimeFormatter.ofPattern("M/d")
    private const val MAX_SEGMENTS = 3

    /** 자정부터의 분 → `19:30` · 1440 이상은 `다음날 00:30` (화면 표기와 같다) */
    fun time(min: Int): String = if (min >= 1440) "다음날 ${hhmm(min - 1440)}" else hhmm(min)

    private fun hhmm(min: Int) = "%02d:%02d".format(min / 60, min % 60)

    /** `10/24(금)` · 날짜 미정이면 `날짜 미정` */
    fun day(date: LocalDate?): String =
        date?.let { "${it.format(DAY)}(${it.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})" } ?: "날짜 미정"

    /** 전날 알림: `{제목} · 13:40 비밀의 화원 → 15:20 셜록의 서재`, 예약 전이면 끝에 ` (아직 예약 전이에요)` */
    fun reminder(event: ClubEvent, segs: List<ClubEventSegment>): Pair<String, String> {
        val schedule = when {
            segs.isNotEmpty() -> joined(segs) { "${time(it.startMin)} ${it.themeName}" }
            else -> halfDay(event) ?: event.startMin?.let(::time) ?: "하루 종일"
        }
        val body = listOfNotNull(event.title.ifEmpty { null }, schedule).joinToString(" · ") +
            if (event.booked) "" else " (아직 예약 전이에요)"
        return "내일 일정이 있어요" to body
    }

    /** 마감: `{제목 또는 구간} · {날짜} · {정원}/{정원}명` — 알림이 늦게 나가도 "찬 순간" 의 수를 쓴다 */
    fun full(event: ClubEvent, segs: List<ClubEventSegment>, capacity: Int): Pair<String, String> =
        "일정 자리가 다 찼어요" to "${what(event, segs)} · ${day(event.date)} · $capacity/${capacity}명"

    /**
     * 참여 (D50): 제목 `{참여한 사람} 님이 참여했어요`, 본문 `{제목 또는 구간} · {날짜} · {count}/{정원}명` — 정원이 없으면 `{count}명`.
     * 날짜·구간 표기는 마감과 같다
     */
    fun joined(event: ClubEvent, segs: List<ClubEventSegment>, nickname: String, count: Int): Pair<String, String> {
        val people = event.capacity?.let { "$count/${it}명" } ?: "${count}명"
        return "$nickname 님이 참여했어요" to "${what(event, segs)} · ${day(event.date)} · $people"
    }

    /** 예약 완료: `{제목 또는 구간} · {날짜} {첫 시각}` — 첫 시각은 첫 구간 → 오전·오후 → 일정 시작 → 없으면 뺀다 */
    fun booked(event: ClubEvent, segs: List<ClubEventSegment>): Pair<String, String> {
        val first = segs.firstOrNull()?.let { time(it.startMin) } ?: halfDay(event) ?: event.startMin?.let(::time)
        return "예약이 끝났어요" to listOfNotNull("${what(event, segs)} · ${day(event.date)}", first).joinToString(" ")
    }

    /**
     * 일정 댓글 (§4.16): 제목 `{쓴 사람} 님이 댓글을 남겼어요`, 본문 `{제목 또는 구간} · {댓글 첫 줄 앞 60자}…`.
     * 제목도 구간도 없는 일정이면 댓글만 — 앞에 ` · ` 가 홀로 남지 않게
     */
    fun comment(event: ClubEvent, segs: List<ClubEventSegment>, author: String, text: String): Pair<String, String> =
        "$author 님이 댓글을 남겼어요" to listOf(what(event, segs), preview(text)).filter { it.isNotEmpty() }.joinToString(" · ")

    /** 첫 줄 앞 60자, 넘으면 `…`. **코드 포인트로** 자른다 — UTF-16 으로 자르면 이모지가 반쪽 난다. 공지 알림(D46)도 이걸 쓴다 */
    fun preview(text: String): String {
        val line = text.lines().first().trimEnd()
        if (line.codePointCount(0, line.length) <= PREVIEW_LETTERS) return line
        return line.substring(0, line.offsetByCodePoints(0, PREVIEW_LETTERS)) + "…"
    }

    private const val PREVIEW_LETTERS = 60

    /**
     * 구간 없는 일정의 `오전`·`오후` (§4.10 · 2026-10-22). 앱이 "오전/오후" 체크를 **정확히** `0~719`·`720~1439` 로 저장한다 —
     * 서버에 칸을 따로 두지 않았다. 이 값이 아니면 null(시각으로 보인다). 손으로 `00:00~11:59` 를 고른 일정도 `오전` 이 되지만,
     * 그 둘은 같은 뜻이라 받아들였다
     */
    private fun halfDay(event: ClubEvent): String? = when (event.startMin to event.endMin) {
        0 to 719 -> "오전"
        720 to 1439 -> "오후"
        else -> null
    }

    private fun what(event: ClubEvent, segs: List<ClubEventSegment>) = event.title.ifEmpty { joined(segs) { it.themeName } }

    private fun joined(segs: List<ClubEventSegment>, show: (ClubEventSegment) -> String): String {
        val head = segs.take(MAX_SEGMENTS).joinToString(" → ", transform = show)
        return if (segs.size > MAX_SEGMENTS) "$head 외 ${segs.size - MAX_SEGMENTS}" else head
    }
}
