package com.my_dream.server.crawler.wpbooking

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 워드프레스 `booking_system` 응답을 매장마다 실제 응답으로 고정한다 (조사 문서 "2026-10-22 홍대 조사" · 아키텍처 D45).
 *
 * | 픽스처 (2026-10-22 받음) | 테마 | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 딥띵커 10-23 | 1 | 13 · 6 | 가능 = `class="submit"` · 주석 안 회차 모양은 걷는다 |
 * | 딥띵커 10-24 (공백 5바이트) | 1 | 0 | **매진된 날은 빈 응답** — 테마를 회차 0개 + closeMissing 으로 넘겨 그날 회차를 마감으로 내린다 |
 * | 쇼룸404 10-23 | 4 | 45 · 30 | 테마 번호를 비우면 전 테마가 한 번에 온다 |
 * | 쇼룸404 지점 99 | — | — | "이용 가능한 객실이 없습니다" — 지점 값이 틀렸다. 빈 하루가 아니다 |
 */
class WpBookingSystemTest {

    private val crawler = WpBookingCrawler(HostRateLimiter(0))
    private val deep = WpBookingSites.DEEPTHINKER
    private val show = WpBookingSites.SHOWROOM404

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.bufferedReader().readText()

    @Test
    fun `딥띵커 — 가능은 submit 클래스 · 메타데이터`() {
        val theme = crawler.parse(deep, LocalDate.of(2026, 10, 23), fixture("wpbooking-deepthinker-2026-10-23.html")).themes.single()
        assertEquals("옹이의 꿈", theme.themeName)
        assertEquals("1727", theme.externalId)
        assertEquals(13 to 6, theme.slots.size to theme.slots.count { it.available })
        assertEquals(false, theme.slots.first { it.time == LocalTime.of(10, 0) }.available)
        assertTrue(theme.slots.first { it.time == LocalTime.of(11, 0) }.available)
        assertEquals("2~5명", theme.capacity)
        assertEquals(65, theme.runningMinutes)
        assertTrue(theme.closeMissing)
    }

    @Test
    fun `매진된 날의 빈 응답은 회차 0개 + closeMissing 이다 — 실패가 아니다`() {
        val day = crawler.parse(deep, LocalDate.of(2026, 10, 24), fixture("wpbooking-deepthinker-2026-10-24-매진빈응답.html"))
        val theme = day.themes.single()
        assertEquals("1727", theme.externalId)
        assertTrue(theme.slots.isEmpty())
        assertTrue(theme.closeMissing, "켜져 있지 않으면 전에 가능이던 회차가 가짜 빈자리로 남는다")
    }

    @Test
    fun `쇼룸404 — 네 테마가 한 번에 온다`() {
        val day = crawler.parse(show, LocalDate.of(2026, 10, 23), fixture("wpbooking-showroom404-2026-10-23.html"))
        assertEquals(listOf("648", "653", "8281", "8284"), day.themes.map { it.externalId })
        assertEquals(listOf("한라아파트 4동 404호", "PIG", "꼬치 진다", "블랙홀"), day.themes.map { it.themeName })
        assertEquals(45 to 30, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
    }

    @Test
    fun `응답에 빠진 테마도 회차 0개 + closeMissing 으로 넘긴다`() {
        // 쇼룸 응답에서 PIG 블록 하나를 지웠다고 치자 — 그 테마도 그날 마감으로 내려야 한다
        val body = fixture("wpbooking-showroom404-2026-10-23.html")
        val blocks = body.split(Regex("""(?=<div class="theme[0-9]*-detail-info">)"""))
        val withoutPig = blocks.filterNot { "value=\"653\"" in it }.joinToString("")
        assertTrue(withoutPig.length < body.length, "픽스처 모양이 바뀌었다 — 이 테스트가 아무것도 안 본다")
        val pig = crawler.parse(show, LocalDate.of(2026, 10, 23), withoutPig).themes.single { it.externalId == "653" }
        assertTrue(pig.slots.isEmpty() && pig.closeMissing)
    }

    @Test
    fun `틀린 지점 · 설정에 없는 테마 · 다른 테마 번호는 끊는다`() {
        assertFailsWith<WpBookingCrawlException> { crawler.parse(show, LocalDate.of(2026, 10, 23), fixture("wpbooking-showroom404-loc99-없는지점.html")) }
        // 쇼룸 응답을 딥띵커로 읽으면 — 테마 집합이 달라 끊긴다(응답에 지점이 없어서 이게 대조다)
        assertFailsWith<WpBookingCrawlException> { crawler.parse(deep, LocalDate.of(2026, 10, 23), fixture("wpbooking-showroom404-2026-10-23.html")) }
        val mixed = fixture("wpbooking-deepthinker-2026-10-23.html").replaceFirst("id=\"1727\" class=\"submit\"", "id=\"9999\" class=\"submit\"")
        assertFailsWith<WpBookingCrawlException> { crawler.parse(deep, LocalDate.of(2026, 10, 23), mixed) }
    }

    @Test
    fun `모르는 회차 클래스는 끊는다`() {
        val odd = fixture("wpbooking-deepthinker-2026-10-23.html").replaceFirst("class=\"submit\"", "class=\"pending\"")
        assertFailsWith<WpBookingCrawlException> { crawler.parse(deep, LocalDate.of(2026, 10, 23), odd) }
    }

    @Test
    fun `매장마다 픽스처 테스트가 있다`() {
        assertEquals(setOf("deepthinker.co.kr", "showroom404.com"), WpBookingSites.ALL.map { it.host }.toSet())
    }
}
