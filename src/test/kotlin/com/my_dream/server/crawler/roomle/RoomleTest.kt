package com.my_dream.server.crawler.roomle

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 룸엘이스케이프 홍대 1호점 응답을 실제 응답으로 고정한다 (조사 문서 "2026-10-22 홍대 조사").
 *
 * | 픽스처 (2026-10-22 받음) | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|
 * | 베니(5) 10-22 | 10 · 8 | 가능 = selectTime 링크 + possible · 링크 인자로 테마·날짜를 되읽는다 |
 * | 퇴근길(6) 10-24 | 10 · 0 | 전 회차 불가 — 되읽을 링크가 없어도 받는다 |
 * | 베니 10-21 (어제) | — | 매장이 "조회할 수 없는 날" 로 거절 — 실패로 끊는다 |
 */
class RoomleTest {

    private val crawler = RoomleCrawler(HostRateLimiter(0))

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.bufferedReader().readText()

    private val today = LocalDate.of(2026, 10, 22)

    @Test
    fun `가능은 selectTime 링크가 걸린 possible 이다`() {
        val day = crawler.parse("5", today, fixture("roomle-t5-2026-10-22.html"))
        val theme = day.themes.single()
        assertEquals("베니", theme.themeName)
        assertEquals("5", theme.externalId)
        assertEquals(10 to 8, theme.slots.size to theme.slots.count { it.available })
        assertEquals(false, theme.slots.first { it.time == LocalTime.of(9, 20) }.available)
        assertTrue(theme.slots.first { it.time == LocalTime.of(12, 10) }.available)
    }

    @Test
    fun `전 회차 불가인 날도 받는다`() {
        val theme = crawler.parse("6", LocalDate.of(2026, 10, 24), fixture("roomle-t6-2026-10-24.html")).themes.single()
        assertEquals("퇴근길", theme.themeName)
        assertEquals(10 to 0, theme.slots.size to theme.slots.count { it.available })
    }

    @Test
    fun `링크의 테마나 날짜가 다르면 끊는다`() {
        // 퇴근길(6)을 물었는데 베니(5) 응답이 왔다 — 회차 요청엔 지점도 테마 이름도 안 실려서 링크 인자가 유일한 대조다
        assertFailsWith<RoomleCrawlException> { crawler.parse("6", today, fixture("roomle-t5-2026-10-22.html")) }
        assertFailsWith<RoomleCrawlException> { crawler.parse("5", today.plusDays(1), fixture("roomle-t5-2026-10-22.html")) }
    }

    @Test
    fun `빈 응답과 거절은 실패다 — 회차 없음이 아니다`() {
        assertFailsWith<RoomleCrawlException> { crawler.parse("5", LocalDate.of(2026, 10, 21), fixture("roomle-t5-2026-10-21-어제.html")) }
        // 없는 테마는 200 · 0바이트다
        assertFailsWith<RoomleCrawlException> { crawler.parse("5", today, "") }
    }

    @Test
    fun `작업의 테마 번호가 크롤러가 다는 번호와 같다 — 감시 빠른 확인이 고를 수 있게`() {
        val units = RoomleAdapter(crawler).plan(listOf(LocalDate.now()))
        assertEquals(Roomle.THEMES.keys.toList(), units.map { it.themeExternalId })
        assertTrue(units.all { it.covers(Roomle.KEY, LocalDate.now(), it.themeExternalId!!) })
    }
}
