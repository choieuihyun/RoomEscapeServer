package com.my_dream.server.crawler.reserv

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Reserv Company 플랫폼 응답을 매장마다 실제 응답으로 고정한다 (아키텍처 D5 · D41).
 * 픽스처의 `account_code`(계좌번호 · 예금주 실명)는 지웠다.
 *
 * | 픽스처 (2026-10-21 받음) | 10-22 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|
 * | 룸즈에이 강남 | 61 · 54 (5테마) | 응답 하나에서 날짜를 골라 낸다 · 지점 keycode 대조 |
 * | 오늘의 한 페이지 | 25 · 2 (가짜 테마 빼고 2테마) | **늘 "가능" 인 가짜 테마를 뺀다** · 마지막 날 0개 테마 |
 * | 틀린 keycode | — | `{"ok":false,"error":{"code":"SHOP_NOT_ALLOWED"}}` 는 실패 |
 */
class ReservBookingTest {

    private val crawler = ReservCrawler(HostRateLimiter(0))

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private val roomsa = fixture("reserv-roomsa-gangnam-2026-10-21.json")
    private val pagetoday = fixture("reserv-pagetoday-2026-10-21.json")

    @Test
    fun `응답 하나에서 그 날짜 회차만 골라 낸다`() {
        val day = crawler.parse(ReservSites.ROOMSA_GANGNAM, LocalDate.of(2026, 10, 22), roomsa)

        assertEquals(5, day.themes.size)
        assertEquals(61 to 54, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        val mega = day.themes.first { it.externalId == "369" }
        assertEquals("메가 게임 (MEGA GAME)", mega.themeName)
        assertEquals(8 to 5, mega.slots.size to mega.slots.count { it.available })
        assertEquals(mega.slots.sortedBy { it.time }, mega.slots, "시각 순")
        assertEquals(8, day.reservationRangeDays, "응답 창 10-21~10-28")
    }

    @Test
    fun `가짜 안내 테마를 뺀다`() {
        val day = crawler.parse(ReservSites.PAGETODAY, LocalDate.of(2026, 10, 22), pagetoday)

        assertEquals(listOf("버디", "용하다 용해!! 용팔도령"), day.themes.map { it.themeName })
        assertTrue(day.themes.none { it.externalId == "342" }, "\"예약하지 마세요\" 테마가 들어왔다 — 늘 가능이라 가짜 빈자리가 뜬다")
        assertEquals(25 to 2, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
    }

    @Test
    fun `창 마지막 날은 진짜 테마의 회차가 0개여도 받는다`() {
        // 10-28 은 가짜 테마에만 회차가 있다 — 아직 안 연 날이다. 테마는 남기고 회차만 비운다 (D22)
        val day = crawler.parse(ReservSites.PAGETODAY, LocalDate.of(2026, 10, 28), pagetoday)
        assertEquals(2, day.themes.size)
        assertTrue(day.themes.all { it.slots.isEmpty() })
    }

    @Test
    fun `응답 창 밖 날짜는 끊는다`() {
        assertFailsWith<ReservCrawlException> { crawler.parse(ReservSites.ROOMSA_GANGNAM, LocalDate.of(2026, 10, 29), roomsa) }
    }

    @Test
    fun `다른 지점 응답이면 끊는다`() {
        assertFailsWith<ReservCrawlException> { crawler.parse(ReservSites.ROOMSA_GANGNAM, LocalDate.of(2026, 10, 22), pagetoday) }
    }

    @Test
    fun `틀린 keycode 응답은 실패다`() {
        assertFailsWith<ReservCrawlException> {
            crawler.parse(ReservSites.ROOMSA_GANGNAM, LocalDate.of(2026, 10, 22), fixture("reserv-roomsa-잘못된키.json"))
        }
    }

    @Test
    fun `회차 시각을 읽는다`() {
        val buddy = crawler.parse(ReservSites.PAGETODAY, LocalDate.of(2026, 10, 22), pagetoday).themes.first()
        assertTrue(buddy.slots.all { it.time.isAfter(LocalTime.of(8, 0)) }, "${buddy.slots}")
    }

    @Test
    fun `매장마다 픽스처 테스트가 있다`() {
        assertEquals(setOf("roomsa.co.kr", "page-today.co.kr"), ReservSites.ALL.map { it.host }.toSet())
    }
}
