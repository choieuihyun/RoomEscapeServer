package com.my_dream.server.crawler.seoulescape

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
 * **서울이스케이프룸** 홍대점 (`www.seoul-escape.com` · Laravel 자체 · 2026-10-22 조사).
 *
 * ```
 * GET /reservation?branch=1&theme=&date=YYYY-MM-DD   → 지점 하루치 전 테마
 *   <section class="res-item"> <img alt="오시리스"> <th>지점</th><td>홍대점</td> …
 *     <button class="active1 eveReservationButton"><label>예약가능</label><span>11:10</span>   ← 가능
 *     <button type="button"><label>예약불가</label><span>12:20</span>                         ← 불가
 * ```
 *
 * 🕳️ **틀린 `branch`(2 · 3 · 99)와 빠진 `branch` 는 조용히 홍대(1)로 돌아간다.** select 는 보낸 값을 돌려줄 뿐이다 —
 * 그래서 테마 칸의 **"지점" 글자**로 되읽는다(전 회차 불가인 날에도 있다). 가능 회차의 hidden JSON 에도 `branch` 가 있지만
 * 매진인 날엔 없어서 못 쓴다.
 *
 * 창은 오늘~+14 (화면 maxDate · 10:1x 실측). 그 밖은 **302 로 다른 날짜에 보낸다** — 우리 Client 는 리다이렉트를 안 따라가서(D39)
 * 빈 본문이 오고 예외로 끊긴다. 플레이방탈은 이 지점을 "홍대 2호점" 이라 부르지만 사이트엔 "홍대점" 하나뿐이라 사이트 이름을 쓴다.
 */
object SeoulEscape {
    const val BRAND = "서울이스케이프룸"
    const val HOST = "www.seoul-escape.com"
    const val BRANCH = 1
    const val BRANCH_NAME = "홍대점"
    const val KEY = "seoulescape-hongdae"
    const val OPEN_DAYS = 15

    val storeRef = StoreRef(key = KEY, brand = BRAND, branchName = BRANCH_NAME)
}

class SeoulEscapeCrawlException(message: String) : RuntimeException(message)

@Component
class SeoulEscapeCrawler(private val rateLimiter: HostRateLimiter) {

    private val client by lazy { CrawlHttp.client("https://${SeoulEscape.HOST}") }

    fun fetch(date: LocalDate): DaySchedule = parse(date, request(date))

    private fun request(date: LocalDate): String =
        rateLimiter.throttled(SeoulEscape.HOST) {
            client.get()
                .uri { it.path("/reservation").queryParam("branch", SeoulEscape.BRANCH).queryParam("theme", "").queryParam("date", date).build() }
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    /** 테스트가 픽스처로 부른다 */
    fun parse(date: LocalDate, html: String): DaySchedule {
        val doc = Jsoup.parse(html, "https://${SeoulEscape.HOST}/")

        val shown = doc.selectFirst("input[name=date]")?.attr("value")
        if (shown != date.toString()) {
            throw SeoulEscapeCrawlException("요청한 날짜와 렌더된 날짜가 다름: 요청=$date 응답=$shown (창 밖이면 302 라 빈 본문이다)")
        }

        // 드롭다운이 테마 번호의 출처다 — 가능 회차 JSON 에도 번호가 있지만 매진인 날엔 없다
        val ids = doc.select("select[name=theme] option").filter { it.attr("value").isNotBlank() }
            .associate { it.text().trim() to it.attr("value") }

        val themes = doc.select("section.res-item").map { section -> theme(section, ids, date) }
        if (themes.isEmpty() || themes.all { it.slots.isEmpty() }) {
            throw SeoulEscapeCrawlException("회차가 하나도 없다 — 마크업이 바뀌었다: $date")
        }
        return DaySchedule(SeoulEscape.storeRef, date, null, SeoulEscape.OPEN_DAYS, themes)
    }

    private fun theme(section: Element, ids: Map<String, String>, date: LocalDate): ThemeSchedule {
        val name = section.selectFirst("h3")?.text()?.trim().orEmpty()
            .ifBlank { section.selectFirst("figure img")?.attr("alt")?.trim().orEmpty() }
        val row = section.select("tr").associate { tr -> tr.selectFirst("th")?.text()?.trim().orEmpty() to tr.selectFirst("td") }

        val branch = row["지점"]?.text()?.trim()
        if (branch != SeoulEscape.BRANCH_NAME) {
            throw SeoulEscapeCrawlException("요청한 지점과 응답 지점이 다름: 요청=${SeoulEscape.BRANCH_NAME} 응답=$branch ($name $date)")
        }
        val id = ids[name] ?: throw SeoulEscapeCrawlException("드롭다운에 없는 테마: '$name' $date")

        val slots = section.select("ul.res-times button").map { button ->
            val label = button.selectFirst("span")?.text()?.trim().orEmpty()
            val time = runCatching { LocalTime.parse(label.padStart(5, '0')) }.getOrNull()
                ?: throw SeoulEscapeCrawlException("시각을 못 읽었다: $name $date '$label'")
            // 클래스와 숨은 예약 정보가 **둘 다** 있어야 가능이다. 글자("예약가능")로 보지 않는다
            val open = button.hasClass("eveReservationButton") && button.selectFirst("div.eveHiddenData") != null
            Slot(time, available = open)
        }

        return ThemeSchedule(
            externalId = id,
            themeName = name,
            posterUrl = section.selectFirst("figure img")?.absUrl("src")?.ifBlank { null },
            genre = row["장르"]?.text()?.trim()?.ifBlank { null },
            capacity = row["참여인원"]?.text()?.trim()?.ifBlank { null }?.let { if (it.endsWith("명")) it else "${it}명" },
            runningMinutes = row["소요시간"]?.text()?.let { MINUTES.find(it)?.groupValues?.get(1)?.toInt() },
            horrorLevel = row["공포"]?.let(::level),
            difficulty = row["난이도"]?.let(::level),
            slots = slots,
        )
    }

    /** `res-item-step size25` = 2.5 · `size2` = 2.0 — 별 반 개 단위다(D10). 모르면 null */
    private fun level(td: Element): Double? {
        val digits = td.selectFirst("div.res-item-step")?.classNames()?.firstNotNullOfOrNull { SIZE.matchEntire(it)?.groupValues?.get(1) }
            ?: return null
        return if (digits.length == 2) digits[0].digitToInt() + digits[1].digitToInt() / 10.0 else digits.toDouble()
    }

    companion object {
        private val MINUTES = Regex("""([0-9]+)\s*분""")
        private val SIZE = Regex("""size([0-9]{1,2})""")
    }
}

/** 지점 × 날짜 = 요청 하나 (D15). 테마가 하나라도 지점 요청이다 */
@Component
class SeoulEscapeAdapter(private val crawler: SeoulEscapeCrawler) : StoreAdapter {

    override val host = SeoulEscape.HOST
    override val brand = SeoulEscape.BRAND
    override val branches = listOf(SeoulEscape.storeRef)

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(SeoulEscape.OPEN_DAYS).map { date ->
            FetchUnit("${SeoulEscape.BRAND} ${SeoulEscape.BRANCH_NAME} $date", SeoulEscape.KEY, date) { crawler.fetch(date) }
        }
}
