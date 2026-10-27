package com.my_dream.server.crawler.playpointlab

import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제로 받아온 HTML 조각 다섯 장으로 파서를 고정한다 (아키텍처 D5).
 *
 * | | 오리진 09-10 | 오리진 09-06 | 탄탄 09-11 | 해운대 09-11 | 강남 10-21 |
 * |---|---|---|---|---|---|
 * | 매진 섞임 | ✅ 11/32 | | ✅ 3/24 | ✅ 14/50 | ✅ 1/35 |
 * | **전량 매진** | | ✅ 33/33 | | | |
 * | `난이도 3+2` | | | | ✅ BANG | ✅ 작은창고 |
 * | 소요시간 태그 없음 | | | | | ✅ 리허설 · STAFF ONLY |
 * | 테마 수 | 4 | 4 | 3 | 6 | 6 |
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
    private val gangnam = fixture("playpointlab-gangnam-2026-10-21.html")
    private val hongdae = fixture("playpointlab-hongdae-2026-10-22.html")
    private val masterkeyKonkuk = fixture("playpointlab-masterkey-konkuk-2026-10-24.html")
    private val nowon = fixture("playpointlab-nowon-2026-10-29.html")

    @Test
    fun `지점마다 테마가 다르다`() {
        val o = parser.parse(origin).themes.map { it.themeName }
        val t = parser.parse(tantan).themes.map { it.themeName }
        val h = parser.parse(haeundae).themes.map { it.themeName }
        val g = parser.parse(gangnam).themes.map { it.themeName }

        assertEquals(4, o.size)
        assertEquals(3, t.size)
        assertEquals(6, h.size)
        assertEquals(6, g.size)
        // 이름은 겹칠 수 있다 — 해운대와 강남에 둘 다 `리허설` 이 있다. 지점을 가르는 건 room-id 다
        val all = o + t + h
        assertEquals(all.size, all.toSet().size, "부산 지점 사이에 겹치는 테마: $all")
    }

    @Test
    fun `room-id 로 지점을 대조할 수 있다`() {
        // 조각에 store 도 지점명도 없다. 이게 유일한 대조 수단이다
        assertEquals(setOf("220", "222", "224", "234"), parser.roomIds(origin))
        assertEquals(setOf("217", "218", "219"), parser.roomIds(tantan))
        assertEquals(setOf("201", "202", "203", "204", "206", "223"), parser.roomIds(haeundae))
        assertEquals(setOf("193", "194", "195", "196", "208", "209"), parser.roomIds(gangnam))
        assertEquals(setOf("71", "72", "73"), parser.roomIds(hongdae))
        assertEquals(setOf("212", "154", "155"), parser.roomIds(masterkeyKonkuk))
        assertEquals(setOf("183", "184", "185", "186"), parser.roomIds(nowon))

        // 네 지점의 room-id 가 하나도 안 겹쳐야 이 대조가 성립한다
        val sets = PlayPointLabBranch.entries.map { it.roomIds }
        assertEquals(sets.sumOf { it.size }, sets.flatten().toSet().size, "지점 사이에 겹치는 room-id: $sets")

        // enum 에 박아 둔 값과 실제 응답이 일치해야 한다 — 어긋나면 크롤러가 매 요청을 끊는다
        for (branch in PlayPointLabBranch.entries) {
            val html = when (branch) {
                PlayPointLabBranch.SEOMYEON_ORIGIN -> origin
                PlayPointLabBranch.TANTAN_STREET -> tantan
                PlayPointLabBranch.HAEUNDAE -> haeundae
                PlayPointLabBranch.GANGNAM -> gangnam
                PlayPointLabBranch.HONGDAE -> hongdae
                PlayPointLabBranch.KONKUK -> masterkeyKonkuk
                PlayPointLabBranch.NOWON -> nowon
            }
            assertEquals(branch.roomIds, parser.roomIds(html), "${branch.branchName} room-id 불일치")
        }
    }

    @Test
    fun `홍대점 — 세 테마 · 소요시간 태그가 없어도 회차는 읽는다`() {
        // 2026-10-22 받음. 33회차 중 31 가능 · 세 테마 다 해시태그에 소요시간이 없다(강남 리허설과 같다)
        val themes = parser.parse(hongdae).themes
        assertEquals(listOf("71", "72", "73"), themes.map { it.externalId })
        assertEquals(33 to 31, themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
    }

    @Test
    fun `노원점 — 네 테마 · 2026-10-29`() {
        // 2026-10-27 받음(서울 확장). 28회차 중 27 가능
        val themes = parser.parse(nowon).themes
        assertEquals(listOf("184", "185", "183", "186"), themes.map { it.externalId }, "사이트가 보여 주는 순서 그대로")
        assertEquals(28 to 27, themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
    }

    @Test
    fun `마스터키 건대 — 분 없는 소요시간 태그 · 지점별 브랜드`() {
        // #75분 · #70 · #60 — "분" 이 없는 태그를 장르로 섞지 않는다
        val themes = parser.parse(masterkeyKonkuk).themes
        assertEquals(31 to 25, themes.flatMap { it.slots }.let { it.size to it.count { s -> s.available } })
        assertEquals(setOf(75, 70, 60), themes.mapNotNull { it.runningMinutes }.toSet())
        assertTrue(themes.none { (it.genre ?: "").any(Char::isDigit) }, "숫자가 장르에 섞였다: ${themes.map { it.genre }}")
        assertEquals("마스터키", PlayPointLabBranch.KONKUK.toStoreRef().brand)
        assertEquals("플레이포인트랩", PlayPointLabBranch.GANGNAM.toStoreRef().brand)
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

        // 강남 `작은창고` 도 `난이도 3+2` 다 — 한 테마만의 버릇이 아니었다
        assertEquals(3.0, parser.parse(gangnam).themes.first { it.themeName == "작은창고" }.difficulty)

        // 나머지는 평범한 정수다
        assertTrue(parser.parse(origin).themes.all { it.difficulty != null })
    }

    @Test
    fun `소요시간 태그가 없는 테마는 시간만 비고 장르는 남는다`() {
        // 강남 `리허설` 은 `<p>#공포 </p>` 뿐이다. 시간이 없다고 테마를 버리면 회차까지 사라진다
        val rehearsal = parser.parse(gangnam).themes.first { it.themeName == "리허설" }
        assertNull(rehearsal.runningMinutes)
        assertEquals("공포", rehearsal.genre)
        assertTrue(rehearsal.slots.isNotEmpty())
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
