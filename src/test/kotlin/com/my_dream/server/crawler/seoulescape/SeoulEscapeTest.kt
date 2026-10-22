package com.my_dream.server.crawler.seoulescape

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 서울이스케이프룸 응답을 실제 응답으로 고정한다 (조사 문서 "2026-10-22 홍대 조사").
 *
 * | 픽스처 (2026-10-22 받음) | 테마 | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 홍대 10-27 | 1 | 11 · 6 | 가능은 클래스 + 숨은 예약 정보 둘 다 |
 * | 홍대 10-23 | 1 | 11 · 0 | 전 회차 불가인 날도 "지점" 칸으로 지점을 되읽는다 |
 * | 대구(branch=4) 10-22 | 8 | — | 다른 지점이 답하면 끊는다 — 틀린 branch 는 조용히 홍대로 가지만, 반대도 막는다 |
 */
class SeoulEscapeTest {

    private val crawler = SeoulEscapeCrawler(HostRateLimiter(0))

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.bufferedReader().readText()

    @Test
    fun `가능은 클래스와 숨은 예약 정보로 본다`() {
        val day = crawler.parse(LocalDate.of(2026, 10, 27), fixture("seoulescape-1-2026-10-27.html"))
        val theme = day.themes.single()
        assertEquals("오시리스", theme.themeName)
        assertEquals("48", theme.externalId)
        assertEquals(11 to 6, theme.slots.size to theme.slots.count { it.available })
        assertTrue(theme.slots.first { it.time == LocalTime.of(11, 10) }.available)
    }

    @Test
    fun `메타데이터 — 장르 · 시간 · 인원 · 공포 · 난이도`() {
        val theme = crawler.parse(LocalDate.of(2026, 10, 23), fixture("seoulescape-1-2026-10-23-전부불가.html")).themes.single()
        assertEquals("SF/스릴러", theme.genre)
        assertEquals(65, theme.runningMinutes)
        assertEquals("3~5명", theme.capacity)
        assertEquals(2.5, theme.horrorLevel, "size25 = 2.5 — 정수로 읽으면 25 가 된다")
        assertEquals(2.0, theme.difficulty)
        assertTrue(theme.posterUrl!!.startsWith("https://www.seoul-escape.com/"))
    }

    @Test
    fun `전 회차 불가인 날도 지점과 날짜를 되읽어 받는다`() {
        val theme = crawler.parse(LocalDate.of(2026, 10, 23), fixture("seoulescape-1-2026-10-23-전부불가.html")).themes.single()
        assertEquals(11, theme.slots.size)
        assertTrue(theme.slots.none { it.available })
    }

    @Test
    fun `다른 지점이나 다른 날짜가 답하면 끊는다`() {
        assertFailsWith<SeoulEscapeCrawlException> { crawler.parse(LocalDate.of(2026, 10, 22), fixture("seoulescape-4-2026-10-22-대구.html")) }
        assertFailsWith<SeoulEscapeCrawlException> { crawler.parse(LocalDate.of(2026, 10, 28), fixture("seoulescape-1-2026-10-27.html")) }
        // 창 밖은 302 라 본문이 비어 온다
        assertFailsWith<SeoulEscapeCrawlException> { crawler.parse(LocalDate.of(2026, 11, 6), "") }
    }
}
