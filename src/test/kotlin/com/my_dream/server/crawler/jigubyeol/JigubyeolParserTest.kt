package com.my_dream.server.crawler.jigubyeol

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제로 받아온 HTML 두 장으로 파서를 고정한다 (아키텍처 D5).
 *
 * **두 장인 이유:** 대구점에는 소수점 평점이 하나도 없다. 그 한 장만 뒀다면
 * `3.5` 를 못 읽는 버그가 영원히 안 잡힌다 — 0.5 단위 평점 버그(D10)를
 * 건대점 픽스처가 통과시켰던 것과 똑같은 자리다.
 *
 * | | 대구점 | 홍대어드벤처점 |
 * |---|---|---|
 * | 공포 `없음` | ✅ | |
 * | 공포 행 없음 | ✅ | ✅ |
 * | 소수점 평점 | | ✅ `3.5` `4.5` |
 * | 예약가능 회차 | 6 | 7 |
 */
class JigubyeolParserTest {

    private val parser = JigubyeolParser()

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private val daegu = fixture("jigubyeol-daegu-2026-08-29.html")
    private val hongdae = fixture("jigubyeol-hongdae-adventure-2026-08-29.html")

    @Test
    fun `렌더된 날짜를 읽는다`() {
        // 이 값이 요청한 날짜와 다르면 크롤러가 예외로 끊는다 (D2)
        assertEquals(LocalDate.of(2026, 8, 29), parser.parse(daegu).renderedDate)
    }

    @Test
    fun `응답이 어느 지점인지 읽는다`() {
        // 지점이 셋이라 이게 없으면 branch 가 무시돼도 아무도 모른다
        assertEquals(1, parser.renderedBranchId(daegu))
        assertEquals(2, parser.renderedBranchId(hongdae))
    }

    @Test
    fun `지점마다 테마가 다르다`() {
        val d = parser.parse(daegu).themes.map { it.themeName }
        val h = parser.parse(hongdae).themes.map { it.themeName }

        assertEquals(7, d.size)
        assertEquals(6, h.size)
        // **겹치는 테마가 하나도 없다** = branch 파라미터가 실제로 적용된다는 증거다.
        // 되돌려받은 branch 값만으로는 "서버에 닿았다" 까지밖에 모른다
        assertTrue(d.intersect(h.toSet()).isEmpty(), "겹침: ${d.intersect(h.toSet())}")
    }

    @Test
    fun `테마 메타데이터를 읽는다`() {
        val inka = parser.parse(daegu).themes.first { it.themeName == "잉카" }

        assertEquals("20", inka.externalId)
        assertEquals("어드벤처,모험,챌린지", inka.genre)
        assertEquals("2~6", inka.capacity)
        assertEquals(80, inka.runningMinutes)
        assertNotNull(inka.posterUrl)
        assertTrue(inka.posterUrl!!.endsWith(".jpg"), inka.posterUrl!!)
    }

    @Test
    fun `공포 없음은 0 이고, 행이 없으면 null 이다`() {
        val themes = parser.parse(daegu).themes

        // "없음" 은 결측이 아니라 "공포 없음" 이라는 값이다. null 로 두면 화면이
        // "정보 없음" 으로 보여 주는데, 사이트는 분명히 밝히고 있다
        assertEquals(0.0, themes.first { it.themeName == "잉카" }.horrorLevel)
        assertEquals(4.0, themes.first { it.themeName == "우리 아빠" }.horrorLevel)
        // 아예 행이 없는 테마 = 사이트가 안 밝힘
        assertNull(themes.first { it.themeName == "펭귄키우기" }.horrorLevel)
    }

    @Test
    fun `소수점 평점을 그대로 읽는다`() {
        val themes = parser.parse(hongdae).themes

        // ⚠️ 여기가 D10 을 잘못 옮겨 붙이면 깨지는 자리다.
        // 래빗홀·플레이33은 `size35` 클래스라 "두 자리면 10으로 나눈다" 였지만,
        // 지구별은 표에 `3.5` 라고 **그대로 적어 준다**. 그 규칙을 가져오면 35 가 된다
        assertEquals(3.5, themes.first { it.themeName == "PINOCCHIO(피노키오)" }.horrorLevel)
        assertTrue(themes.any { it.difficulty == 3.5 }, "난이도 3.5 가 있어야 한다")
        assertTrue(themes.any { it.difficulty == 4.5 }, "난이도 4.5 가 있어야 한다")
        // 정수도 정수로 읽힌다
        assertTrue(themes.any { it.difficulty == 3.0 })
    }

    @Test
    fun `예약 가능과 매진을 문구로 가른다`() {
        val themes = parser.parse(daegu).themes
        val slots = themes.flatMap { it.slots }

        assertEquals(65, slots.size, "회차 전체")
        assertEquals(6, slots.count { it.available }, "예약가능")
        assertEquals(59, slots.count { !it.available }, "예약불가")
    }

    @Test
    fun `매진 회차도 목록에 남는다`() {
        // 빼 버리면 전이(불가 → 가능)를 볼 수 없다. 이 서비스의 전부가 그 변화다
        val inka = parser.parse(daegu).themes.first { it.themeName == "잉카" }

        assertTrue(inka.slots.any { !it.available })
        assertEquals(LocalTime.of(10, 40), inka.slots.first().time)
    }

    @Test
    fun `엉뚱한 HTML 이면 날짜가 없어서 크롤러가 끊는다`() {
        val page = parser.parse("<html><body>점검 중입니다</body></html>")

        // 빈 결과를 정상으로 흘려보내면 그날 회차가 통째로 사라진다.
        // 실패는 실패로 드러나야 한다 — renderedDate 가 null 이면 크롤러가 예외를 던진다
        assertNull(page.renderedDate)
        assertTrue(page.themes.isEmpty())
    }
}
