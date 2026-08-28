package com.my_dream.server.crawler.rabbithole

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제로 받아둔 홍대점 2026-08-29 페이지로 검증한다.
 *
 * 픽스처가 둘이다. `08-29` 는 전 회차 매진, `09-02` 는 **가능 1 · 불가 15** 로 섞여 있다.
 * 처음엔 매진 표본만 있어서 "불가일 때만 label 이 붙는다" 고 잘못 봤다 —
 * **반쪽 픽스처는 반쪽만 지킨다.**
 */
class RabbitholeParserTest {

    private val parser = RabbitholeParser()
    private val html = requireNotNull(javaClass.getResource("/rabbithole-hongdae-2026-08-29.html")).readText()
    private val page = parser.parse(html)

    @Test
    fun `페이지가 렌더한 날짜를 읽는다`() {
        assertEquals(LocalDate.of(2026, 8, 29), page.renderedDate)
    }

    @Test
    fun `한 번의 요청에서 지점의 모든 테마를 읽는다`() {
        assertEquals(listOf("행운만물상", "두껍아 두껍아 헌집줄게 새집다오"), page.themes.map { it.themeName })
    }

    @Test
    fun `테마 select 에서 사이트 고유 ID를 읽는다`() {
        assertEquals("5", page.themes.first { it.themeName == "행운만물상" }.externalId)
        assertEquals("4", page.themes.first { it.themeName.startsWith("두껍아") }.externalId)
    }

    @Test
    fun `테마 메타데이터를 읽는다`() {
        val luck = page.themes.first { it.themeName == "행운만물상" }

        assertEquals("동화 / 모노룸", luck.genre)
        assertEquals("2~4", luck.capacity)
        // `70min` — 플레이33의 `65분` 과 단위 표기가 다르다
        assertEquals(70, luck.runningMinutes)
        // 두 번째 테마는 size2 다 — 섹션마다 제 값을 읽는지 같이 본다
        assertEquals(2.0, page.themes.first { it.themeName.startsWith("두껍아") }.difficulty)
        assertEquals(4.0, luck.difficulty)
        assertTrue(luck.posterUrl!!.startsWith("https://"))
        // 사이트가 공포도를 주지 않는다. 없는 걸 0 으로 채우지 않는다
        assertNull(luck.horrorLevel)
    }

    @Test
    fun `label 이 있으면 예약 불가다`() {
        val slots = page.themes.first().slots

        assertEquals(9, slots.size)
        assertTrue(slots.none { it.available }, "이 픽스처는 전 회차 매진이다")
        assertEquals(LocalTime.of(9, 50), slots.first().time)
    }

    @Test
    fun `예약가능 label 을 가능으로 읽는다`() {
        // ⚠️ 처음엔 "불가일 때만 label 이 붙는다" 고 봤는데 틀렸다.
        // 배포한 서버가 `모르는 예약 상태 문구 — "예약가능"` 경고를 뱉어서 알았고,
        // 그때까지 래빗홀 전 회차를 매진으로 읽고 있었다.
        val mixed = parser.parse(
            requireNotNull(javaClass.getResource("/rabbithole-hongdae-2026-09-02.html")).readText(),
        )
        val slots = mixed.themes.flatMap { it.slots }

        assertEquals(16, slots.size)
        assertEquals(1, slots.count { it.available }, "예약가능 1개")
        assertEquals(15, slots.count { !it.available }, "예약불가 15개")
    }

    @Test
    fun `모르는 문구는 불가로 본다`() {
        // 잘못 "가능" 으로 읽으면 감시 걸어둔 사람 전원에게 헛알림이 나간다.
        // 반대로 틀리면 알림이 안 갈 뿐이다 — 안전한 쪽으로 틀린다
        val renamed = html.replace("<label>예약불가</label>", "<label>마감</label>")

        assertTrue(parser.parse(renamed).themes.first().slots.none { it.available })
    }

    @Test
    fun `예약 페이지가 아니면 렌더된 날짜가 없다`() {
        val notReservation = parser.parse("<html><body><h1>RABBITHOLE</h1></body></html>")

        assertNull(notReservation.renderedDate)
        assertTrue(notReservation.themes.isEmpty())
    }
}
