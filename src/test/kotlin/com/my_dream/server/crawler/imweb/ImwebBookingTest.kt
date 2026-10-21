package com.my_dream.server.crawler.imweb

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 아임웹 예약 위젯 응답을 매장마다 실제 응답으로 고정한다 (아키텍처 D5 · D41).
 *
 * | 픽스처 (단편선 강남 · 2026-10-21 받음) | msg | 상품 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | 10-22 | SUCCESS | 가능 3 · 불가 15 | 상품 이름 `상자 / 10:00` 을 테마·회차로 묶는다 |
 * | 10-28 | Exceeds available booking period | 0 | 사이트가 범위 밖이라고 말한 0개는 정상 |
 * | 10-20 | date is out of valid range. | 0 | 200 인데 실패다 |
 * | 날짜 없이 부른 것 | SUCCESS | 배열 0 (`total` 에만 18) | **SUCCESS 인 0개도 실패다** — 날짜가 안 먹었다 |
 */
class ImwebBookingTest {

    private val crawler = ImwebCrawler(HostRateLimiter(0))
    private val site = ImwebSites.DANPYEONSEON_GANGNAM

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    @Test
    fun `상품을 테마와 회차로 묶는다`() {
        val day = crawler.validate(site, LocalDate.of(2026, 10, 22), fixture("imweb-dpsnnn-2026-10-22.json"))

        assertEquals(listOf("그림자 없는 상자", "사람들은 그것을 행복이라 부르기로 했다"), day.themes.map { it.themeName })
        assertEquals(listOf(9, 9), day.themes.map { it.slots.size })
        assertEquals(18 to 3, day.themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })

        val box = day.themes.first()
        assertEquals("상자", box.externalId, "별칭을 테마 키로 쓴다")
        assertEquals(75, box.runningMinutes)
        assertEquals(LocalTime.of(10, 0), box.slots.first().time, "시각 순으로 세운다")
        assertTrue(box.slots.first().available, "상자 10:00 은 available 배열에 있었다")
        assertTrue(box.posterUrl!!.startsWith("https://"))
    }

    @Test
    fun `범위 밖이라고 밝힌 응답은 빈 하루다`() {
        val day = crawler.validate(site, LocalDate.of(2026, 10, 28), fixture("imweb-dpsnnn-2026-10-28-범위밖.json"))
        assertTrue(day.themes.isEmpty())
    }

    @Test
    fun `200 이어도 msg 가 실패면 끊는다`() {
        assertFailsWith<ImwebCrawlException> {
            crawler.validate(site, LocalDate.of(2026, 10, 20), fixture("imweb-dpsnnn-2026-10-20-지난날.json"))
        }
    }

    @Test
    fun `SUCCESS 인데 상품이 0개면 끊는다 — 날짜가 안 먹은 응답이다`() {
        // 배열만 보면 "그날 회차 0개" 로 읽혀 저장 단계가 멀쩡한 하루를 비운다
        assertFailsWith<ImwebCrawlException> {
            crawler.validate(site, LocalDate.of(2026, 10, 21), fixture("imweb-dpsnnn-날짜없음.json"))
        }
    }

    @Test
    fun `상품 이름 모양이 바뀌면 끊는다`() {
        val renamed = """{"msg":"SUCCESS","available":[{"name":"그림자 없는 상자 10시"}],"unavailable":[]}"""
        assertFailsWith<ImwebCrawlException> { crawler.validate(site, LocalDate.of(2026, 10, 22), renamed) }
    }

    @Test
    fun `JSON 이 아니면 끊는다`() {
        assertFailsWith<ImwebCrawlException> { crawler.validate(site, LocalDate.of(2026, 10, 22), "<html>점검 중</html>") }
    }

    @Test
    fun `수집 대상은 모두 픽스처 테스트가 있다`() {
        // 단편선은 운영 서버에서 403 이라 수집 목록에서 뺐다(2026-10-21). 픽스처는 아임웹 코드를 지키려고 남긴다
        val tested = setOf("www.dpsnnn.com")
        assertTrue(ImwebSites.ALL.map { it.host }.all { it in tested }, "픽스처 없이 더한 매장이 있다")
    }
}
