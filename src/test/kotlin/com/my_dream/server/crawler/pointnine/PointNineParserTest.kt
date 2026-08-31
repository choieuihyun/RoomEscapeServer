package com.my_dream.server.crawler.pointnine

import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제로 받아온 HTML 네 장으로 파서를 고정한다 (아키텍처 D5).
 *
 * | | 강남 | 건대 | 홍대 | 범위밖 |
 * |---|---|---|---|---|
 * | 장르 빈 괄호 `()` | ✅ | | | |
 * | 쉼표 든 장르 | | | ✅ `스릴, 수사` | |
 * | 전량 매진 테마 | | ✅ `Jack in the Show` | | |
 * | 매진 섞임 | ✅ 6/8 | | ✅ 17/18 | |
 * | 폼만 있고 회차 0 | | | | ✅ |
 *
 * **범위밖 한 장이 이 매장의 핵심이다** — 200 에 날짜까지 맞게 오고 회차만 없다.
 */
class PointNineParserTest {

    private val parser = PointNineParser()

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private val gangnam = fixture("pointnine-gangnam-2026-09-02.html")
    private val konkuk = fixture("pointnine-konkuk-2026-09-02.html")
    private val hongdae = fixture("pointnine-hongdae-2026-09-02.html")
    private val outOfRange = fixture("pointnine-gangnam-2026-09-10-범위밖.html")

    @Test
    fun `렌더된 날짜를 읽는다`() {
        assertEquals(LocalDate.of(2026, 9, 2), parser.parse(gangnam).renderedDate)
    }

    @Test
    fun `응답이 어느 지점인지 읽는다`() {
        // 지점이 셋이라 이게 없으면 s_zizum 이 무시돼도 전부 강남점으로 덮인다
        assertEquals(1, parser.renderedBranchId(gangnam))
        assertEquals(5, parser.renderedBranchId(konkuk))
        assertEquals(6, parser.renderedBranchId(hongdae))
    }

    @Test
    fun `지점마다 테마가 다르다`() {
        val g = parser.parse(gangnam).themes.map { it.themeName }
        val k = parser.parse(konkuk).themes.map { it.themeName }
        val h = parser.parse(hongdae).themes.map { it.themeName }

        assertEquals(4, g.size)
        assertEquals(3, k.size)
        assertEquals(2, h.size)
        // **겹치는 테마가 하나도 없다** = s_zizum 이 실제로 적용된다는 증거다.
        // 조사 때 이걸 안 해서 "1지점" 이라고 적었었다 — 기본값만 보면 확인이 안 된다
        val all = g + k + h
        assertEquals(all.size, all.toSet().size, "지점 사이에 겹치는 테마: $all")
    }

    @Test
    fun `범위 밖 날짜는 폼만 있고 회차가 없다`() {
        val page = parser.parse(outOfRange)

        // ⚠️ 이 매장의 함정. 302 도 아니고 에러도 아니다 —
        // **날짜까지 요청한 그대로 되돌아오는데** 회차만 통째로 없다
        assertEquals(LocalDate.of(2026, 9, 10), page.renderedDate)
        assertEquals(1, parser.renderedBranchId(outOfRange))
        assertTrue(page.themes.isEmpty())
    }

    @Test
    fun `이름과 장르를 가른다 — 빈 괄호는 null 이다`() {
        val g = parser.parse(gangnam).themes.first()

        // 괄호를 안 떼면 이름이 곧 키라서(externalId 가 없다) 사이트가 장르를 채우는 순간
        // 다른 테마가 되어 버린다. 그러면 이력이 끊기고 전이 감지가 멈춘다
        assertEquals("EP1 : 시간이 멈춘 마을", g.themeName)
        // 빈 문자열이 아니라 null 이어야 화면이 빈 칸을 안 그린다
        assertNull(g.genre)
    }

    @Test
    fun `쉼표가 든 장르도 통째로 읽는다`() {
        val silent = parser.parse(hongdae).themes.first { it.themeName == "SILENT" }

        assertEquals("스릴, 수사", silent.genre)
        assertEquals("LISTEN", parser.parse(hongdae).themes.last().themeName)
    }

    @Test
    fun `난이도는 아이콘 개수다`() {
        // ⚠️ D10 의 `size35` 규칙도, 지구별의 글자 규칙도 여기 오면 안 된다. 세 번째 방식이다
        val g = parser.parse(gangnam).themes
        assertEquals(4.0, g.first { it.themeName == "EP1 : 시간이 멈춘 마을" }.difficulty)
        assertEquals(3.0, g.first { it.themeName == "EP3 : 눈 먼 귀금속상인의 후회" }.difficulty)
        assertEquals(2.0, parser.parse(konkuk).themes.first { it.themeName == "Jack in the Show" }.difficulty)
    }

    @Test
    fun `테마 메타데이터를 읽는다`() {
        val alba = parser.parse(konkuk).themes.first { it.themeName == "ALBA" }

        assertEquals("2~6명", alba.capacity)
        // ⚠️ ALBA 의 HTML 은 `시간 : 70` 이다 — **`분` 이 없다.** 강남 EP1 은 `70분` 이다.
        // 단위를 필수로 두면 이 테마만 조용히 소요시간이 빈다. 픽스처 세 장이라 드러났다
        assertEquals(70, alba.runningMinutes)
        assertEquals("잠입", alba.genre)
        // 상대경로(`../../file/theme/34_a.jpg`)를 절대 URL 로 펴야 화면이 그린다
        assertNotNull(alba.posterUrl)
        assertEquals("https://point-nine.com/file/theme/34_a.jpg", alba.posterUrl)
        // 사이트가 공포도를 안 밝힌다. 0 이 아니라 모른다
        assertNull(alba.horrorLevel)
    }

    @Test
    fun `예약 여부는 href 존재로 가른다`() {
        val g = parser.parse(gangnam).themes
        val slots = g.flatMap { it.slots }

        assertEquals(32, slots.size, "회차 전체")
        assertEquals(27, slots.count { it.available }, "예약가능")
        assertEquals(5, slots.count { !it.available }, "예약불가")
    }

    @Test
    fun `문구와 href 가 어긋나면 href 를 믿는다`() {
        // **이 테스트가 없으면 판정 근거를 문구로 바꿔도 아무것도 안 깨진다.**
        // 실제로 확인해 봤다 — 픽스처에서는 문구와 href 가 언제나 같아서,
        // 회차 수만 세는 테스트는 두 방식을 구분하지 못한다.
        //
        // 사이트가 문구를 바꾸는 것은 흔한 일이고(`예약마감` → `마감`),
        // 그때 문구로 읽으면 **전 회차가 가능으로 뒤집혀 감시자 전원에게 헛알림이 나간다.**
        // 그래서 둘이 어긋나는 상황을 손으로 만들어 규칙을 못 박는다.
        val page = parser.parse(
            """
            <input name="rev_days" value="2026-09-02">
            <div class="theme_box"><h3 class="h3_theme">시험용 (테스트)</h3>
              <ul class="reserve_Time">
                <li><a class="end"><span class="time">11:00 </span><span class="possible">예약가능</span></a></li>
                <li><a href="home.php?go=rev.make.input"><span class="time">12:00 </span><span class="impossible">마감</span></a></li>
              </ul>
            </div>
            """.trimIndent(),
        )
        val slots = page.themes.single().slots

        // 문구는 "예약가능" 인데 예약 폼으로 가는 링크가 없다 → 불가
        assertEquals(LocalTime.of(11, 0), slots[0].time)
        assertEquals(false, slots[0].available, "href 가 없으면 문구가 뭐든 불가다")
        // 문구는 처음 보는 "마감" 인데 링크가 있다 → 가능
        assertEquals(true, slots[1].available, "href 가 있으면 문구를 몰라도 가능이다")
    }

    @Test
    fun `전량 매진 테마도 회차가 그대로 남는다`() {
        // 빼 버리면 전이(불가 → 가능)를 볼 수 없다. 이 서비스의 전부가 그 변화다
        val jack = parser.parse(konkuk).themes.first { it.themeName == "Jack in the Show" }

        assertEquals(8, jack.slots.size)
        assertTrue(jack.slots.none { it.available }, "전 회차가 매진이어야 한다")
        assertEquals(LocalTime.of(13, 10), jack.slots.first().time)
    }

    @Test
    fun `엉뚱한 HTML 이면 날짜도 지점도 없어서 크롤러가 끊는다`() {
        val page = parser.parse("<html><body>점검 중입니다</body></html>")

        assertNull(page.renderedDate)
        assertNull(parser.renderedBranchId("<html><body>점검 중입니다</body></html>"))
        assertTrue(page.themes.isEmpty())
    }
}
