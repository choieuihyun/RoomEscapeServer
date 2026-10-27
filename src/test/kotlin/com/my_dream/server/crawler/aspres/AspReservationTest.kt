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
 * | **홍대 (2026-10-22 받음)** | | | |
 * | 코드케이 S3 10-22 | 2 | 15 · 2 | 테마 머리 클래스가 `thema3` — 지점마다 숫자가 다르다 |
 * | 싸인 S5 A · S7 A 10-22 | 1씩 | 7 · 5 / 7 · 6 | 최대일수 7 지점 (강남시티는 6) |
 * | 싸인 S5 A 10-30 | — | — | +8 은 링크 없는 timeOn — 받지 않는다 |
 * | **서울 (2026-10-27 받음)** | | | |
 * | 이스케이프시티 S1 10-29 · 10-31(토) | 6 | 42 · 41 / 54 · 52 | 테마 머리가 늘 `thema1` — 지점은 켜진 탭으로 본다 · `S P Y` 공백이 요일마다 다르다 |
 * | 이스케이프시티 s2 · S4 10-29 | 5 · 3 | 35 · 35 / 22 · 19 | 소문자 `s2` · 최대일수 14 지점 |
 * | 이스케이프시티 S2(대문자) · S99 · S1 11-28 | — | — | 탭이 안 켜진 응답 · 없는 지점 · 창(+31) 밖 전부 `timeOff` |
 *
 * 이스케이프시티 픽스처는 푸터 전화 · 계좌 · 대표자 · 메일 · 사업자번호를 지웠다.
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
    fun `코드케이 홍대 — 테마 머리 숫자가 달라도 읽는다`() {
        val today = LocalDate.of(2026, 10, 22)
        val day = crawler.parse(AspSites.CODEK_HONGDAE, today, null, fixture("aspres-codek-s3-2026-10-22.html"), today)
        assertEquals(listOf("꼬레아 우라 A", "꼬레아 우라 B"), day.themes.map { it.themeName })
        assertEquals(listOf("codek_s16", "codek_s17"), day.themes.map { it.externalId })
        assertEquals(15 to 2, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        assertEquals(AspSites.CODEK_HONGDAE.openDays, day.reservationRangeDays)
        // 강남 설정으로 홍대 응답을 읽으면 지점이 달라 끊는다
        assertFailsWith<AspCrawlException> { crawler.parse(codek, today, null, fixture("aspres-codek-s3-2026-10-22.html"), today) }
    }

    @Test
    fun `코드케이 — hidden 지점값이 맞아도 그린 지점이 다르면 끊는다`() {
        // hidden R_JIJEM 은 보낸 값을 돌려줄 뿐이다(없는 지점 S4 도 S4). R_JIJEM 이 무시되고 기본 지점(S1)이 답했는데
        // hidden 만 S3 으로 왔다고 치자 — 테마 머리 숫자(thema1)로 잡는다. 안 잡으면 강남 15테마가 홍대 키로 저장된다
        val today = LocalDate.of(2026, 10, 21)
        val gangnamWithEcho = fixture("aspres-codek-s1-2026-10-21.html").replace("R_JIJEM\" value=\"S1\"", "R_JIJEM\" value=\"S3\"")
        assertTrue("R_JIJEM\" value=\"S3\"" in gangnamWithEcho, "픽스처 모양이 바뀌었다 — 이 테스트가 아무것도 안 본다")
        val e = assertFailsWith<AspCrawlException> { crawler.parse(AspSites.CODEK_HONGDAE, today, null, gangnamWithEcho, today) }
        assertTrue("그린 지점" in e.message!!, "다른 검사에 걸렸다 — 이 테스트가 보려는 건 테마 머리 대조다: ${e.message}")

        val hongdaeWithEcho = fixture("aspres-codek-s3-2026-10-22.html").replace("R_JIJEM\" value=\"S3\"", "R_JIJEM\" value=\"S1\"")
        assertFailsWith<AspCrawlException> {
            crawler.parse(codek, LocalDate.of(2026, 10, 22), null, hongdaeWithEcho, LocalDate.of(2026, 10, 22))
        }
    }

    @Test
    fun `싸인 홍대 · 상수 — 지점을 되읽고 창은 오늘~+7 이다`() {
        val today = LocalDate.of(2026, 10, 22)
        val s5 = crawler.parse(AspSites.SIGN_HONGDAE, today, "A", fixture("aspres-sign-s5-A-2026-10-22.html"), today)
        assertEquals("거상", s5.themes.single().themeName)
        assertEquals(7 to 5, s5.themes.single().slots.let { it.size to it.count { s -> s.available } })
        assertEquals(8, s5.reservationRangeDays, "최대일수 7 = 오늘~+7 = 8일")
        assertEquals(AspSites.SIGN_HONGDAE.openDays, s5.reservationRangeDays)

        val s7 = crawler.parse(AspSites.SIGN_SANGSU, today, "A", fixture("aspres-sign-s7-A-2026-10-22.html"), today)
        assertEquals("원티드", s7.themes.single().themeName)
        assertEquals(7 to 6, s7.themes.single().slots.let { it.size to it.count { s -> s.available } })

        // 같은 건물 두 지점이 서로 덮지 않는다 — 상수 응답을 홍대점으로 읽으면 끊는다
        assertFailsWith<AspCrawlException> { crawler.parse(AspSites.SIGN_HONGDAE, today, "A", fixture("aspres-sign-s7-A-2026-10-22.html"), today) }
        // +8 은 링크 없는 timeOn
        assertFailsWith<AspCrawlException> {
            crawler.parse(AspSites.SIGN_HONGDAE, LocalDate.of(2026, 10, 30), "A", fixture("aspres-sign-s5-A-2026-10-30-범위밖.html"), today)
        }
    }

    private val escapeToday = LocalDate.of(2026, 10, 27)
    private val yeongdeungpo = AspSites.ESCAPECITY_YEONGDEUNGPO

    @Test
    fun `이스케이프시티 영등포 — 테마 머리 숫자가 아니라 켜진 지점 탭으로 지점을 본다`() {
        val day = crawler.parse(yeongdeungpo, LocalDate.of(2026, 10, 29), null, fixture("aspres-escapecity-S1-2026-10-29.html"), escapeToday)
        assertEquals(listOf("좀비바이러스", "마법학교", "기억의 조각", "S P Y", "카지노의 전설", "다시만날수있을까?"), day.themes.map { it.themeName })
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), day.themes.map { it.externalId })
        assertEquals(42 to 41, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        assertEquals(32, day.reservationRangeDays, "최대일수 31 = 오늘~+31 = 32일")
        assertEquals(yeongdeungpo.openDays, day.reservationRangeDays)
    }

    @Test
    fun `이스케이프시티 — 주말은 공백 칸 수가 달라도 같은 이름이다`() {
        val sat = crawler.parse(yeongdeungpo, LocalDate.of(2026, 10, 31), null, fixture("aspres-escapecity-S1-2026-10-31-주말.html"), escapeToday)
        assertTrue("S    P    Y" in fixture("aspres-escapecity-S1-2026-10-31-주말.html"), "픽스처 모양이 바뀌었다 — 이 테스트가 아무것도 안 본다")
        assertEquals("S P Y", sat.themes.first { it.externalId == "D" }.themeName)
        assertEquals(54 to 52, sat.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
    }

    @Test
    fun `이스케이프시티 성신여대 · 신촌 — 소문자 s2 · 최대일수 14`() {
        val s2 = crawler.parse(AspSites.ESCAPECITY_SUNGSHIN, LocalDate.of(2026, 10, 29), null, fixture("aspres-escapecity-s2-2026-10-29.html"), escapeToday)
        assertEquals(listOf("알고보면", "그녀를 찾아서", "킬러VS", "VS경찰", "시험지 유출사건"), s2.themes.map { it.themeName })
        assertEquals(35 to 35, s2.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })

        val s4 = crawler.parse(AspSites.ESCAPECITY_SINCHON, LocalDate.of(2026, 10, 29), null, fixture("aspres-escapecity-S4-2026-10-29.html"), escapeToday)
        assertEquals(listOf("연(80분)", "신의실수(80분)", "컨티뉴얼(70분)"), s4.themes.map { it.themeName })
        assertEquals(22 to 19, s4.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        assertEquals(15, s4.reservationRangeDays, "최대일수 14 = 오늘~+14 = 15일")
        assertEquals(AspSites.ESCAPECITY_SINCHON.openDays, s4.reservationRangeDays)
    }

    @Test
    fun `이스케이프시티 — 다른 지점 · 대문자 S2 · 없는 지점 · 창 밖은 끊는다`() {
        val d = LocalDate.of(2026, 10, 29)
        // 셋 다 테마 머리가 thema1 이다 — 숫자로 봤다면 영등포 응답이 성신여대 키로 저장됐다
        assertFailsWith<AspCrawlException> { crawler.parse(AspSites.ESCAPECITY_SUNGSHIN, d, null, fixture("aspres-escapecity-S1-2026-10-29.html"), escapeToday) }
        assertFailsWith<AspCrawlException> { crawler.parse(yeongdeungpo, d, null, fixture("aspres-escapecity-S4-2026-10-29.html"), escapeToday) }
        // 대문자 S2 는 회차가 똑같이 오는데 탭이 안 켜진다 — 설정을 사이트 표기(s2)로 둔 이유
        val upper = assertFailsWith<AspCrawlException> {
            crawler.parse(AspSites.ESCAPECITY_SUNGSHIN.copy(jijem = "S2"), d, null, fixture("aspres-escapecity-S2대문자-탭꺼짐-2026-10-29.html"), escapeToday)
        }
        assertTrue("탭" in upper.message!!, "다른 검사에 걸렸다: ${upper.message}")
        // 없는 지점 — 테마 0 · 최대일수 빈 값
        assertFailsWith<AspCrawlException> { crawler.parse(yeongdeungpo, d, null, fixture("aspres-escapecity-S99-없는지점.html"), escapeToday) }
        // +32 는 창 밖 — 200 에 전 회차 timeOff 로 온다
        assertFailsWith<AspCrawlException> {
            crawler.parse(yeongdeungpo, LocalDate.of(2026, 11, 28), null, fixture("aspres-escapecity-S1-2026-11-28-창밖.html"), escapeToday)
        }
    }

    @Test
    fun `이스케이프시티 — 전 회차 매진 테마도 설정 표로 번호를 잇는다`() {
        // 좀비바이러스(A)의 예약 링크를 전부 걷어 낸다 = 그날 매진. room_code 를 얻을 자리가 사라진다
        val html = fixture("aspres-escapecity-S1-2026-10-29.html")
        val soldOut = html.replace(Regex("""<a href="sub03_2\.html\?[^"]*room_code=A&[^"]*">"""), "")
        assertTrue(soldOut != html && "room_code=A&" !in soldOut, "픽스처 모양이 바뀌었다 — 이 테스트가 아무것도 안 본다")
        val zombie = crawler.parse(yeongdeungpo, LocalDate.of(2026, 10, 29), null, soldOut, escapeToday).themes.first()
        assertTrue(zombie.slots.isNotEmpty() && zombie.slots.none { it.available })
        assertEquals("A", zombie.externalId, "번호를 못 얻으면 이름이 키가 되어 같은 테마가 다른 행으로 갈린다")
    }

    @Test
    fun `이스케이프시티 — 설정 표가 링크의 room_code 와 맞다`() {
        listOf(
            yeongdeungpo to "aspres-escapecity-S1-2026-10-29.html",
            AspSites.ESCAPECITY_SUNGSHIN to "aspres-escapecity-s2-2026-10-29.html",
            AspSites.ESCAPECITY_SINCHON to "aspres-escapecity-S4-2026-10-29.html",
        ).forEach { (site, name) ->
            val themes = crawler.parse(site, LocalDate.of(2026, 10, 29), null, fixture(name), escapeToday).themes
            assertEquals(site.themeCodes, themes.associate { it.themeName to it.externalId }, site.branchName)
        }
    }

    @Test
    fun `지점 키가 겹치지 않는다`() {
        val keys = AspSites.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "겹치는 지점 키: $keys")
    }

    @Test
    fun `매장마다 픽스처 테스트가 있다`() {
        assertEquals(setOf("code-k.co.kr", "www.signescape.com", "escapecity.kr"), AspSites.ALL.map { it.host }.toSet(), "픽스처 없이 더한 매장이 있다")
    }
}
