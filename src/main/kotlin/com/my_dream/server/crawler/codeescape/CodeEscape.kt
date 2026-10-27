package com.my_dream.server.crawler.codeescape

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
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * **코드이스케이프** 가로수길점 (`www.code-escape-garosu.com` · 신비웹 **옛 테이블 세대** · 2026-10-27 조사).
 *
 * ```
 * GET /web/home.php?rev_days=YYYY-MM-DD&person=3&theme_num=&zizum_num=1&mid=7&go=rev_make   → 지점 하루치 전 테마
 *   <select name=theme_num> <option value='14'>(수)산업스파이 …                     ← 테마 번호의 출처
 *   <table class=list> <tr><td>11:15 </td><td class=left>재즈인더레인 (Jazz in the Rain)</td><td>66,000원</td>
 *     <td><a href=javascript:fun_rev_next('103','')><img src='../img/sub/rev_y.png'></a></td>   ← 가능
 *     <td><img src='../img/sub/rev_n.png'></td>                                                 ← 불가
 * ```
 *
 * 우리가 붙인 신비웹 세대(`/layout/res/home.php?go=rev.make` · `ul.reserve_Time`)와 마크업이 달라 따로 읽는다.
 * `mid` 는 메뉴 번호, `person` 은 금액만 바꾼다(ⓖ — 둘 다 회차와 무관하다는 걸 실측했다).
 *
 * 🕳️ **창 밖 · 어제 · 틀린 날짜가 전부 "드롭다운은 있고 회차 0행 · 문구 없음"** 이다. 빈 표를 "결과 없음" 으로 저장하면 안 된다 —
 * 창(오늘~+13) 안인데 0행이면 실패로 끊는다. 오늘만은 지난 회차가 목록에서 빠지므로 밤에는 0행이 정상이다.
 * 🕳️ **불가를 `rev_n` 부분 문자열로 찾으면 `fun_rev_next` 에도 걸려 전 회차가 불가로 읽힌다**(조사 중 실제로 틀렸다).
 * 가능은 예약 링크, 불가는 `rev_n.png` 이미지로 따로 보고, 둘 다 아니면 모르는 모양이라 끊는다.
 * 없는 지점(`zizum_num=2`)은 드롭다운까지 0개다. hidden `zizum_num` 은 템플릿 값이라 되읽기로 못 쓴다.
 * https 는 안 쓴다 — 페이지 스스로 http 로 돌리는 스크립트를 두었다(비활성 주석).
 */
object CodeEscape {
    const val BRAND = "코드이스케이프"
    const val HOST = "www.code-escape-garosu.com"
    const val BRANCH_NAME = "가로수길점"
    const val KEY = "codeescape-garosu"
    /** 오늘~+13 (2026-10-27 11시 실측 · +14 는 0행) */
    const val OPEN_DAYS = 14

    val storeRef = StoreRef(key = KEY, brand = BRAND, branchName = BRANCH_NAME)
}

class CodeEscapeCrawlException(message: String) : RuntimeException(message)

@Component
class CodeEscapeCrawler(private val rateLimiter: HostRateLimiter) {

    private val client by lazy { CrawlHttp.client("http://${CodeEscape.HOST}") }

    /** 테스트가 "오늘" 을 고정하려고 바꾼다 */
    internal var clock: Clock = Clock.system(ZoneId.of("Asia/Seoul"))

    fun fetch(date: LocalDate): DaySchedule = parse(date, request(date))

    private fun request(date: LocalDate): String =
        rateLimiter.throttled(CodeEscape.HOST) {
            client.get()
                .uri {
                    it.path("/web/home.php").queryParam("rev_days", date).queryParam("person", 3).queryParam("theme_num", "")
                        .queryParam("zizum_num", 1).queryParam("mid", 7).queryParam("go", "rev_make").build()
                }
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    /** 테스트가 픽스처로 부른다 */
    fun parse(date: LocalDate, html: String): DaySchedule {
        val doc = Jsoup.parse(html, "http://${CodeEscape.HOST}/web/")

        val shown = doc.selectFirst("input[name=rev_days]")?.attr("value")
        if (shown != date.toString()) throw CodeEscapeCrawlException("요청한 날짜와 렌더된 날짜가 다름: 요청=$date 응답=$shown")

        val ids = doc.select("select[name=theme_num] option").filter { it.attr("value").isNotBlank() }
            .associate { it.text().trim() to it.attr("value") }
        if (ids.isEmpty()) throw CodeEscapeCrawlException("테마 드롭다운이 비었다 — 지점 값이 틀렸거나 마크업이 바뀌었다: $date")

        val rows = doc.select("table.list tr").filter { it.select("td").size >= 4 }
        val today = LocalDate.now(clock)
        if (rows.isEmpty() && date != today) {
            throw CodeEscapeCrawlException("창 안인데 회차가 0행이다 — 창 밖 · 휴무 · 틀린 날짜가 같은 모양이라 결과 없음으로 보지 않는다: $date")
        }

        val byTheme = rows.groupBy({ it.select("td")[1].text().trim() }) { tr ->
            val cells = tr.select("td")
            val label = cells[0].text().trim()
            val time = runCatching { LocalTime.parse(label.padStart(5, '0')) }.getOrNull()
                ?: throw CodeEscapeCrawlException("시각을 못 읽었다: $date '$label'")
            val booking = cells[3]
            val open = booking.selectFirst("a[href^=javascript:fun_rev_next]") != null
            val closed = booking.selectFirst("img[src$=rev_n.png]") != null
            if (open == closed) throw CodeEscapeCrawlException("가능도 불가도 아닌 칸: $date $label '${booking.html()}'")
            Slot(time, available = open)
        }
        byTheme.keys.firstOrNull { it !in ids }?.let { throw CodeEscapeCrawlException("드롭다운에 없는 테마: '$it' $date") }

        val themes = ids.map { (name, id) ->
            ThemeSchedule(
                externalId = id,
                themeName = name,
                posterUrl = null,
                genre = null,
                capacity = null,
                runningMinutes = null,
                horrorLevel = null,
                difficulty = null,
                slots = byTheme[name].orEmpty().sortedBy { it.time },
            )
        }
        return DaySchedule(CodeEscape.storeRef, date, null, CodeEscape.OPEN_DAYS, themes)
    }
}

/** 지점 × 날짜 = 요청 하나 (D15) */
@Component
class CodeEscapeAdapter(private val crawler: CodeEscapeCrawler) : StoreAdapter {

    override val host = CodeEscape.HOST
    override val brand = CodeEscape.BRAND
    override val branches = listOf(CodeEscape.storeRef)

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(CodeEscape.OPEN_DAYS).map { date ->
            FetchUnit("${CodeEscape.BRAND} ${CodeEscape.BRANCH_NAME} $date", CodeEscape.KEY, date) { crawler.fetch(date) }
        }
}
