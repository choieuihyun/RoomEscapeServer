package com.my_dream.server.crawler.play33

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제로 받아둔 건대점 2026-09-01 페이지로 파서를 검증한다.
 * 사이트 마크업이 바뀌면 이 테스트가 먼저 깨져서 알려준다.
 */
class Play33ParserTest {

    private val parser = Play33Parser()
    private val html = requireNotNull(javaClass.getResource("/play33-konkuk-2026-09-01.html")).readText()
    private val page = parser.parse(html)

    @Test
    fun `한 번의 요청에서 지점의 모든 테마를 읽는다`() {
        assertEquals(listOf("목격자", "그 날", "다이얼"), page.themes.map { it.themeName })
    }

    @Test
    fun `페이지가 렌더한 날짜를 읽는다`() {
        assertEquals(LocalDate.of(2026, 9, 1), page.renderedDate)
    }

    @Test
    fun `예약 오픈 범위를 읽는다`() {
        assertEquals(7, page.reservationRangeDays)
    }

    @Test
    fun `예약 페이지가 아니면 렌더된 날짜가 없다`() {
        // 범위 밖 날짜는 홈으로 302 된다. 홈에는 날짜 input 도 시간표도 없다.
        val notReservationPage = parser.parse("<html><body><h1>PLAY33</h1></body></html>")

        assertNull(notReservationPage.renderedDate)
        assertTrue(notReservationPage.themes.isEmpty())
    }

    @Test
    fun `테마 select 에서 사이트 고유 ID를 읽는다`() {
        // 이름이 바뀌어도 같은 테마로 이어붙일 수 있게 하는 키다
        assertEquals("18", page.themes.first { it.themeName == "목격자" }.externalId)
        assertEquals("16", page.themes.first { it.themeName == "그 날" }.externalId)
        assertEquals("15", page.themes.first { it.themeName == "다이얼" }.externalId)
    }

    @Test
    fun `테마 메타데이터를 읽는다`() {
        val witness = page.themes.first { it.themeName == "목격자" }

        assertEquals("드라마/스릴러", witness.genre)
        assertEquals("2~3인", witness.capacity)
        assertEquals(65, witness.runningMinutes)
        assertEquals(1, witness.horrorLevel)
        assertEquals(2, witness.difficulty)
        assertNotNull(witness.posterUrl)
    }

    @Test
    fun `분 단위가 생략된 시간도 숫자로 읽는다`() {
        // "그 날" 은 시간이 "60분" 이 아니라 "60" 으로 들어있다
        assertEquals(60, page.themes.first { it.themeName == "그 날" }.runningMinutes)
    }

    @Test
    fun `disabled 여부로 예약 가능을 판단한다`() {
        val witness = page.themes.first { it.themeName == "목격자" }

        assertEquals(10, witness.slots.size)
        assertEquals(Slot(LocalTime.of(10, 35), available = true), witness.slots.first())
        assertEquals(Slot(LocalTime.of(22, 15), available = false), witness.slots.last())
    }

    @Test
    fun `모든 테마가 슬롯을 가진다`() {
        assertTrue(page.themes.isNotEmpty())
        page.themes.forEach { assertEquals(10, it.slots.size, "${it.themeName} 슬롯 수") }
    }
}
