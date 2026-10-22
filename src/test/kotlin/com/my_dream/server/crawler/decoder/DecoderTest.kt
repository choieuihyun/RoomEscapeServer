package com.my_dream.server.crawler.decoder

import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 디코더(Bookly) 응답을 실제 응답으로 고정한다 (조사 문서 "2026-10-22 홍대 조사").
 *
 * | 픽스처 (2026-10-22 받음) | 이 장이 막는 것 |
 * |---|---|
 * | room1 10월 (10-22~10-31) | 한 달치에서 하루를 꺼낸다 · 가능 = disabled · booked 둘 다 없음 · 시각은 value 에서 |
 * | room2 10월 | **다른 방 응답**(staff 5)을 room1 으로 읽으면 끊는다 — 응답에 테마 이름이 없다 |
 * | room1 12월 | 12-04 는 slots 에 없고 disabled_days 에 있다 — 휴무 = 회차 0개 정상 · 월이 0부터 |
 * | 틀린 form_id | `success:true · has_slots:false · slots:[]` — 빈 달처럼 보이지만 실패다 |
 */
class DecoderTest {

    private val crawler = DecoderCrawler(HostRateLimiter(0))
    private val room1 = Decoder.THEMES.first { it.serviceId == "1" }
    private val room2 = Decoder.THEMES.first { it.serviceId == "2" }
    private val today = LocalDate.of(2026, 10, 22)

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }.bufferedReader().readText()

    @Test
    fun `한 달치에서 하루를 꺼낸다 — 가능은 disabled 도 booked 도 없는 것`() {
        val sat = crawler.parse(room1, LocalDate.of(2026, 10, 24), fixture("decoder-room1-2026-10.json"), today)
        val theme = sat.themes.single()
        assertEquals("Introverted Thief", theme.themeName)
        assertEquals("1", theme.externalId)
        assertEquals(9 to 6, theme.slots.size to theme.slots.count { it.available })

        // 오늘은 지난 회차(10:00)가 목록에서 빠져 8개다
        val now = crawler.parse(room1, today, fixture("decoder-room1-2026-10.json"), today).themes.single()
        assertEquals(8 to 4, now.slots.size to now.slots.count { it.available })
        assertTrue(now.slots.first { it.time == LocalTime.of(11, 30) }.available)
        assertEquals(false, now.slots.first { it.time == LocalTime.of(13, 0) }.available)
    }

    @Test
    fun `창은 응답의 date_min~date_max 다 — 월이 0부터`() {
        val day = crawler.parse(room1, LocalDate.of(2026, 10, 24), fixture("decoder-room1-2026-10.json"), today)
        assertEquals(61, day.reservationRangeDays, "[2026,9,22]~[2026,11,21] = 10-22~12-21")
        assertEquals(Decoder.OPEN_DAYS, day.reservationRangeDays, "다르면 매 요청 '범위가 바뀌었다' 경고가 뜬다")
        assertFailsWith<DecoderCrawlException> { crawler.parse(room1, LocalDate.of(2026, 12, 22), fixture("decoder-room1-2026-12-휴무1204.json"), today) }
    }

    @Test
    fun `휴무일은 slots 에 없고 disabled_days 에 있다 — 회차 0개 정상`() {
        val off = crawler.parse(room1, LocalDate.of(2026, 12, 4), fixture("decoder-room1-2026-12-휴무1204.json"), today)
        assertTrue(off.themes.single().slots.isEmpty())
        val open = crawler.parse(room1, LocalDate.of(2026, 12, 5), fixture("decoder-room1-2026-12-휴무1204.json"), today)
        assertTrue(open.themes.single().slots.isNotEmpty())
    }

    @Test
    fun `물은 날짜가 slots 에도 휴무에도 없으면 실패다`() {
        // 10월 응답에 11월 날짜를 물으면 — 한 달 캐시 키가 어긋나는 날 이렇게 된다
        assertFailsWith<DecoderCrawlException> { crawler.parse(room1, LocalDate.of(2026, 11, 3), fixture("decoder-room1-2026-10.json"), today) }
    }

    @Test
    fun `다른 방 응답이면 끊는다 — staff_id 로 대조한다`() {
        assertFailsWith<DecoderCrawlException> { crawler.parse(room1, LocalDate.of(2026, 10, 24), fixture("decoder-room2-2026-10.json"), today) }
        val ok = crawler.parse(room2, LocalDate.of(2026, 10, 24), fixture("decoder-room2-2026-10.json"), today)
        assertEquals("Furious Revenge", ok.themes.single().themeName)
    }

    @Test
    fun `has_slots false 는 빈 달이 아니라 실패다`() {
        assertFailsWith<DecoderCrawlException> { crawler.parse(room1, LocalDate.of(2026, 10, 24), fixture("decoder-틀린formid.json"), today) }
    }

    @Test
    fun `작업의 테마 번호가 크롤러가 다는 번호와 같다 — 감시 빠른 확인이 고를 수 있게`() {
        val units = DecoderAdapter(crawler).plan(listOf(LocalDate.now()))
        assertEquals(Decoder.THEMES.map { it.serviceId }, units.map { it.themeExternalId })
    }
}
