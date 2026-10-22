package com.my_dream.server.crawler.secretgarden

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 비밀의 화원 미드나잇 합정점 응답을 실제 응답으로 고정한다 (조사 문서 "2026-10-22 홍대 조사").
 * 푸터의 지점별 휴대폰 번호는 픽스처에서 지웠다.
 *
 * | 픽스처 (2026-10-22 받음) | 테마 | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 10-24 | 4 | 29 · 21 | 불가에도 href 가 있다 — `SendVar` 로 본다 · 매진 테마도 이름으로 번호를 단다 |
 * | 10-29 (+7) | 4 | 29 · 0 | 창 밖은 "전 회차 마감" — 창 마지막 날이 이 모양이면 아직 안 연 날로 비운다 |
 * | 10-21 (어제) | 4 | 29 · 0 | 오늘 · 중간 날짜의 전부 마감은 그대로 받는다 |
 */
class SecretGardenTest {

    private val crawler = SecretGardenCrawler(HostRateLimiter(0))

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.bufferedReader().readText()

    private val sat = LocalDate.of(2026, 10, 24)

    @Test
    fun `가능은 SendVar 로 본다 — 불가에도 href 가 있다`() {
        val day = crawler.parse(sat, fixture("secretgarden-7-2026-10-24.html"), today = LocalDate.of(2026, 10, 22))
        assertEquals(listOf("비밀의 가족", "파리82", "팩토리 엠", "기다려, 금방 갈게[야외]"), day.themes.map { it.themeName })
        assertEquals(listOf("88", "87", "86", "47"), day.themes.map { it.externalId })
        assertEquals(29 to 21, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })

        val family = day.themes.first()
        assertTrue(family.slots.first { it.time == LocalTime.of(10, 40) }.available)
        assertEquals(false, family.slots.first { it.time == LocalTime.of(14, 0) }.available)
        assertEquals(4.0, family.difficulty, "장미 다섯 중 넷")
    }

    @Test
    fun `창 마지막 날이 전부 마감이면 아직 안 연 날로 비운다`() {
        // 10-29 는 10-23 기준 +6(창 마지막 날)이다. 매장이 이날을 낮에 연다면 그 전엔 이 모양이다
        val day = crawler.parse(LocalDate.of(2026, 10, 29), fixture("secretgarden-7-2026-10-29-창밖전부마감.html"), today = LocalDate.of(2026, 10, 23))
        assertTrue(day.themes.isEmpty(), "저장하면 여는 순간 하루치가 한꺼번에 불가→가능이 된다")
    }

    @Test
    fun `마지막 날이 아니면 전부 마감도 진짜 매진으로 받는다`() {
        // 같은 장을 오늘(10-29)로 보면 — 늦은 밤 오늘은 지난 회차가 전부 마감이다
        val today = crawler.parse(LocalDate.of(2026, 10, 29), fixture("secretgarden-7-2026-10-29-창밖전부마감.html"), today = LocalDate.of(2026, 10, 29))
        assertEquals(4, today.themes.size)
        assertTrue(today.themes.all { t -> t.slots.isNotEmpty() && t.slots.none { it.available } })
        // +5 도 그대로
        val middle = crawler.parse(LocalDate.of(2026, 10, 21), fixture("secretgarden-7-2026-10-21-어제.html"), today = LocalDate.of(2026, 10, 16))
        assertEquals(4, middle.themes.size)
    }

    @Test
    fun `다른 날짜가 렌더되면 끊는다`() {
        assertFailsWith<SecretGardenCrawlException> { crawler.parse(sat.plusDays(1), fixture("secretgarden-7-2026-10-24.html")) }
    }

    @Test
    fun `다른 지점이 답하면 끊는다`() {
        val other = fixture("secretgarden-7-2026-10-24.html").replace("name=\"k_shopno\" value=\"7\"", "name=\"k_shopno\" value=\"8\"")
        assertTrue("value=\"8\"" in other, "픽스처 모양이 바뀌었다 — 이 테스트가 아무것도 안 본다")
        assertFailsWith<SecretGardenCrawlException> { crawler.parse(sat, other) }
    }

    @Test
    fun `설정에 없는 테마가 오면 끊는다`() {
        val renamed = fixture("secretgarden-7-2026-10-24.html").replace("<h3 class=\"h3_theme\">파리82", "<h3 class=\"h3_theme\">새 테마")
        assertFailsWith<SecretGardenCrawlException> { crawler.parse(sat, renamed) }
    }
}
