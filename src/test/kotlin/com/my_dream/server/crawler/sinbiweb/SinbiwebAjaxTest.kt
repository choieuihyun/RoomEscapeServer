package com.my_dream.server.crawler.sinbiweb

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 신비웹 AJAX 세대(프랭크의 골동품가게)를 실제 응답으로 고정한다 (아키텍처 D5 · D41).
 *
 * | 픽스처 (2026-10-21 받음) | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|
 * | 달력 10월 | 열린 날 10-21~10-28 | 달력이 연 날만 묻는다 |
 * | t5 · t6 10-21 | 8 · 6 / 8 · 5 | `12시 10분` · 가능은 함수 링크 · 지난 회차는 목록에서 빠진다 |
 * | t5 10-28 | — | "예약오픈시간 23:30" — 보류라 실패로 둔다 |
 * | t5 10-29 | 9 · **9** | **범위 밖인데 전부 링크** — 달력이 거르지 않으면 가짜 빈자리가 나간다 |
 */
class SinbiwebAjaxTest {

    private val site = SinbiwebAjaxSites.THE_FRANK

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    /** 달력·회차를 픽스처로 돌려주는 가짜. **보낸 요청을 센다** — 캐시가 바퀴마다 새는지 본다 */
    private inner class Scripted : SinbiwebAjaxClient(HostRateLimiter(0)) {
        val sent = mutableListOf<Map<String, String>>()
        override fun post(site: SinbiwebAjaxSite, form: Map<String, String>): String {
            sent += form
            return when (form["act"]) {
                "calendar" -> fixture("sinbiweb-ajax-thefrank-calendar-2026-10.html")
                else -> fixture("sinbiweb-ajax-thefrank-t5-2026-10-21.html")
            }
        }
    }

    private val crawler = SinbiwebAjaxCrawler(Scripted())

    @Test
    fun `회차 — 한글 시각을 읽고 가능은 함수 링크로 본다`() {
        val day = crawler.parseTime(site, "5", LocalDate.of(2026, 10, 21), fixture("sinbiweb-ajax-thefrank-t5-2026-10-21.html"))
        val t = day.themes.single()
        assertEquals("My Private Heaven", t.themeName)
        assertEquals("5", t.externalId)
        assertEquals(8 to 6, t.slots.size to t.slots.count { it.available })
        assertEquals(LocalTime.of(12, 0), t.slots.first().time)
        assertTrue(t.slots.first { it.time == LocalTime.of(16, 30) }.available.not(), "a.none 은 불가다")

        val six = crawler.parseTime(site, "6", LocalDate.of(2026, 10, 21), fixture("sinbiweb-ajax-thefrank-t6-2026-10-21.html"))
        assertEquals(8 to 5, six.themes.single().slots.let { it.size to it.count { s -> s.available } })
    }

    @Test
    fun `예약오픈시간 은 보류라 실패로 둔다`() {
        assertFailsWith<SinbiwebCrawlException> {
            crawler.parseTime(site, "5", LocalDate.of(2026, 10, 28), fixture("sinbiweb-ajax-thefrank-t5-2026-10-28-오픈전.html"))
        }
    }

    @Test
    fun `회차 응답만 보면 범위 밖도 전부 가능이다 — 그래서 달력이 거른다`() {
        // 이 장 자체는 멀쩡해 보인다. 파서는 이걸 못 거른다 — 거르는 건 아래 달력 테스트다
        val outside = crawler.parseTime(site, "5", LocalDate.of(2026, 10, 29), fixture("sinbiweb-ajax-thefrank-t5-2026-10-29-범위밖전부링크.html"))
        assertTrue(outside.themes.single().slots.all { it.available })

        val calendar = SinbiwebAjaxCalendar(Scripted())
        val adapter = SinbiwebAjaxAdapter(site, calendar, crawler)
        val today = LocalDate.of(2026, 10, 21)
        calendar.clock = Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"), ZoneId.of("Asia/Seoul"))

        val dates = (0L..14L).map { today.plusDays(it) }
        val asked = adapter.plan(dates).map { it.date }.toSet()

        assertTrue(LocalDate.of(2026, 10, 29) !in asked, "달력이 안 연 날을 물었다")
        assertTrue(LocalDate.of(2026, 10, 28) !in asked, "+7 은 23:30 전엔 거절된다 — openDays 7 로 뺀다")
        assertEquals((0L..6L).map { today.plusDays(it) }.toSet(), asked)
    }

    @Test
    fun `달력은 30분 동안 다시 묻지 않는다`() {
        val client = Scripted()
        val calendar = SinbiwebAjaxCalendar(client)
        val start = Instant.parse("2026-10-21T02:00:00Z")
        calendar.clock = Clock.fixed(start, ZoneId.of("Asia/Seoul"))
        calendar.openDates(site, LocalDate.of(2026, 10, 21))
        calendar.openDates(site, LocalDate.of(2026, 10, 21))
        assertEquals(1, client.sent.size, "바퀴마다 달력을 물었다")

        calendar.clock = Clock.fixed(start.plusSeconds(31 * 60), ZoneId.of("Asia/Seoul"))
        calendar.openDates(site, LocalDate.of(2026, 10, 21))
        assertEquals(2, client.sent.size)
    }

    @Test
    fun `창이 다음 달로 넘어가면 다음 달 달력도 묻는다`() {
        val client = Scripted()
        val calendar = SinbiwebAjaxCalendar(client)
        calendar.clock = Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneId.of("Asia/Seoul"))
        calendar.openDates(site, LocalDate.of(2026, 10, 28))

        assertEquals(2, client.sent.size)
        assertEquals(mapOf("act" to "calendar", "rev_days" to "2026-10-28", "year" to "2026", "month" to "11"), client.sent[1])
    }

    @Test
    fun `달력을 못 받으면 그 바퀴는 건너뛴다`() {
        val broken = object : SinbiwebAjaxClient(HostRateLimiter(0)) {
            override fun post(site: SinbiwebAjaxSite, form: Map<String, String>): String = throw IllegalStateException("타임아웃")
        }
        val adapter = SinbiwebAjaxAdapter(site, SinbiwebAjaxCalendar(broken), SinbiwebAjaxCrawler(broken))
        assertEquals(emptyList(), adapter.plan(listOf(LocalDate.now())), "창을 추측해서 물었다")
    }

    @Test
    fun `매장마다 픽스처 테스트가 있다`() {
        assertEquals(setOf("thefrank.co.kr"), SinbiwebAjaxSites.ALL.map { it.host }.toSet())
    }
}
