package com.my_dream.server.crawler.codeescape

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 코드이스케이프 가로수길 응답을 실제 응답으로 고정한다 (조사 문서 "2026-10-27 서울 조사").
 * 푸터 전화 · 이메일 · 대표자 · 사업자번호는 픽스처에서 지웠다.
 *
 * | 픽스처 (2026-10-27 받음) | 테마 | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 10-29 | 3 | 27 · 22 | 가능 = `fun_rev_next` 링크 · 불가 = `rev_n.png` — 부분 문자열 `rev_n` 은 둘 다에 걸린다 |
 * | 10-31 (토) | 3 | 27 · 16 | |
 * | 11-10 (+14 · 창 밖) | 3 | 0행 | 드롭다운은 있고 표가 비었다 — 결과 없음이 아니라 실패 |
 * | 지점 2 | 0 | — | 없는 지점은 드롭다운까지 0개 |
 */
class CodeEscapeTest {

    private val crawler = CodeEscapeCrawler(HostRateLimiter(0)).apply { clock = at("2026-10-27T11:00:00+09:00") }

    private fun at(iso: String) = Clock.fixed(OffsetDateTime.parse(iso).toInstant(), ZoneId.of("Asia/Seoul"))

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.bufferedReader().readText()

    @Test
    fun `세 테마 · 가능은 예약 링크 · 불가는 rev_n 이미지`() {
        val day = crawler.parse(LocalDate.of(2026, 10, 29), fixture("codeescape-garosu-2026-10-29.html"))
        assertEquals(listOf("14", "15", "8"), day.themes.map { it.externalId })
        assertEquals(listOf("(수)산업스파이", "아는사이", "재즈인더레인 (Jazz in the Rain)"), day.themes.map { it.themeName })
        assertEquals(27 to 22, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        val jazz = day.themes.first { it.externalId == "8" }
        assertTrue(jazz.slots.first { it.time == LocalTime.of(11, 15) }.available)
        assertEquals(false, jazz.slots.first { it.time == LocalTime.of(13, 45) }.available)
    }

    @Test
    fun `토요일`() {
        val day = crawler.parse(LocalDate.of(2026, 10, 31), fixture("codeescape-garosu-2026-10-31.html"))
        assertEquals(27 to 16, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
    }

    @Test
    fun `창 안인데 0행이면 실패다 — 창 밖 · 휴무 · 틀린 날짜가 같은 모양이다`() {
        assertFailsWith<CodeEscapeCrawlException> { crawler.parse(LocalDate.of(2026, 11, 10), fixture("codeescape-garosu-2026-11-10-창밖.html")) }
    }

    @Test
    fun `오늘은 지난 회차가 빠지므로 밤엔 0행이 정상이다`() {
        val night = CodeEscapeCrawler(HostRateLimiter(0)).apply { clock = at("2026-11-10T23:00:00+09:00") }
        val day = night.parse(LocalDate.of(2026, 11, 10), fixture("codeescape-garosu-2026-11-10-창밖.html"))
        assertEquals(3, day.themes.size)
        assertTrue(day.themes.all { it.slots.isEmpty() })
    }

    @Test
    fun `없는 지점 · 다른 날짜 · 모르는 칸은 끊는다`() {
        assertFailsWith<CodeEscapeCrawlException> { crawler.parse(LocalDate.of(2026, 10, 29), fixture("codeescape-garosu-zizum2-없는지점.html")) }
        assertFailsWith<CodeEscapeCrawlException> { crawler.parse(LocalDate.of(2026, 10, 30), fixture("codeescape-garosu-2026-10-29.html")) }
        val odd = fixture("codeescape-garosu-2026-10-29.html").replaceFirst("rev_n.png", "rev_x.png")
        assertFailsWith<CodeEscapeCrawlException> { crawler.parse(LocalDate.of(2026, 10, 29), odd) }
    }
}
