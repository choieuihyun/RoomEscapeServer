package com.my_dream.server.crawler.play33

import com.my_dream.server.crawler.Slot
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
        assertEquals(1.0, witness.horrorLevel)
        assertEquals(2.0, witness.difficulty)
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

/**
 * 대전점 2026-08-28 — **0.5 단위** 평점이 들어 있는 페이지다.
 *
 * 건대점 픽스처는 공포·난이도가 전부 정수라서, 소수점을 지우던 버그를 못 잡았다.
 * (`"0.5"` → `5`, `size25` → `25`) 정수만 있는 표본으로는 증명되지 않는 게 있다.
 */
class Play33ParserHalfStepTest {

    private val parser = Play33Parser()
    private val html = requireNotNull(javaClass.getResource("/play33-daejeon-2026-08-28.html")).readText()
    private val page = parser.parse(html)

    @Test
    fun `공포도의 소수점을 지우지 않는다`() {
        // 이게 5.0 으로 읽히면 "거의 안 무섭다" 가 "최고 공포" 가 된다
        assertEquals(0.5, page.themes.first { it.themeName == "좌충우돌 꼬마마법사" }.horrorLevel)
    }

    @Test
    fun `공포도가 정수인 테마도 그대로 읽는다`() {
        assertEquals(0.0, page.themes.first { it.themeName == "우울해서 빵 샀어" }.horrorLevel)
        assertEquals(1.0, page.themes.first { it.themeName == "자각몽(自覺夢)" }.horrorLevel)
        assertEquals(4.0, page.themes.first { it.themeName == "강천여자고등학교" }.horrorLevel)
    }

    @Test
    fun `sizeN 이 두 자리면 반 칸이다`() {
        // reservation.css 가 size1·size15·size2·size25 … 를 각각 다르게 그린다
        assertEquals(2.5, page.themes.first { it.themeName == "우울해서 빵 샀어" }.difficulty)
        assertEquals(2.0, page.themes.first { it.themeName == "좌충우돌 꼬마마법사" }.difficulty)
        assertEquals(4.0, page.themes.first { it.themeName == "자각몽(自覺夢)" }.difficulty)
        assertEquals(3.0, page.themes.first { it.themeName == "강천여자고등학교" }.difficulty)
    }

    @Test
    fun `난이도는 별 다섯 개를 넘지 않는다`() {
        // 25 같은 값이 다시 새어 나오면 여기서 걸린다
        page.themes.forEach {
            val d = it.difficulty
            assertTrue(d == null || d in 0.0..5.0, "${it.themeName} 난이도 $d")
        }
    }

    @Test
    fun `모르는 표기는 넘겨짚지 않고 비운다`() {
        // 세 자리(백분율 등)로 바뀌면 이상한 숫자를 만들지 말고 null 이어야 한다
        val odd = parser.parse(
            """<section class="reslist"><div class="reslist-text"><strong>가짜</strong>
               <div class="resstep size100 cba"></div></div></section>""",
        )
        assertNull(odd.themes.single().difficulty)
    }
}
