package com.my_dream.server.crawler.murderparker

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 머더파커 홍대 두 지점 응답을 실제 응답(cp949 원본 바이트)으로 고정한다 (조사 문서 "2026-10-22 홍대 조사").
 * 푸터의 휴대폰 번호는 픽스처에서 `010-0000-0000` 으로 바꿨다.
 *
 * | 픽스처 (2026-10-22 받음) | 테마 | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 홍대 1호점(S9) 10-23 · 화면과 같은 POST | 5 | 43 · 28 | 가능 = 예약 링크(지점·날짜·테마·시각이 맞을 때) · `<a … />` 비표준 |
 * | WOW 홍대점(S13) 10-23 | 3 | 27 · 26 | 이름 끝 "60분" 을 소요시간으로 옮기고 지점 꼬리표를 뗀다 |
 * | 없는 지점(S99) | 0 | — | hidden JIJEM 은 보낸 값을 돌려준다 — 테마 목록이 비어 끊긴다 |
 * | S9 2027-10-22 (1년 뒤) | 5 | 전부 가능 | 매장이 정말 다 연다 — 사용자가 사이트에서 확인. 묻는 범위는 우리가 정한다(+14) |
 * | **건대 (2026-10-22 받음)** | | | |
 * | 건대 1호점(S6) 10-24 | 6 | 57 · 52 | 꼬리표가 지점 이름과 다르다(`_건대점`) · 괄호 안 소요시간(`파커4(80분 Premium)`) |
 * | 건대 2호점(S8) 10-24 | 3 | 26 · 17 | 공백이 든 꼬리표(`_건대 2호점`) |
 */
class MurderParkerTest {

    private val crawler = MurderParkerCrawler(HostRateLimiter(0))
    private val hongdae1 = MurderParker.BRANCHES.first { it.jijem == "S9" }
    private val wow = MurderParker.BRANCHES.first { it.jijem == "S13" }
    private val fri = LocalDate.of(2026, 10, 23)

    private fun fixture(name: String) =
        String(requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.readBytes(), Charset.forName("MS949"))

    @Test
    fun `홍대 1호점 — 다섯 테마 · 가능은 예약 링크`() {
        val day = crawler.parse(hongdae1, fri, fixture("murderparker-s9-2026-10-23.html"))
        assertEquals(listOf("구둣방손님", "밴드의 탄생", "주마등", "칠칠77(70분 프리미엄)", "엠M(70분 혼방)"), day.themes.map { it.themeName })
        assertEquals(listOf("A", "B", "C", "D", "E"), day.themes.map { it.externalId })
        assertEquals(43 to 28, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })

        val shoe = day.themes.first()
        assertEquals(false, shoe.slots.first { it.time == LocalTime.of(11, 15) }.available, "href=\"#\" 은 불가")
        assertTrue(shoe.slots.first { it.time == LocalTime.of(15, 0) }.available)
        assertEquals("2~6명", shoe.capacity)
        assertTrue(shoe.posterUrl!!.startsWith("http://murderparker.com/upload_file/room/"))
    }

    @Test
    fun `WOW 홍대점 — 이름 끝 분을 소요시간으로 옮긴다`() {
        val day = crawler.parse(wow, fri, fixture("murderparker-s13-2026-10-23.html"))
        assertEquals(listOf("홈그라운드(Home Ground)", "선택받은 자", "하(HA)"), day.themes.map { it.themeName })
        assertTrue(day.themes.all { it.runningMinutes == 60 })
        assertEquals(27 to 26, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
    }

    @Test
    fun `다른 지점 응답이면 끊는다 — hidden 은 보낸 값을 돌려줄 뿐이다`() {
        // 두 지점이 한 호스트다. JIJEM 이 무시되는 날 서로 덮지 않게 테마 목록의 지점 번호 · 꼬리표로 본다
        assertFailsWith<MurderParkerCrawlException> { crawler.parse(wow, fri, fixture("murderparker-s9-2026-10-23.html")) }
        assertFailsWith<MurderParkerCrawlException> { crawler.parse(hongdae1, fri, fixture("murderparker-s13-2026-10-23.html")) }
        // 없는 지점: 200 · 테마 0개
        assertFailsWith<MurderParkerCrawlException> { crawler.parse(hongdae1, fri, fixture("murderparker-s99-2026-10-23-없는지점.html")) }
    }

    @Test
    fun `건대 두 지점 — 꼬리표가 지점 이름과 달라도 읽고, 괄호 안 소요시간을 읽는다`() {
        val sat = LocalDate.of(2026, 10, 24)
        val one = MurderParker.BRANCHES.first { it.jijem == "S6" }
        val two = MurderParker.BRANCHES.first { it.jijem == "S8" }

        val d1 = crawler.parse(one, sat, fixture("murderparker-s6-2026-10-24.html"))
        assertEquals(listOf("음대출신 김수학", "파커4(80분 Premium)", "우리 꼰대 진분홍", "여행(70분 Premium)", "꽁노리", "어은꿈이진루다"), d1.themes.map { it.themeName })
        assertEquals(57 to 52, d1.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        assertEquals(80, d1.themes.first { it.externalId == "B" }.runningMinutes, "괄호 안 80분")
        assertEquals(null, d1.themes.first { it.externalId == "A" }.runningMinutes)

        val d2 = crawler.parse(two, sat, fixture("murderparker-s8-2026-10-24.html"))
        assertEquals(listOf("그림가문", "특수사건대응반", "미로(me路)"), d2.themes.map { it.themeName })
        assertEquals(26 to 17, d2.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })

        // 건대 두 지점이 서로 덮지 않는다
        assertFailsWith<MurderParkerCrawlException> { crawler.parse(two, sat, fixture("murderparker-s6-2026-10-24.html")) }
        assertFailsWith<MurderParkerCrawlException> { crawler.parse(one, sat, fixture("murderparker-s8-2026-10-24.html")) }
    }

    @Test
    fun `다른 날짜가 렌더되면 끊는다`() {
        assertFailsWith<MurderParkerCrawlException> { crawler.parse(hongdae1, fri.plusDays(1), fixture("murderparker-s9-2026-10-23.html")) }
    }

    @Test
    fun `예약 링크의 테마 코드가 블록과 다르면 끊는다`() {
        val swapped = fixture("murderparker-s9-2026-10-23.html").replaceFirst("ROOM_CODE=A&", "ROOM_CODE=B&")
        assertFailsWith<MurderParkerCrawlException> { crawler.parse(hongdae1, fri, swapped) }
    }

    @Test
    fun `1년 뒤도 전부 열려 있다 — 매장이 정말 다 받는다`() {
        val day = crawler.parse(hongdae1, LocalDate.of(2027, 10, 22), fixture("murderparker-s9-2027-10-22-1년뒤.html"))
        assertTrue(day.themes.flatMap { it.slots }.all { it.available })
        // 그래도 우리가 묻는 건 늘 보는 범위까지다
        val units = MurderParkerAdapter(crawler).plan((0L..30L).map { LocalDate.now().plusDays(it) })
        assertTrue(units.all { !it.date.isAfter(LocalDate.now().plusDays(MurderParker.OPEN_DAYS - 1L)) })
    }
}
