package com.my_dream.server.crawler.bitphobia

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
 * | | 던전101 | 서면던전 | 홍대던전Ⅲ | 오픈전 |
 * |---|---|---|---|---|
 * | 매진 섞임 | ✅ 4/45 | ✅ 2/27 | ✅ **42/46** | |
 * | 지점별 테마 다름 | ✅ | ✅ | ✅ | |
 * | **테마는 있고 회차 0개** | | | | ✅ |
 *
 * **마지막 한 장이 이 매장의 핵심이다** — `200` 에 날짜·지점·테마까지 다 정상인데
 * 회차만 0개다. 포인트나인(테마도 0개)과 달라서 **테마 유무로 못 가른다** (D22).
 */
class BitphobiaParserTest {

    private val parser = BitphobiaParser()

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private val dungeon101 = fixture("bitphobia-dungeon101-2026-09-02.html")
    private val seomyeon = fixture("bitphobia-seomyeon-2026-09-02.html")
    private val hongdae3 = fixture("bitphobia-hongdae3-2026-09-05.html")
    private val beforeOpen = fixture("bitphobia-dungeon101-2026-09-06-오픈전.html")

    @Test
    fun `렌더된 날짜와 지점을 읽는다`() {
        assertEquals(LocalDate.of(2026, 9, 2), parser.parse(dungeon101).renderedDate)
        // 지점이 아홉이라 이게 없으면 s_zizum 이 무시돼도 전부 던전101 로 덮인다
        assertEquals(1, parser.renderedBranchId(dungeon101))
        assertEquals(7, parser.renderedBranchId(seomyeon))
        assertEquals(5, parser.renderedBranchId(hongdae3))
    }

    @Test
    fun `지점마다 테마가 다르다`() {
        val d = parser.parse(dungeon101).themes.map { it.themeName }
        val s = parser.parse(seomyeon).themes.map { it.themeName }
        val h = parser.parse(hongdae3).themes.map { it.themeName }

        assertEquals(4, d.size)
        assertEquals(3, s.size)
        assertEquals(4, h.size)
        val all = d + s + h
        assertEquals(all.size, all.toSet().size, "지점 사이에 겹치는 테마: $all")
    }

    @Test
    fun `예약안내 문단을 테마로 세지 않는다`() {
        // 페이지 아래쪽 `예약 · 게임진행 · 입장 제한…` 안내에도 `p.tit` 이 붙어 있다.
        // 선택자를 `p.tit` 으로 넓게 잡으면 **안내문 셋이 테마로 들어온다** —
        // 그러면 회차 0개짜리 유령 테마가 매 바퀴 저장된다
        assertTrue(
            parser.parse(dungeon101).themes.none { it.themeName in setOf("예약", "게임진행") },
            parser.parse(dungeon101).themes.map { it.themeName }.toString(),
        )
    }

    @Test
    fun `테마 id 는 _fun_theme_view 의 숫자다`() {
        val theme = parser.parse(dungeon101).themes.first { it.themeName == "화생설화 : Blooming" }

        // 회차 URL 의 crypt_data 는 암호화 덩어리라 키로 못 쓴다. 이게 유일한 안정 키다
        assertEquals("1", theme.externalId)
        assertNotNull(theme.posterUrl)
        assertTrue(theme.posterUrl!!.startsWith("https://xdungeon.net/file/theme/"), theme.posterUrl!!)
    }

    @Test
    fun `사이트가 안 주는 값은 0 이 아니라 null 이다`() {
        val theme = parser.parse(seomyeon).themes.first()

        // 없는 정보를 0 으로 채우면 화면이 "공포도 0 · 60분" 같은 거짓말을 그린다
        assertNull(theme.genre)
        assertNull(theme.capacity)
        assertNull(theme.runningMinutes)
        assertNull(theme.horrorLevel)
        assertNull(theme.difficulty)
    }

    @Test
    fun `예약 여부는 href 로 가른다 — sale 클래스가 아니다`() {
        val slots = parser.parse(hongdae3).themes.flatMap { it.slots }

        // ⚠️ 매진 회차에도 class 에 `sale` 이 들어 있다(`dead sale`). 세일 배지지 예약 표시가 아니다.
        // 46개 중 42개가 매진인 픽스처를 고른 이유가 이것이다 —
        // `sale` 을 근거로 삼았다면 **전 회차가 가능으로 읽혔을 것이다**
        assertEquals(46, slots.size, "회차 전체")
        assertEquals(4, slots.count { it.available }, "예약가능")
        assertEquals(42, slots.count { !it.available }, "예약불가")
    }

    @Test
    fun `클래스와 href 가 어긋나면 href 를 믿는다`() {
        // **이 테스트가 없으면 판정 근거를 `dead` 클래스로 바꿔도 아무것도 안 깨진다.**
        // 실제로 확인했다 — 픽스처에서는 `dead` 와 `href` 없음이 언제나 같이 나온다.
        // 포인트나인에서 똑같은 구멍을 발견했던 자리라, 여기서는 처음부터 막는다.
        //
        // **왜 href 가 근거인가** — 예약 폼으로 가는 링크는 실제로 예약이 될 때만 생긴다.
        // 클래스 이름은 디자인 사정으로 언제든 바뀐다(`dead` → `soldout` 한 줄이면 끝이다).
        val page = parser.parse(
            """
            <input name="rev_days" value="2026-09-02">
            <div class="thm_box"><div class="box">
              <div class="img_wrap"><a href="javascript:_fun_theme_view('99')"><img src="/x.png"></a></div>
              <p class="tit">시험용</p>
              <div class="time_box"><ul>
                <li class="sale"><a><span>SALE</span>11:00</a></li>
                <li class="dead sale"><a href="home.php?go=rev.make&crypt_data=ABC">12:00</a></li>
              </ul></div>
            </div></div>
            """.trimIndent(),
        )
        val slots = page.themes.single().slots

        // 클래스는 `sale`(=매진 아님처럼 보임) 인데 링크가 없다 → 불가
        assertEquals(false, slots[0].available, "href 가 없으면 클래스가 뭐든 불가다")
        // 클래스는 `dead` 인데 링크가 있다 → 가능
        assertEquals(true, slots[1].available, "href 가 있으면 클래스를 몰라도 가능이다")
    }

    @Test
    fun `SALE 배지가 붙어도 시각을 제대로 읽는다`() {
        // `<a><span>SALE</span>09:55</a>` — span 을 안 빼면 시각 파싱이 통째로 실패한다
        val theme = parser.parse(dungeon101).themes.first { it.themeName == "화생설화 : Blooming" }

        assertEquals(LocalTime.of(9, 55), theme.slots.first().time)
        assertTrue(theme.slots.any { it.time == LocalTime.of(10, 45) }, "SALE 배지 달린 매진 회차")
    }

    @Test
    fun `오픈 전 날짜는 테마가 다 있는데 회차만 0개다`() {
        val page = parser.parse(beforeOpen)

        // ⚠️ **이 매장의 함정.** 날짜도 지점도 테마도 정상이라 D20(폼·테마 유무)으로 못 가른다.
        // 매일 하루씩 여는 사이트라 창의 마지막 날은 오픈 시각 전까지 이 모양이다
        assertEquals(LocalDate.of(2026, 9, 6), page.renderedDate)
        assertEquals(1, parser.renderedBranchId(beforeOpen))
        assertEquals(4, page.themes.size, "테마는 그대로 있어야 한다")
        assertEquals(0, page.themes.sumOf { it.slots.size }, "회차만 0개여야 한다")
    }

    @Test
    fun `엉뚱한 HTML 이면 날짜도 지점도 없어서 크롤러가 끊는다`() {
        val junk = "<html><body>점검 중입니다</body></html>"

        assertNull(parser.parse(junk).renderedDate)
        assertNull(parser.renderedBranchId(junk))
        assertTrue(parser.parse(junk).themes.isEmpty())
    }
}
