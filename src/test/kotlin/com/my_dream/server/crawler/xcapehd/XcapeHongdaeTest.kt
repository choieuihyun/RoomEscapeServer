package com.my_dream.server.crawler.xcapehd

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 엑스케이프 홍대점 응답을 실제 응답으로 고정한다 (조사 문서 "2026-10-22 홍대 조사").
 *
 * | 픽스처 (2026-10-22 받음) | 테마 | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 10-24 | 6 | 64 · 58 (+ 자정 2개 뺌) | 가능 = state1 + 예약 링크 · 매진 테마도 이름으로 번호를 단다 · `24:00` 은 담지 못해 뺀다 |
 * | 11-12 (+21) | 6 | 66 · 0 | **전 회차 "예약불가" = 창 밖** — 매진으로 저장하지 않는다 |
 * | 10-21 (어제) | 6 | 66 · 0 | 전 회차 "예약완료" 는 진짜 불가로 받는다 — 같은 클래스, 다른 글자 |
 */
class XcapeHongdaeTest {

    private val crawler = XcapeHongdaeCrawler(HostRateLimiter(0))

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.bufferedReader().readText()

    private val sat = LocalDate.of(2026, 10, 24)

    @Test
    fun `테마 여섯 · 가능은 state1 의 예약 링크다`() {
        val day = crawler.parse(sat, fixture("xcapehd-2026-10-24.html"))
        assertEquals(listOf("녹", "더 다이스룸", "고요의기지", "그남자 그여자", "육식주의자", "버지니아 란제리샵"), day.themes.map { it.themeName })
        assertEquals(listOf("2", "1", "12", "15", "16", "17"), day.themes.map { it.externalId })
        // 66칸 중 24:00 두 칸(고요의기지 · 버지니아 란제리샵 · 둘 다 가능)은 뺀다 — 하루 안 시각만 담을 수 있다
        assertEquals(64 to 58, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        assertTrue(day.themes.flatMap { it.slots }.none { it.time == LocalTime.MIDNIGHT }, "자정을 그날 00:00 으로 넣었다")

        val rust = day.themes.first()
        assertTrue(rust.slots.first { it.time == LocalTime.of(10, 0) }.available)
        assertEquals(false, rust.slots.first { it.time == LocalTime.of(18, 0) }.available, "예약완료")
        assertEquals("2~7명", rust.capacity)
        assertEquals(3.0, rust.difficulty)
        assertEquals(2.5, day.themes.first { it.themeName == "고요의기지" }.difficulty)
    }

    @Test
    fun `전 회차 예약불가는 창 밖이다 — 받지 않는다`() {
        assertFailsWith<XcapeHongdaeCrawlException> { crawler.parse(LocalDate.of(2026, 11, 12), fixture("xcapehd-2026-11-12-창밖예약불가.html")) }
    }

    @Test
    fun `전 회차 예약완료는 진짜 불가로 받는다`() {
        val day = crawler.parse(LocalDate.of(2026, 10, 21), fixture("xcapehd-2026-10-21-어제.html"))
        assertEquals(6, day.themes.size)
        assertTrue(day.themes.all { t -> t.slots.isNotEmpty() && t.slots.none { it.available } })
    }

    @Test
    fun `다른 날짜가 렌더되면 끊는다`() {
        assertFailsWith<XcapeHongdaeCrawlException> { crawler.parse(sat.plusDays(1), fixture("xcapehd-2026-10-24.html")) }
    }

    @Test
    fun `링크의 테마 번호가 표와 다르면 끊는다 · 모르는 테마도 끊는다`() {
        val swapped = fixture("xcapehd-2026-10-24.html").replace("g=2&d=", "g=99&d=")
        assertFailsWith<XcapeHongdaeCrawlException> { crawler.parse(sat, swapped) }
        val renamed = fixture("xcapehd-2026-10-24.html").replace(">녹</span>", ">새 테마</span>")
        assertTrue(">새 테마</span>" in renamed, "픽스처 모양이 바뀌었다 — 이 테스트가 아무것도 안 본다")
        assertFailsWith<XcapeHongdaeCrawlException> { crawler.parse(sat, renamed) }
    }

    @Test
    fun `모르는 상태 글자는 끊는다`() {
        val odd = fixture("xcapehd-2026-10-24.html").replaceFirst("<span>예약완료</span>", "<span>점검중</span>")
        assertFailsWith<XcapeHongdaeCrawlException> { crawler.parse(sat, odd) }
    }
}
