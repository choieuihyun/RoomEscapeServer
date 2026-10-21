package com.my_dream.server.crawler.smurf

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 스머프 매직 포레스트 응답을 실제 응답으로 고정한다 (조사 문서 "스머프").
 *
 * | 픽스처 (2026-10-21 받음) | 관 | 회차 · 가능 (합친 뒤) | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 강남 10-23 | 4 | 17 · 10 | **같은 시각 두 관을 합친다** — 하나라도 비면 가능 |
 * | 강남 10-21 | — | — | 휴무 문구 = 회차 0개인 정상 하루 |
 * | 홍대 10-23 | 5 | — | 관 수가 다르면 다른 지점 응답이다 — 끊는다 |
 * | 강남 날짜 목록 | — | 10-21~11-09 | 이 목록에 없는 날은 묻지 않는다 (`time` 은 창 밖에도 회차를 준다) |
 */
class SmurfMagicTest {

    private val crawler = SmurfCrawler(SmurfClient(HostRateLimiter(0)))
    private val site = SmurfSites.GANGNAM

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private val friday = LocalDate.of(2026, 10, 23)

    @Test
    fun `같은 시각 관들을 합친다 — 하나라도 비면 가능`() {
        val day = crawler.parse(site, friday, fixture("smurf-time-z6-2026-10-23.html"))

        val theme = day.themes.single()
        assertEquals("파파스머프의 마법실험실", theme.themeName)
        assertEquals("1", theme.externalId)
        // 1·3관 9칸 + 2·4관 8칸 → 시각 17개. 관 그대로였다면 34칸이 17자리를 두고 서로 덮어썼다
        assertEquals(17 to 10, theme.slots.size to theme.slots.count { it.available })
        assertEquals(theme.slots.map { it.time }.distinct(), theme.slots.map { it.time }, "시각이 겹치면 안 된다")
        assertEquals(theme.slots.sortedBy { it.time }, theme.slots, "시각 순")

        val at = theme.slots.associate { it.time to it.available }
        // 13:30 은 2관이 불가 · 4관이 가능이다 — 순서대로 덮어썼다면 마지막 관(4관)에 따라 맞았겠지만 15:00(2관만 가능)은 틀렸다
        assertTrue(at.getValue(LocalTime.of(13, 30)), "4관만 비었다")
        assertTrue(at.getValue(LocalTime.of(15, 0)), "2관만 비었다 — 덮어쓰기였다면 4관(불가)이 이겼다")
        assertEquals(false, at.getValue(LocalTime.of(10, 0)), "1·3관 둘 다 불가")
    }

    @Test
    fun `휴무 문구는 회차 0개인 정상 하루다`() {
        val day = crawler.parse(site, LocalDate.of(2026, 10, 21), fixture("smurf-time-z6-2026-10-21-휴무.html"))
        assertEquals(1, day.themes.size, "테마는 남긴다 — 0개면 '파서가 깨졌나' 경고가 매주 뜬다")
        assertTrue(day.themes.single().slots.isEmpty())
    }

    @Test
    fun `휴무가 아닌 안내문은 모르는 것이라 끊는다`() {
        assertFailsWith<SmurfCrawlException> {
            crawler.parse(site, friday, """<div class="time_msg">예약이 마감되었습니다</div>""")
        }
    }

    @Test
    fun `관 수가 다르면 다른 지점 응답이다`() {
        // 홍대는 5관이다. zizum_num 이 무시되면 이런 모양이 강남 이름으로 저장된다
        assertFailsWith<SmurfCrawlException> {
            crawler.parse(site, friday, fixture("smurf-time-z17-2026-10-23-홍대.html"))
        }
    }

    @Test
    fun `응답 날짜가 다르면 끊는다`() {
        assertFailsWith<SmurfCrawlException> {
            crawler.parse(site, friday.plusDays(1), fixture("smurf-time-z6-2026-10-23.html"))
        }
    }

    @Test
    fun `관도 안내문도 없으면 끊는다`() {
        assertFailsWith<SmurfCrawlException> { crawler.parse(site, friday, "<html>점검 중</html>") }
    }

    @Test
    fun `날짜 목록을 읽는다`() {
        val days = SmurfCalendar.parseDays(fixture("smurf-days-z6-2026-10-21.html"))
        assertEquals(20, days.size)
        assertEquals(LocalDate.of(2026, 10, 21), days.min())
        assertEquals(LocalDate.of(2026, 11, 9), days.max())
    }

    @Test
    fun `매장마다 픽스처 테스트가 있다`() {
        assertEquals(setOf("smurfmagic.com:6"), SmurfSites.ALL.map { "${it.host}:${it.zizum}" }.toSet(), "픽스처 없이 더한 지점이 있다")
    }
}
