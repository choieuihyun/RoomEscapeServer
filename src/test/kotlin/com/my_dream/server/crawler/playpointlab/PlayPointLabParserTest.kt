package com.my_dream.server.crawler.playpointlab

import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제로 받아온 HTML 조각 네 장으로 파서를 고정한다 (아키텍처 D5).
 *
 * | | 오리진 09-10 | 오리진 09-06 | 탄탄 09-11 | 해운대 09-11 |
 * |---|---|---|---|---|
 * | 매진 섞임 | ✅ 11/32 | | ✅ 3/24 | ✅ 14/50 |
 * | **전량 매진** | | ✅ 33/33 | | |
 * | `난이도 3+2` | | | | ✅ BANG |
 * | 테마 수 | 4 | 4 | 3 | 6 |
 *
 * **전량 매진 한 장이 중요하다** — 매진 자리에는 `data-booking-time` 속성이 안 붙어서,
 * 속성만 읽는 파서는 이 픽스처에서 **회차가 0개로 나온다.**
 */
class PlayPointLabParserTest {

    private val parser = PlayPointLabParser()

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private val origin = fixture("playpointlab-seomyeon-origin-2026-09-10.html")
    private val originSoldOut = fixture("playpointlab-seomyeon-origin-2026-09-06-전량매진.html")
    private val tantan = fixture("playpointlab-tantan-2026-09-11.html")
    private val haeundae = fixture("playpointlab-haeundae-2026-09-11.html")

    @Test
    fun `지점마다 테마가 다르다`() {
        val o = parser.parse(origin).themes.map { it.themeName }
        val t = parser.parse(tantan).themes.map { it.themeName }
        val h = parser.parse(haeundae).themes.map { it.themeName }

        assertEquals(4, o.size)
        assertEquals(3, t.size)
        assertEquals(6, h.size)
        val all = o + t + h
        assertEquals(all.size, all.toSet().size, "지점 사이에 겹치는 테마: $all")
    }

    @Test
    fun `room-id 로 지점을 대조할 수 있다`() {
        // 조각에 store 도 지점명도 없다. 이게 유일한 대조 수단이다
        assertEquals(setOf("220", "222", "224", "234"), parser.roomIds(origin))
        assertEquals(setOf("217", "218", "219"), parser.roomIds(tantan))
        assertEquals(setOf("201", "202", "203", "204", "206", "223"), parser.roomIds(haeundae))

        // enum 에 박아 둔 값과 실제 응답이 일치해야 한다 — 어긋나면 크롤러가 매 요청을 끊는다
        for (branch in PlayPointLabBranch.entries) {
            val html = when (branch) {
                PlayPointLabBranch.SEOMYEON_ORIGIN -> origin
                PlayPointLabBranch.TANTAN_STREET -> tantan
                PlayPointLabBranch.HAEUNDAE -> haeundae
            }
            assertEquals(branch.roomIds, parser.roomIds(html), "${branch.branchName} room-id 불일치")
        }
    }

    @Test
    fun `날짜는 조각에 없으므로 항상 null 이다`() {
        // ⚠️ 이게 이 매장의 핵심 한계다. 지어내면 크롤러의 대조가 자기 자신과 비교하게 된다
        assertNull(parser.parse(origin).renderedDate)
    }

    @Test
    fun `전량 매진이어도 회차가 다 나온다`() {
        // 매진 자리에는 data-booking-time 이 없다. 속성만 읽으면 여기서 0개가 된다 —
        // 그러면 "매진" 이 "회차 없음" 으로 둔갑해 감시를 걸 자리가 사라진다
        val themes = parser.parse(originSoldOut).themes
        val slots = themes.flatMap { it.slots }

        assertEquals(33, slots.size)
        assertTrue(slots.none { it.available }, "전량 매진 픽스처인데 가능한 자리가 있다")
        assertTrue(slots.all { it.time != LocalTime.MIDNIGHT }, "시각을 못 읽은 자리가 있다")
    }

    @Test
    fun `예약 여부는 available 클래스로 본다`() {
        val steam = parser.parse(origin).themes.first { it.themeName == "스팀스파이어사가" }
        assertEquals(LocalTime.of(12, 20), steam.slots.first().time)
        assertTrue(steam.slots.first().available)

        val page = parser.parse(origin)
        val all = page.themes.flatMap { it.slots }
        assertEquals(21, all.count { it.available })
        assertEquals(11, all.count { !it.available })
    }

    @Test
    fun `소요시간과 장르를 해시태그에서 가른다`() {
        // <p>#75분 #어드벤쳐 #장치방 </p> — 숫자로 시작하는 것이 소요시간, 나머지가 장르
        val steam = parser.parse(origin).themes.first { it.themeName == "스팀스파이어사가" }
        assertEquals(75, steam.runningMinutes)
        assertEquals("어드벤쳐/장치방", steam.genre)
        assertEquals("2~6명", steam.capacity)
    }

    @Test
    fun `난이도에 3+2 같은 값이 와도 앞의 수를 읽는다`() {
        // 해운대 BANG 이 실제로 `난이도 3+2` 다. toDoubleOrNull() 로 통째로 읽으면 null 이 된다
        val bang = parser.parse(haeundae).themes.first { it.themeName == "BANG" }
        assertEquals(3.0, bang.difficulty)

        // 나머지는 평범한 정수다
        assertTrue(parser.parse(origin).themes.all { it.difficulty != null })
    }

    @Test
    fun `테마 번호를 externalId 로 쓴다`() {
        val byName = parser.parse(origin).themes.associate { it.themeName to it.externalId }
        assertEquals("220", byName["스팀스파이어사가"])
        assertEquals(4, byName.size)
        assertTrue(byName.values.all { it != null }, "externalId 가 빈 테마가 있다: $byName")
    }

    @Test
    fun `포스터 URL 을 읽는다`() {
        val steam = parser.parse(origin).themes.first { it.themeName == "스팀스파이어사가" }
        val poster = assertNotNull(steam.posterUrl)
        assertTrue(poster.contains("master-key.co.kr"), "실제 데이터 출처가 master-key 다: $poster")
    }

    @Test
    fun `상세 페이지의 주석 마크업은 파싱되지 않는다`() {
        // ⚠️ /office/detail?bid=44 에는 회차가 든 theme-booking-card 가 있지만 통째로 주석이다.
        // 주석을 파싱하면 매일 똑같은 고정 시간표를 긁으면서 성공한 줄 안다
        val commented = """
            <div id="booking_list">
              <!-- <article class="theme-booking-card" data-room-id="999" data-room-name="주석테마">
                     <button class="booking-slot booked">11:05<span>예약완료</span></button>
                   </article> -->
            </div>
        """.trimIndent()
        assertTrue(parser.parse(commented).themes.isEmpty(), "주석 안 마크업을 읽었다")
        assertTrue(parser.roomIds(commented).isEmpty())
    }
}
