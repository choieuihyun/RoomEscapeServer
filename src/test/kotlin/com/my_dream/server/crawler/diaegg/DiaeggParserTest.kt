package com.my_dream.server.crawler.diaegg

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제로 받아온 HTML 세 장으로 파서를 고정한다 (아키텍처 D5).
 *
 * | | 다이아에그 09-08 | 미스테리인 09-08 | 범위밖 09-05 |
 * |---|---|---|---|
 * | 괄호가 **영문 제목** | ✅ `레드룸(RED ROOM)` | ✅ `무료한 하루(A Free Day)` | |
 * | 괄호 없는 이름 | ✅ `닥터 슈나벨` | ✅ `우시마을` | |
 * | 매진 섞임 | ✅ 30/40 | ✅ 50/68 | |
 * | 폼만 있고 회차 0 | | | ✅ |
 *
 * **범위밖 한 장이 이 매장의 핵심이다** — 200 에 날짜까지 맞게 오고 회차만 없다.
 * 그리고 그 빈 날이 **창 뒤가 아니라 앞**(오늘+1)이라는 게 다른 매장과 다르다.
 */
class DiaeggParserTest {

    private val parser = DiaeggParser()

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private val diaegg = fixture("diaegg-diaegg-2026-09-08.html")
    private val mysteryin = fixture("diaegg-mysteryin-2026-09-08.html")
    private val beforeWindow = fixture("diaegg-diaegg-2026-09-05-범위밖.html")

    @Test
    fun `렌더된 날짜를 읽는다`() {
        assertEquals(LocalDate.of(2026, 9, 8), parser.parse(diaegg).renderedDate)
    }

    @Test
    fun `응답이 어느 지점인지 읽는다 — select 가 아니라 hidden 입력이다`() {
        // 포인트나인 셀렉터(select[name=s_zizum] option[selected])를 그대로 베꼈다면
        // 여기서 항상 null 이 나와 크롤러가 매 요청을 예외로 끊었을 것이다
        assertEquals(1, parser.renderedBranchId(diaegg))
        assertEquals(2, parser.renderedBranchId(mysteryin))
    }

    @Test
    fun `지점마다 테마가 다르다`() {
        val d = parser.parse(diaegg).themes.map { it.themeName }
        val m = parser.parse(mysteryin).themes.map { it.themeName }

        assertEquals(4, d.size)
        assertEquals(7, m.size)
        // 겹치는 테마가 하나도 없다 = s_zizum 이 실제로 적용된다는 증거다.
        // 드롭다운만 보고 "1지점" 이라고 적을 뻔한 자리라 여기에 박아 둔다
        val all = d + m
        assertEquals(all.size, all.toSet().size, "지점 사이에 겹치는 테마: $all")
    }

    @Test
    fun `지점 접두사를 뗀다`() {
        val names = parser.parse(diaegg).themes.map { it.themeName }
        assertTrue(names.none { it.startsWith("[") }, "접두사가 남았다: $names")
        assertEquals(listOf("레드룸(RED ROOM)", "닥터 슈나벨", "신입사원", "회귀"), names)
    }

    @Test
    fun `괄호는 영문 제목이라 장르로 떼지 않는다`() {
        // ⚠️ 포인트나인 파서는 마지막 괄호를 장르로 잘라낸다. 그 규칙을 여기 옮겨 붙이면
        // "레드룸" + 장르 "RED ROOM" 이 된다 — 이 매장의 괄호는 전부 영문 제목이다
        val red = parser.parse(diaegg).themes.first { it.themeName.startsWith("레드룸") }
        assertEquals("레드룸(RED ROOM)", red.themeName)
        assertNull(red.genre, "예약 페이지에는 장르가 없다. 지어내지 않는다")

        val free = parser.parse(mysteryin).themes.first { it.themeName.startsWith("무료한") }
        assertEquals("무료한 하루(A Free Day)", free.themeName)
    }

    @Test
    fun `사이트가 매긴 테마 번호를 externalId 로 쓴다`() {
        // 이름이 바뀌어도 이력이 안 끊기게. 포인트나인은 이 자리가 없어 이름을 키로 쓴다
        val byName = parser.parse(diaegg).themes.associate { it.themeName to it.externalId }
        assertEquals("4", byName["레드룸(RED ROOM)"])
        assertEquals("1", byName["닥터 슈나벨"])
        assertEquals("3", byName["신입사원"])
        assertEquals("2", byName["회귀"])
    }

    @Test
    fun `예약 여부는 href 존재로 본다`() {
        val red = parser.parse(diaegg).themes.first { it.themeName.startsWith("레드룸") }

        // 테마마다 회차 수가 다르다 — 13/9/10/8. "총 회차 ÷ 테마 수" 로 짐작하면 틀린다
        assertEquals(13, red.slots.size)
        assertEquals(LocalTime.of(10, 10), red.slots.first().time)
        assertEquals(4, red.slots.count { it.available })
        assertEquals(9, red.slots.count { !it.available })

        // 한 테마 안에 두 상태가 다 있다 — 반쪽 픽스처가 아니라는 증거
        val page = parser.parse(diaegg)
        assertTrue(page.themes.any { t -> t.slots.any { it.available } }, "가능한 회차가 한 개도 없다")
        assertTrue(page.themes.any { t -> t.slots.any { !it.available } }, "매진 회차가 한 개도 없다")
    }

    @Test
    fun `소요시간과 인원을 읽는다`() {
        val red = parser.parse(diaegg).themes.first { it.themeName.startsWith("레드룸") }
        assertEquals(40, red.runningMinutes)
        assertEquals("2~6명", red.capacity)

        // 미스테리인에는 2~9명짜리가 있다 — 한 자리 수 범위만 있는 게 아니다
        assertTrue(parser.parse(mysteryin).themes.any { it.capacity == "2~9명" })
    }

    @Test
    fun `괄호 앞에 공백이 있어도 이름 그대로 둔다`() {
        // `타임크루즈 (Time Cruise)` — 다른 테마와 달리 괄호 앞에 공백이 있다.
        // 접두사만 떼고 나머지는 손대지 않으므로 공백이 보존되어야 한다
        val names = parser.parse(mysteryin).themes.map { it.themeName }
        assertTrue(names.contains("타임크루즈 (Time Cruise)"), "실제: $names")
    }

    @Test
    fun `난이도는 아이콘 개수다`() {
        val red = parser.parse(diaegg).themes.first { it.themeName.startsWith("레드룸") }
        assertEquals(2.0, red.difficulty)
    }

    @Test
    fun `포스터는 절대 URL 로 편다`() {
        val red = parser.parse(diaegg).themes.first { it.themeName.startsWith("레드룸") }
        val poster = assertNotNull(red.posterUrl)
        assertTrue(poster.startsWith("https://diaegg.com/"), "상대경로가 안 펴졌다: $poster")
    }

    @Test
    fun `창보다 앞선 날짜는 폼만 있고 회차가 없다`() {
        val page = parser.parse(beforeWindow)

        // ⚠️ 이 매장의 함정. 302 도 아니고 에러도 아니다 —
        // **날짜까지 요청한 그대로 되돌아오는데** 회차만 통째로 없다.
        // 게다가 다른 매장과 달리 비는 쪽이 창의 **앞**이다 (오늘+1)
        assertEquals(LocalDate.of(2026, 9, 5), page.renderedDate)
        assertEquals(1, parser.renderedBranchId(beforeWindow))
        assertTrue(page.themes.isEmpty(), "회차가 없어야 한다: ${page.themes.map { it.themeName }}")
    }

    @Test
    fun `오픈 범위는 사이트가 안 밝히므로 null 이다`() {
        // 우리가 잰 값(+4~+10)은 DiaeggBranch 에 있다. 여기서 지어내면
        // warnIfRangeChanged 가 우리 추측끼리 비교하게 된다
        assertNull(parser.parse(diaegg).reservationRangeDays)
    }
}
