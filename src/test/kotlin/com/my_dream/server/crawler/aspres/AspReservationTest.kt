package com.my_dream.server.crawler.aspres

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ASP 예약 솔루션 응답을 매장마다 실제 응답(cp949 원본 바이트)으로 고정한다 (아키텍처 D5 · D41).
 *
 * | 픽스처 (2026-10-21 받음) | 테마 | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 코드케이 S1 10-21 | 15 | 150 · 87 | 지점 한 페이지 · 전 회차 매진 테마(테러리스트)도 번호를 얻는다 |
 * | 코드케이 S1 11-21 | — | — | 페이지가 밝힌 창(+30) 밖 — "전 회차 불가" 로 오는 날을 저장하지 않는다 |
 * | 코드케이 S2 | — | — | 다른 지점이 답하면 끊는다 |
 * | 싸인 S6 A·B 10-21 | 1씩 | 7 · 4 / 7 · 3 | 테마마다 요청 · 선택된 탭 대조 |
 * | 싸인 S6 A 10-28 | — | — | 범위 밖인데 `timeOn` — 클래스로 판정했다면 전부 가능이었다 |
 * | 싸인 기본값(S2) | — | — | R_JIJEM 을 빼면 수원이 답한다 |
 */
class AspReservationTest {

    private val crawler = AspCrawler(HostRateLimiter(0))
    private val codek = AspSites.CODEK_GANGNAM
    private val sign = AspSites.SIGN_GANGNAM_CITY
    private val today = LocalDate.of(2026, 10, 21)

    private fun fixture(name: String) =
        String(requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.readBytes(), Charset.forName("MS949"))

    @Test
    fun `코드케이 — 지점 한 페이지에 15테마 · 가능은 링크로 본다`() {
        val day = crawler.parse(codek, today, null, fixture("aspres-codek-s1-2026-10-21.html"), today)

        assertEquals(15, day.themes.size)
        assertEquals(150 to 87, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })

        val thieves = day.themes.first()
        assertEquals("도둑들", thieves.themeName, "이름 끝 공백을 다듬는다")
        assertEquals("codek_s1", thieves.externalId)
        assertEquals(listOf(LocalTime.of(12, 40), LocalTime.of(18, 0), LocalTime.of(20, 40)), thieves.slots.filter { it.available }.map { it.time })
    }

    @Test
    fun `코드케이 — 전 회차 매진 테마도 주석에 남은 번호로 잇는다`() {
        // 테러리스트는 10-21 에 10회차 전부 불가라 room_code 링크가 하나도 없다
        val terror = crawler.parse(codek, today, null, fixture("aspres-codek-s1-2026-10-21.html"), today)
            .themes.first { it.themeName == "테러리스트" }
        assertTrue(terror.slots.none { it.available })
        assertTrue(terror.externalId!!.startsWith("codek_s"), "번호를 못 얻어 이름이 키가 됐다")
        // 15개 모두 번호가 있고 서로 다르다
        val ids = crawler.parse(codek, today, null, fixture("aspres-codek-s1-2026-10-21.html"), today).themes.map { it.externalId }
        assertEquals(15, ids.filterNotNull().toSet().size, "번호: $ids")
    }

    @Test
    fun `코드케이 — 페이지가 밝힌 창 밖은 받지 않는다`() {
        // 11-21 은 +31 이다. 매장은 "전 회차 불가" 를 준다 — 저장하면 다음 날 자정에 150회차가 한꺼번에 풀린다
        assertFailsWith<AspCrawlException> {
            crawler.parse(codek, LocalDate.of(2026, 11, 21), null, fixture("aspres-codek-s1-2026-11-21-범위밖.html"), today)
        }
    }

    @Test
    fun `코드케이 — 다른 지점이 답하면 끊는다`() {
        assertFailsWith<AspCrawlException> {
            crawler.parse(codek, LocalDate.of(2026, 10, 22), null, fixture("aspres-codek-s2-2026-10-22-테마없음.html"), today)
        }
    }

    @Test
    fun `싸인 — 테마마다 요청하고 선택된 탭을 대조한다`() {
        val a = crawler.parse(sign, today, "A", fixture("aspres-sign-s6-A-2026-10-21.html"), today)
        assertEquals("러너웨이", a.themes.single().themeName)
        assertEquals("A", a.themes.single().externalId)
        assertEquals(7 to 4, a.themes.single().slots.let { it.size to it.count { s -> s.available } })

        val b = crawler.parse(sign, today, "B", fixture("aspres-sign-s6-B-2026-10-21.html"), today)
        assertEquals(7 to 3, b.themes.single().slots.let { it.size to it.count { s -> s.available } })

        // A 를 물었는데 B 가 왔다 — 빈 R_THEMA 가 첫 테마로 조용히 바뀌는 것과 같은 모양이다
        assertFailsWith<AspCrawlException> { crawler.parse(sign, today, "A", fixture("aspres-sign-s6-B-2026-10-21.html"), today) }
    }

    @Test
    fun `싸인 — 범위 밖 timeOn 은 받지 않는다`() {
        // 10-28 은 +7 이고 매장 창은 +6 이다. 7회차가 전부 timeOn 인데 링크가 없다
        assertFailsWith<AspCrawlException> {
            crawler.parse(sign, LocalDate.of(2026, 10, 28), "A", fixture("aspres-sign-s6-A-2026-10-28-범위밖.html"), today)
        }
    }

    @Test
    fun `싸인 — 지점을 빼면 수원이 답한다 — 끊는다`() {
        assertFailsWith<AspCrawlException> {
            crawler.parse(sign, today, "A", fixture("aspres-sign-기본값-S2-2026-10-21.html"), today)
        }
    }

    @Test
    fun `싸인 — 범위는 사이트가 밝힌 대로다`() {
        val a = crawler.parse(sign, today, "A", fixture("aspres-sign-s6-A-2026-10-21.html"), today)
        assertEquals(7, a.reservationRangeDays, "최대일수 6 = 오늘~+6 = 7일")
        assertEquals(sign.openDays, a.reservationRangeDays, "다르면 매 요청마다 '범위가 바뀌었다' 경고가 뜬다")
        val c = crawler.parse(codek, today, null, fixture("aspres-codek-s1-2026-10-21.html"), today)
        assertEquals(codek.openDays, c.reservationRangeDays)
    }

    @Test
    fun `매장마다 픽스처 테스트가 있다`() {
        assertEquals(setOf("code-k.co.kr", "www.signescape.com"), AspSites.ALL.map { it.host }.toSet(), "픽스처 없이 더한 매장이 있다")
    }
}
