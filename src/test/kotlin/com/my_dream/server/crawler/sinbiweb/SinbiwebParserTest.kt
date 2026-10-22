package com.my_dream.server.crawler.sinbiweb

import com.my_dream.server.crawler.CrawlHttp
import com.my_dream.server.crawler.HostRateLimiter
import org.junit.jupiter.api.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 신비웹 GET 세대 공통 파서·검증을 **매장마다 실제 응답으로** 고정한다 (아키텍처 D5 · D41).
 *
 * 공통 파서라 **한 매장을 위해 고친 것이 다른 매장을 조용히 깨뜨릴 수 있다.** 그래서 매장마다 한 장씩 문다.
 * 매장을 [SinbiwebSites.ALL] 에 더하면 여기도 한 줄 더한다 — 마지막 테스트가 빠뜨림을 잡는다.
 *
 * | 픽스처 (2026-10-21 받음) | 테마 | 회차 · 가능 | 이 장이 막는 것 |
 * |---|---|---|---|
 * | Studio ESC 10-22 | 2 | 16 · 15 | 영문 부제 괄호를 장르로 자르지 않는다 |
 * | 돌로 1번 10-27 | 4 | 27 · 25 | 겹친 괄호 · hidden 지점 |
 * | 이룸에이트 10-22 | 6 | 60 · 52 | 장르를 뗀 이름으로 테마 번호를 맞춘다 · `60분 + α` |
 * | 황금열쇠 6번 10-27 | 2 | 18 · 17 | select 지점 · 드롭다운이 없다 |
 * | 황금열쇠 5번 10-21 | 2 | 19 · 17 | 지점이 둘이라 서로 덮지 않는다 |
 * | Studio ESC 10-28 범위 밖 | 0 | — | "예약가능일이 아닙니다" 는 정상 |
 * | Studio ESC 10-26 휴무 | 0 | — | "지점휴무일 입니다" 도 정상 — 배포 첫날 월요일에 실패로 잡혀 더했다 |
 * | 돌로 2번 (없는 지점) | 0 | — | 문구 없는 0개는 예외 — hidden 은 보낸 값을 그대로 돌려준다 |
 * | **홍대 (2026-10-22 받음)** | | | |
 * | 스피키지 10-24 | 2 | 18 · 10 | 창 2주 매장 · 장르 괄호 없음 |
 * | 위욜 10-24 | 3 | 28 · 13 | 방화벽 매장 · 첫·끝 회차가 늘 마감 |
 * | 해피앤딩 10-24 | 4 | 45 · 38 | 괄호가 이름에 붙은 테마(`어?계인씨!(야외활동O)`)를 장르로 자르지 않는다 |
 * | 스피키지 10-28 휴무 | 0 | — | 날짜가 앞에 붙은 휴무 문구(`2026-10-28<br><br>지점휴무일 입니다`)도 정상 |
 * | 황금열쇠 7번(건대) 10-24 | 2 | 20 · 4 | 같은 사이트 세 번째 지점 — select 되읽기로 강남 둘과 가른다 |
 * | 퀘스천마크 10-24 | 2 | 15 · 2 | **이름이 똑같은 별개 테마 둘** — 포스터 번호로 가른다(매장 기믹 · 사용자 확인). 휴대폰 · 메일은 지웠다 |
 */
class SinbiwebParserTest {

    private val parser = SinbiwebParser()
    private val crawler = SinbiwebCrawler(HostRateLimiter(0), parser)

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private val studio = fixture("sinbiweb-studioesc-2026-10-22.html")
    private val dolo = fixture("sinbiweb-dolo-1-2026-10-27.html")
    private val eroom8 = fixture("sinbiweb-eroom8-2026-10-22.html")
    private val goldkey6 = fixture("sinbiweb-goldkey-6-2026-10-27.html")
    private val goldkey5 = fixture("sinbiweb-goldkey-5-2026-10-21.html")
    private val speakeasy = fixture("sinbiweb-speakeasy-2026-10-24.html")
    private val weyol = fixture("sinbiweb-weyol-2026-10-24.html")
    private val happyanding = fixture("sinbiweb-happyanding-2026-10-24.html")
    private val questionmark = fixture("sinbiweb-questionmark-2026-10-24.html")

    private fun themes(html: String, site: SinbiwebSite) = parser.parse(html, site).page.themes

    private fun counts(html: String, site: SinbiwebSite) =
        themes(html, site).flatMap { it.slots }.let { it.size to it.count { s -> s.available } }

    @Test
    fun `매장마다 회차와 예약 가능 수가 실제 응답과 같다`() {
        assertEquals(16 to 15, counts(studio, SinbiwebSites.STUDIO_ESC))
        assertEquals(27 to 25, counts(dolo, SinbiwebSites.DOLO))
        assertEquals(60 to 52, counts(eroom8, SinbiwebSites.EROOM8))
        assertEquals(18 to 17, counts(goldkey6, SinbiwebSites.GOLDKEY))
        assertEquals(19 to 17, counts(goldkey5, SinbiwebSites.GOLDKEY))
        assertEquals(18 to 10, counts(speakeasy, SinbiwebSites.SPEAKEASY))
        assertEquals(28 to 13, counts(weyol, SinbiwebSites.WEYOL))
        assertEquals(45 to 38, counts(happyanding, SinbiwebSites.HAPPYANDING))
        assertEquals(15 to 2, counts(questionmark, SinbiwebSites.QUESTION_MARK))
    }

    @Test
    fun `이름이 똑같은 별개 테마는 포스터 번호로 가른다 — 퀘스천마크`() {
        // 매장 기믹이다 — 같은 이름 · 다른 시간표(8칸 / 7칸). 이름으로 맞추면 둘 다 3번이 돼 한 테마로 섞인다
        val q = themes(questionmark, SinbiwebSites.QUESTION_MARK)
        assertEquals(listOf("퀘스천마크 ?", "퀘스천마크 ?"), q.map { it.themeName })
        assertEquals(listOf("1", "3"), q.map { it.externalId })
        assertEquals(listOf(8, 7), q.map { it.slots.size })
        val day = crawler.validate(SinbiwebSites.QUESTION_MARK, SinbiwebSites.QUESTION_MARK.branches.single(), LocalDate.of(2026, 10, 24), questionmark)
        assertEquals(2, day.themes.size)
    }

    @Test
    fun `테마 키가 겹치면 저장 전에 끊는다 — 같은 이름을 번호로 못 가른 날`() {
        // 포스터가 바뀌어 번호를 못 얻으면 두 테마가 같은 이름 키가 된다 — 조용히 섞지 않는다
        val noPoster = questionmark.replace("file/theme/1_a.jpg", "file/theme/x_a.jpg").replace("file/theme/3_a.jpg", "file/theme/y_a.jpg")
        assertTrue("x_a.jpg" in noPoster && "y_a.jpg" in noPoster, "픽스처 모양이 바뀌었다 — 이 테스트가 아무것도 안 본다")
        assertFailsWith<SinbiwebCrawlException> {
            crawler.validate(SinbiwebSites.QUESTION_MARK, SinbiwebSites.QUESTION_MARK.branches.single(), LocalDate.of(2026, 10, 24), noPoster)
        }
    }

    @Test
    fun `홍대 셋 — 이름과 번호가 드롭다운과 맞고 장르를 자르지 않는다`() {
        val h = themes(happyanding, SinbiwebSites.HAPPYANDING)
        assertTrue("어?계인씨!(야외활동O)" in h.map { it.themeName }, "괄호를 장르로 잘랐다: ${h.map { it.themeName }}")
        assertTrue(listOf(speakeasy to SinbiwebSites.SPEAKEASY, weyol to SinbiwebSites.WEYOL, happyanding to SinbiwebSites.HAPPYANDING)
            .all { (html, site) -> themes(html, site).all { it.externalId != null && it.genre == null } })
        assertEquals(setOf("4", "5", "6"), themes(weyol, SinbiwebSites.WEYOL).map { it.externalId }.toSet())
        assertEquals(setOf("1", "2", "3", "4"), h.map { it.externalId }.toSet())
    }

    @Test
    fun `예약 가능은 href 로 본다`() {
        // 돌로 시선 10-27 — 7회차 중 2개가 a.end 다
        val sight = themes(dolo, SinbiwebSites.DOLO).first { it.themeName == "시선" }
        assertEquals(7, sight.slots.size)
        assertEquals(5, sight.slots.count { it.available })
        assertTrue(sight.slots.all { it.time.isAfter(LocalTime.of(9, 0)) }, "시각을 못 읽었다: ${sight.slots}")
    }

    @Test
    fun `장르는 공백 두 칸 뒤 괄호다 — 영문 부제와 겹친 괄호를 가른다`() {
        val s = themes(studio, SinbiwebSites.STUDIO_ESC)
        assertEquals(listOf("검은마법사 (The Dark Enchanter)", "하얀마법사 (The Pure Enchanter)"), s.map { it.themeName })
        assertTrue(s.all { it.genre == null }, "영문 부제를 장르로 잘랐다: ${s.map { it.genre }}")

        val waiting = themes(dolo, SinbiwebSites.DOLO).first { it.themeName == "The 대기실" }
        assertEquals("미니테마(예약방법 공지사항 참조)", waiting.genre, "마지막 ( 에서 잘랐다")

        val e = themes(eroom8, SinbiwebSites.EROOM8)
        assertEquals("민초", e[1].themeName)
        assertEquals("코믹 / 잠입", e[1].genre)
    }

    @Test
    fun `테마 번호를 드롭다운에서 맞춘다 — 드롭다운이 없으면 비운다`() {
        assertEquals(listOf("1", "2"), themes(studio, SinbiwebSites.STUDIO_ESC).map { it.externalId })
        assertEquals(listOf("8", "4", "5", "6"), themes(dolo, SinbiwebSites.DOLO).map { it.externalId })
        assertEquals(listOf("14", "13", "16", "11", "10", "15"), themes(eroom8, SinbiwebSites.EROOM8).map { it.externalId })
        // 황금열쇠는 s_theme_num 드롭다운이 없다. 이름이 키가 된다 (ScheduleSyncService)
        assertTrue(themes(goldkey6, SinbiwebSites.GOLDKEY).all { it.externalId == null })
    }

    @Test
    fun `메타데이터 — 인원 · 시간 · 난이도 · 포스터`() {
        val goin = themes(eroom8, SinbiwebSites.EROOM8).first()
        assertEquals("2~5명", goin.capacity)
        assertEquals(60, goin.runningMinutes, "`60분 + α` 에서 60 을 못 읽었다")
        assertTrue((goin.difficulty ?: 0.0) > 0)
        assertTrue(goin.posterUrl!!.startsWith("https://eroom8.co.kr/"), "포스터 주소가 안 펴졌다: ${goin.posterUrl}")

        // 단위 없이 `시간 : 75` 인 매장
        assertEquals(75, themes(studio, SinbiwebSites.STUDIO_ESC).first().runningMinutes)
        // http 매장은 포스터도 http 로 편다
        assertTrue(themes(goldkey6, SinbiwebSites.GOLDKEY).first().posterUrl!!.startsWith("http://"))
    }

    // ── 검증 (SinbiwebCrawler.validate) ─────────────────────────────────

    private val goldkeyFlower = SinbiwebSites.GOLDKEY.branches.first { it.zizum == 6 }
    private val goldkeyTimes = SinbiwebSites.GOLDKEY.branches.first { it.zizum == 5 }

    @Test
    fun `황금열쇠 건대 — 같은 사이트 세 번째 지점`() {
        val konkuk = SinbiwebSites.GOLDKEY.branches.first { it.zizum == 7 }
        val html = fixture("sinbiweb-goldkey-7-2026-10-24.html")
        val day = crawler.validate(SinbiwebSites.GOLDKEY, konkuk, LocalDate.of(2026, 10, 24), html)
        assertEquals("goldkey-konkuk", day.store.key)
        assertEquals(listOf("fl[ae]sh", "NOW HERE"), day.themes.map { it.themeName })
        assertEquals(listOf("공포,스릴러", "잠입"), day.themes.map { it.genre })
        assertEquals(20 to 4, counts(html, SinbiwebSites.GOLDKEY))
        // 건대 응답을 강남으로 읽으면 끊는다
        assertFailsWith<SinbiwebCrawlException> { crawler.validate(SinbiwebSites.GOLDKEY, goldkeyFlower, LocalDate.of(2026, 10, 24), html) }
    }

    @Test
    fun `물어본 지점과 날짜면 통과한다`() {
        val day = crawler.validate(SinbiwebSites.GOLDKEY, goldkeyFlower, LocalDate.of(2026, 10, 27), goldkey6)
        assertEquals("goldkey-gangnam-flowerroad", day.store.key)
        assertEquals(2, day.themes.size)
    }

    @Test
    fun `다른 지점이 답하면 끊는다 — 강남 두 곳이 서로 덮지 않게`() {
        // 플라워로드(6)를 물었는데 타임스퀘어(5) 응답이 왔다고 치자. s_zizum 이 무시되는 날 이렇게 된다
        assertFailsWith<SinbiwebCrawlException> {
            crawler.validate(SinbiwebSites.GOLDKEY, goldkeyFlower, LocalDate.of(2026, 10, 21), goldkey5)
        }
        crawler.validate(SinbiwebSites.GOLDKEY, goldkeyTimes, LocalDate.of(2026, 10, 21), goldkey5)
    }

    @Test
    fun `다른 날짜가 렌더되면 끊는다`() {
        assertFailsWith<SinbiwebCrawlException> {
            crawler.validate(SinbiwebSites.EROOM8, SinbiwebSites.EROOM8.branches.single(), LocalDate.of(2026, 10, 23), eroom8)
        }
    }

    @Test
    fun `범위 밖이라고 밝힌 0개는 정상이다`() {
        val closed = fixture("sinbiweb-studioesc-2026-10-28-범위밖.html")
        assertTrue(parser.parse(closed, SinbiwebSites.STUDIO_ESC).saysNotOpen)

        val day = crawler.validate(SinbiwebSites.STUDIO_ESC, SinbiwebSites.STUDIO_ESC.branches.single(), LocalDate.of(2026, 10, 28), closed)
        assertTrue(day.themes.isEmpty())
    }

    @Test
    fun `휴무일이라고 밝힌 0개도 정상이다`() {
        // 2026-10-26(월) Studio ESC — 배포 첫날 이 날짜가 실패로 잡혀서 알았다. 조사한 10-21 은 수요일이었다
        val off = fixture("sinbiweb-studioesc-2026-10-26-휴무.html")
        assertTrue(parser.parse(off, SinbiwebSites.STUDIO_ESC).saysNotOpen)
        val day = crawler.validate(SinbiwebSites.STUDIO_ESC, SinbiwebSites.STUDIO_ESC.branches.single(), LocalDate.of(2026, 10, 26), off)
        assertTrue(day.themes.isEmpty())
    }

    @Test
    fun `날짜가 앞에 붙은 휴무 문구도 정상이다 — 스피키지`() {
        val off = fixture("sinbiweb-speakeasy-2026-10-28-휴무.html")
        val day = crawler.validate(SinbiwebSites.SPEAKEASY, SinbiwebSites.SPEAKEASY.branches.single(), LocalDate.of(2026, 10, 28), off)
        assertTrue(day.themes.isEmpty())
    }

    @Test
    fun `문구 없는 0개는 끊는다 — 없는 지점은 hidden 되읽기까지 통과한다`() {
        val missing = fixture("sinbiweb-dolo-2-2026-10-21-없는지점.html")
        val parsed = parser.parse(missing, SinbiwebSites.DOLO)
        assertEquals(2, parsed.renderedZizum, "hidden 은 보낸 값을 그대로 돌려준다 — 되읽기로는 못 거른다")
        assertTrue(!parsed.saysNotOpen)

        val ghost = SinbiwebBranch(2, "없는 지점", "dolo-ghost")
        assertFailsWith<SinbiwebCrawlException> {
            crawler.validate(SinbiwebSites.DOLO, ghost, LocalDate.of(2026, 10, 21), missing)
        }
    }

    // ── 설정 ───────────────────────────────────────────────────────────

    @Test
    fun `지점 키가 겹치지 않는다`() {
        val keys = SinbiwebSites.ALL.flatMap { site -> site.branches.map { it.key } }
        assertEquals(keys.size, keys.toSet().size, "겹치는 지점 키: $keys")
    }

    @Test
    fun `매장마다 픽스처 테스트가 있다`() {
        // 공통 파서는 한 매장을 고치다 다른 매장을 깨뜨린다. 픽스처 없는 매장은 그걸 못 잡는다
        val tested = setOf(
            "studioesc.co.kr", "doloescape.co.kr", "eroom8.co.kr", "xn--jj0b998aq3cptw.com",
            "speakeasyescape.co.kr", "weyol.com", "happyanding.co.kr", "question-mark.co.kr",
        )
        assertEquals(tested, SinbiwebSites.ALL.map { it.host }.toSet(), "픽스처 없이 더한 매장이 있다 — 이 파일에 한 장 더한다")
    }

    @Test
    fun `매장 Client 를 따로 안 만든다 — 공용 공장을 쓴다`() {
        // CrawlHttpTest 가 전체를 보지만, 이 패키지는 Client 파일이 없어서 크롤러 안을 직접 본다
        val src = File("src/main/kotlin/com/my_dream/server/crawler/sinbiweb/SinbiwebCrawler.kt").readText()
        assertTrue("CrawlHttp.client" in src)
        assertNull(Regex("RestClient\\.create\\(").find(src))
        assertTrue(CrawlHttp.READ_TIMEOUT.seconds > 0)
    }
}
