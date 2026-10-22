package com.my_dream.server.crawler.xcapehd

import com.my_dream.server.crawler.CrawlHttp
import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.crawler.openWithin
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime

/**
 * **엑스케이프 홍대점** (`www.xcapehd.co.kr` · 그누보드4 게시판 예약 · 2026-10-22 조사).
 *
 * ⚠️ **강남 엑스케이프(⚠️ 결정 대기 · Supabase · Cloudflare)와 이름만 같고 백엔드가 다르다.** HTML · JS 어디에도 supabase 가 없다.
 *
 * ```
 * GET /bbs/board.php?pt=3&m=t&bo_table=reservation&date=YYYY-MM-DD   → 하루치 전 테마
 *   <input class="inputbox calendar" value="2026-10-24">                                     ← 날짜 되읽기
 *   <span style="font-size: 30px…">녹</span><span>The Rust</span> … <dl>
 *     <dd class="state1"><a href="./write.php?bo_table=reservation&g=2&d=2026-10-24&time=10:00…">  ← 가능
 *     <dd class="state2"><strong>18:00</strong><span>예약완료</span>                              ← 불가 (예약됨 · 지난 회차)
 *     <dd class="state2"><strong>10:00</strong><span>예약불가</span>                              ← **창 밖**
 * ```
 *
 * 🕳️ **불가가 글자로 둘이다.** "예약완료" 는 매진, "예약불가" 는 창 밖(+21 부터 · `2026-13-45` 도)이다 — 클래스는 같다.
 * 창 밖을 매진으로 저장하면 여는 날 하루치가 한꺼번에 불가→가능이 된다. 그래서 **전 회차 "예약불가" 인 날은 받지 않는다.**
 * 창 안에서 "예약불가" 가 섞여 오면 불가로 친다(아직 그런 날은 못 봤다). 모르는 글자는 끊는다.
 *
 * `pt` 는 무시된다(1 과 3 이 바이트까지 같다) — 지점은 도메인 하나에 하나다. http 로만 확인했다.
 */
object XcapeHongdae {
    const val BRAND = "엑스케이프"
    const val HOST = "www.xcapehd.co.kr"
    const val BRANCH_NAME = "홍대점"
    const val KEY = "xcape-hongdae"

    /** 오늘~+20 — 달력 `maxDate:'+1970/01/21'`(21일) · +21 부터 전 회차 "예약불가" (10:2x 실측) */
    const val OPEN_DAYS = 21

    /**
     * 테마 번호(`g`). **매진인 테마는 링크가 없어 번호가 안 보인다** — 이름으로 찾는다.
     * 링크에 번호가 있으면 이 표와 대조한다. 새 테마는 예외로 끊겨 여기 한 줄을 더하게 된다
     */
    val THEMES = mapOf(
        "녹" to "2", "더 다이스룸" to "1", "고요의기지" to "12",
        "그남자 그여자" to "15", "육식주의자" to "16", "버지니아 란제리샵" to "17",
    )

    val storeRef = StoreRef(key = KEY, brand = BRAND, branchName = BRANCH_NAME)
}

class XcapeHongdaeCrawlException(message: String) : RuntimeException(message)

@Component
class XcapeHongdaeCrawler(private val rateLimiter: HostRateLimiter) {

    private val client by lazy { CrawlHttp.client("http://${XcapeHongdae.HOST}") }

    fun fetch(date: LocalDate): DaySchedule = parse(date, request(date))

    private fun request(date: LocalDate): String =
        rateLimiter.throttled(XcapeHongdae.HOST) {
            client.get()
                .uri { it.path("/bbs/board.php").queryParam("pt", 3).queryParam("m", "t").queryParam("bo_table", "reservation").queryParam("date", date).build() }
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    /** 테스트가 픽스처로 부른다 */
    fun parse(date: LocalDate, html: String): DaySchedule {
        val doc = Jsoup.parse(html, "http://${XcapeHongdae.HOST}/bbs/")

        val shown = doc.selectFirst("input.calendar")?.attr("value")
        if (shown != date.toString()) throw XcapeHongdaeCrawlException("요청한 날짜와 렌더된 날짜가 다름: 요청=$date 응답=$shown")

        val list = doc.getElementById("reservation_list") ?: throw XcapeHongdaeCrawlException("예약 목록이 없다 — 마크업이 바뀌었다: $date")

        // 이름 · 정보 · 회차가 형제로 나란히 온다 — 이름 칸(30px)이 나오면 새 테마, 다음 dl 이 그 회차다
        val themes = mutableListOf<ThemeSchedule>()
        var name: String? = null
        var info: Element? = null
        for (el in list.children()) {
            if (el.tagName() == "dl") {
                val themeName = name ?: throw XcapeHongdaeCrawlException("이름 없는 회차 목록 — 마크업이 바뀌었다: $date")
                themes += theme(themeName, el, info, date)
                name = null
                continue
            }
            val title = el.select("span").firstOrNull { "font-size: 30px" in it.attr("style") }
            if (title != null) {
                name = title.text().trim()
                info = null
            } else if ("인원" in el.text()) {
                info = el
            }
        }
        if (themes.isEmpty() || themes.all { it.slots.isEmpty() }) throw XcapeHongdaeCrawlException("회차가 하나도 없다 — 마크업이 바뀌었다: $date")

        // 칸 수로 비교하지 않는다 — 자정 칸을 빼서 회차 수와 칸 수가 다르다
        val states = list.select("dd.state2 span").map { it.text().trim() }
        if (list.select("dd.state1").isEmpty() && states.isNotEmpty() && states.all { it == OUT_OF_WINDOW }) {
            throw XcapeHongdaeCrawlException("전 회차 \"$OUT_OF_WINDOW\" — 창 밖이다(+21 부터). 매진으로 저장하지 않는다: $date")
        }
        return DaySchedule(XcapeHongdae.storeRef, date, null, XcapeHongdae.OPEN_DAYS, themes)
    }

    private fun theme(name: String, dl: Element, info: Element?, date: LocalDate): ThemeSchedule {
        val id = XcapeHongdae.THEMES[name] ?: throw XcapeHongdaeCrawlException("설정에 없는 테마: '$name' $date — XcapeHongdae.THEMES 에 번호를 더한다")

        val slots = dl.select("dd").mapNotNull { dd ->
            val label = dd.selectFirst("strong")?.text()?.trim().orEmpty()
            // 🕳️ `24:00` 회차가 있다(고요의기지 · 버지니아 란제리샵) — 그날 영업의 마지막 회차인 자정이다.
            // 저장 구조(`LocalTime` · 하루 안 시각)가 못 담는다. 00:00 으로 넣으면 그날 새벽 회차가 되고, 23:59 는 없는 시각이다.
            // **틀린 시각으로 넣느니 뺀다** — 작업명세서 ▶ "자정 넘는 회차" 에 숙제로 올렸다
            if (MIDNIGHT_OR_LATER.matches(label)) return@mapNotNull null
            val time = runCatching { LocalTime.parse(label.padStart(5, '0')) }.getOrNull()
                ?: throw XcapeHongdaeCrawlException("시각을 못 읽었다: $name $date '$label'")
            when {
                dd.hasClass("state1") -> {
                    val href = dd.selectFirst("a")?.attr("href").orEmpty()
                    val link = LINK.find(href) ?: throw XcapeHongdaeCrawlException("가능 칸에 예약 링크가 없다: $name $date $label")
                    if (link.groupValues[1] != id || link.groupValues[2] != date.toString()) {
                        throw XcapeHongdaeCrawlException("회차 링크의 테마·날짜가 다르다: $name($id) $date — ${link.value}")
                    }
                    Slot(time, available = true)
                }
                dd.hasClass("state2") -> {
                    val state = dd.selectFirst("span")?.text()?.trim()
                    if (state != BOOKED && state != OUT_OF_WINDOW) throw XcapeHongdaeCrawlException("모르는 상태 글자: $name $date $label '$state'")
                    Slot(time, available = false)
                }
                else -> throw XcapeHongdaeCrawlException("모르는 회차 칸: $name $date ${dd.className()}")
            }
        }

        val infoText = info?.text().orEmpty()
        return ThemeSchedule(
            externalId = id,
            themeName = name,
            posterUrl = dl.selectFirst("dt img")?.absUrl("src")?.ifBlank { null },
            genre = null,
            capacity = CAPACITY.find(infoText)?.groupValues?.get(1),
            runningMinutes = null,
            horrorLevel = null,
            difficulty = info?.selectFirst("img[src*=/star/]")?.attr("src")?.let { STAR.find(it)?.groupValues?.get(1)?.toDoubleOrNull() },
            slots = slots,
        )
    }

    companion object {
        private const val BOOKED = "예약완료"
        private const val OUT_OF_WINDOW = "예약불가"
        private val LINK = Regex("""[?&]g=([0-9]+)&d=([0-9-]{10})""")
        private val CAPACITY = Regex("""인원\s*([0-9]+\s*~\s*[0-9]+명)""")
        private val STAR = Regex("""/star/([0-9.]+)\.png""")
        private val MIDNIGHT_OR_LATER = Regex("""2[4-9]:[0-5][0-9]""")
    }
}

/** 날짜 하나 = 요청 하나 (D15) */
@Component
class XcapeHongdaeAdapter(private val crawler: XcapeHongdaeCrawler) : StoreAdapter {

    override val host = XcapeHongdae.HOST
    override val brand = XcapeHongdae.BRAND
    override val branches = listOf(XcapeHongdae.storeRef)

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(XcapeHongdae.OPEN_DAYS).map { date ->
            FetchUnit("${XcapeHongdae.BRAND} ${XcapeHongdae.BRANCH_NAME} $date", XcapeHongdae.KEY, date) { crawler.fetch(date) }
        }
}
