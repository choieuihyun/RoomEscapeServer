package com.my_dream.server.crawler.escapers

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 이스케이퍼스 응답을 실제 응답으로 고정한다 (조사 문서 "2026-10-22 홍대 조사").
 * 지점 페이지의 계좌 · 휴대폰(`010-0000-0000`) · 메일은 픽스처에서 지웠다.
 *
 * | 픽스처 (2026-10-22 받음) | 테마 | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 1호점 페이지 | — | — | 숨은 막음 정보 — 10-22 를 1호점 평일 테마 다섯이 통째로 닫았다(`today`) · RS의 밀실(6) 10-24 17:40 을 막았다(`other`) |
 * | 1호점 10-22 | 5 | 36 · 0 | **회차 응답은 전부 `reservation:false` 인데 매장이 그날을 닫았다** — 응답만 보면 전부 가능이다 |
 * | 1호점 10-24 | 5 | 41 · 34 | 응답 기준 35 가능 중 17:40 하나를 `other` 가 막는다 |
 * | 2호점 10-24 | 5 | 40 · 32 | 다른 지점 테마 이름 집합 |
 * | 없는 지점(99) | 0 | — | `{"data":[],"times":[]}` — 빈 하루가 아니라 실패 |
 */
class EscapersTest {

    private val crawler = EscapersCrawler(HostRateLimiter(0))
    private val one = Escapers.BRANCHES.first { it.groupPK == 1 }
    private val two = Escapers.BRANCHES.first { it.groupPK == 2 }
    private val morning = LocalDateTime.of(2026, 10, 22, 9, 0)

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.bufferedReader().readText()

    private val page by lazy { crawler.parsePage(one, fixture("escapers-page-1-2026-10-22.html"), "XSRF-TOKEN=x; escapers_session=y") }

    @Test
    fun `지점 페이지에서 CSRF · groupPhone · 막음 정보를 읽는다`() {
        assertTrue(page.csrf.isNotBlank())
        assertEquals("010-0000-0000", page.groupPhone)
        assertEquals(setOf(1792594800L), page.blocks.today["1"], "10-22 00:00 KST")
        assertEquals(setOf(1792831200L), page.blocks.other["6"], "10-24 17:40 KST")
        assertTrue(page.blocks.after.isEmpty(), "지금은 [] 로 온다")
    }

    @Test
    fun `매장이 그날을 닫으면 응답이 전부 가능이어도 불가다 — today`() {
        val day = crawler.parse(one, LocalDate.of(2026, 10, 22), fixture("escapers-g1-2026-10-22-오늘닫음.json"), page.blocks, morning)
        assertEquals(36 to 0, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        // 막음 정보 없이 읽으면 전부 가능 — 이게 막으려는 사고다
        val naive = crawler.parse(one, LocalDate.of(2026, 10, 22), fixture("escapers-g1-2026-10-22-오늘닫음.json"), Blocks(emptyMap(), emptyMap(), emptyMap()), morning)
        assertEquals(36, naive.themes.flatMap { it.slots }.count { it.available })
    }

    @Test
    fun `매장이 막은 회차 하나 — other`() {
        val day = crawler.parse(one, LocalDate.of(2026, 10, 24), fixture("escapers-g1-2026-10-24.json"), page.blocks, morning)
        assertEquals(listOf("서커스:공중그네", "싸이코메트리", "RS의 밀실", "붉은머리연맹", "라이브").sorted(), day.themes.map { it.themeName }.sorted())
        assertEquals(41 to 34, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        val rs = day.themes.first { it.themeName == "RS의 밀실" }
        assertEquals(false, rs.slots.first { it.time == LocalTime.of(17, 40) }.available)
        assertTrue(day.themes.all { it.externalId == null }, "테마 번호는 요일마다 바뀐다 — 이름이 키다")
    }

    @Test
    fun `after 이후는 전부 막는다`() {
        val after = Blocks(emptyMap(), emptyMap(), mapOf("6" to LocalDate.of(2026, 10, 24).atTime(15, 0).atZone(Escapers.ZONE).toEpochSecond()))
        val rs = crawler.parse(one, LocalDate.of(2026, 10, 24), fixture("escapers-g1-2026-10-24.json"), after, morning)
            .themes.first { it.themeName == "RS의 밀실" }
        assertTrue(rs.slots.filter { !it.time.isBefore(LocalTime.of(15, 0)) }.none { it.available })
    }

    @Test
    fun `오늘 지난 회차는 불가`() {
        val evening = LocalDateTime.of(2026, 10, 24, 20, 0)
        val day = crawler.parse(one, LocalDate.of(2026, 10, 24), fixture("escapers-g1-2026-10-24.json"), Blocks(emptyMap(), emptyMap(), emptyMap()), evening)
        assertTrue(day.themes.flatMap { it.slots }.filter { it.time.isBefore(LocalTime.of(20, 0)) }.none { it.available })
    }

    @Test
    fun `다른 지점 응답이면 끊는다 · 없는 지점은 실패다`() {
        assertEquals(40 to 32, crawler.parse(two, LocalDate.of(2026, 10, 24), fixture("escapers-g2-2026-10-24.json"), page.blocks, morning)
            .themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        assertFailsWith<EscapersCrawlException> { crawler.parse(one, LocalDate.of(2026, 10, 24), fixture("escapers-g2-2026-10-24.json"), page.blocks, morning) }
        assertFailsWith<EscapersCrawlException> { crawler.parse(one, LocalDate.of(2026, 10, 23), fixture("escapers-g99-2026-10-23-없는지점.json"), page.blocks, morning) }
    }

    @Test
    fun `막음 정보가 없는 페이지는 받지 않는다`() {
        val stripped = fixture("escapers-page-1-2026-10-22.html").replace("reservationHiddenData", "somethingElse")
        assertFailsWith<EscapersCrawlException> { crawler.parsePage(one, stripped, "c") }
        assertFailsWith<EscapersCrawlException> { crawler.parsePage(two, fixture("escapers-page-1-2026-10-22.html"), "c") }
    }
}
