package com.my_dream.server.crawler.zeroworld

import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제 응답 네 조각으로 고정한다 (아키텍처 D5).
 *
 * **이 매장은 다른 데 없는 검증을 하나 준다** — 회차 응답 끝의 `{@}가능/전체` 가
 * **사이트가 스스로 센 값**이다. 그래서 `href` 판정이 맞는지를 **진짜 응답으로** 확인할 수 있다.
 * 포인트나인·비트포비아에서는 손으로 HTML 을 만들어야 했던 자리다.
 */
class ZeroworldParserTest {

    private val parser = ZeroworldParser()

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private val gimpoThemes = fixture("zeroworld-gimpo-themes.html")
    private val diveThemes = fixture("zeroworld-dive-konkuk-themes.html")
    private val times14 = fixture("zeroworld-gimpo-14-2026-09-05.html")
    private val times15 = fixture("zeroworld-gimpo-15-2026-09-05.html")

    @Test
    fun `테마 목록을 읽는다`() {
        val themes = parser.themes(gimpoThemes)

        assertEquals(11, themes.size)
        assertEquals(2, parser.themes(diveThemes).size, "다이브 건대점은 2테마다")
        // 지점끼리 테마가 안 겹친다
        val overlap = themes.map { it.themeNum }.intersect(parser.themes(diveThemes).map { it.themeNum }.toSet())
        assertTrue(overlap.isEmpty(), overlap.toString())
    }

    @Test
    fun `테마 메타데이터를 읽는다`() {
        val theme = parser.themes(gimpoThemes).first()

        assertTrue(theme.themeNum.toIntOrNull() != null, theme.themeNum)
        assertTrue(theme.name.isNotBlank())
        assertNotNull(theme.runningMinutes)
        assertTrue(theme.runningMinutes!! in 30..180, "소요시간 ${theme.runningMinutes}")
    }

    @Test
    fun `난이도는 채워진 칸 수다 — half 는 반 칸`() {
        // ⚠️ **네 번째 표현 방식이다.** size35 클래스(D10)도, 표의 글자도, 아이콘 개수도 아니다.
        // `full`=1 · `half`=0.5 로 세지 않으면 3.5 가 3 이나 4 로 뭉개진다
        val all = parser.themes(gimpoThemes).mapNotNull { it.difficulty }

        assertTrue(all.isNotEmpty(), "난이도를 하나도 못 읽었다")
        assertTrue(all.all { it in 0.0..5.0 }, all.toString())
        // 0.5 단위가 실제로 나온다 — 정수로 받으면 조용히 틀리는 자리다
        assertTrue(all.any { it % 1.0 != 0.0 }, "0.5 단위가 하나도 없다: $all")
    }

    @Test
    fun `회차와 사이트 집계를 함께 읽는다`() {
        val t = parser.times(times15)

        assertEquals(9, t.slots.size)
        assertEquals(9, t.total, "{@}5/9 의 9")
        assertEquals(5, t.available, "{@}5/9 의 5")
        assertEquals(5, t.slots.count { it.available }, "우리가 센 가능 수도 5여야 한다")
        assertEquals(4, t.slots.count { !it.available })
    }

    @Test
    fun `집계 문구가 회차로 섞여 들어가지 않는다`() {
        // `{@}5/9 가능` 은 `</a>` 뒤에 그냥 붙어 오는 텍스트다.
        // 잘라내지 않으면 시각 파싱이 이상해지거나 회차 수가 어긋난다
        assertTrue(parser.times(times14).slots.all { it.time != null })
        assertEquals(11, parser.times(times14).total)
        assertEquals(9, parser.times(times14).available)
    }

    @Test
    fun `예약 여부는 href 로 가른다`() {
        val t = parser.times(times14)
        assertEquals(11, t.slots.size)
        assertEquals(9, t.slots.count { it.available })
        assertEquals(LocalTime.of(10, 30), t.slots.first().time)
    }

    @Test
    fun `우리 판정이 사이트 집계와 맞으면 통과다`() {
        // **진짜 응답으로 하는 검증이다.** href 규칙이 틀렸다면 여기서 바로 깨진다
        assertNull(parser.times(times14).mismatch())
        assertNull(parser.times(times15).mismatch())
    }

    @Test
    fun `클래스 이름이 바뀌어도 집계가 잡아낸다 — 이 매장의 안전망이다`() {
        // 다른 매장에서는 `href` 냐 클래스냐가 **혼자 서는 판정**이라, 손으로 만든 HTML 로
        // 규칙을 못 박아야 했다(포인트나인·비트포비아). 여기는 다르다 —
        // 사이트가 정답을 같이 보내 주므로 **판정이 틀리면 그 자리에서 드러난다.**
        //
        // 사이트가 `disable` 을 `soldout` 으로 바꿨다고 치자.
        // 클래스로 읽는 파서는 전 회차를 "가능" 으로 읽는다 — 감시자 전원에게 헛알림이 나갈 상황이다
        val 클래스가_바뀐_응답 = """
            <a class="choice-time__time" href="javascript:x">10:30</a>
            <a class="choice-time__time soldout">11:35</a>
            <a class="choice-time__time soldout">12:40</a>
            {@}1/3 가능
        """.trimIndent()

        val t = parser.times(클래스가_바뀐_응답)

        // href 로 읽으면 가능 1개 — 사이트가 말한 1과 같으니 통과한다
        assertEquals(1, t.slots.count { it.available })
        assertNull(t.mismatch())

        // 반대로 클래스(`disable` 없음 = 가능)로 읽었다면 3개가 되어 집계와 어긋났을 것이다
        assertTrue(t.copy(available = 3).mismatch()!!.contains("예약가능 수"))
    }

    @Test
    fun `가능 수가 어긋나면 잡아낸다`() {
        val t = parser.times(times15).copy(available = 99)

        // 제일 무서운 종류다 — href 판정이 틀리면 감시자 전원에게 헛알림이 나간다
        assertTrue(t.mismatch()!!.contains("예약가능 수"), t.mismatch()!!)
    }

    @Test
    fun `회차 수가 어긋나면 잡아낸다`() {
        assertTrue(parser.times(times15).copy(total = 99).mismatch()!!.contains("회차 수"))
    }

    @Test
    fun `집계가 아예 없으면 잡아낸다`() {
        // 조용히 통과시키면 검증이 있는 척만 하게 된다
        val t = parser.times("<a class=\"choice-time__time\" href=\"x\">11:00</a>")

        assertEquals(1, t.slots.size)
        assertNull(t.total)
        assertTrue(t.mismatch()!!.contains("집계"), t.mismatch()!!)
    }
}
