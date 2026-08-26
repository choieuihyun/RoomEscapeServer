package com.my_dream.server.crawler.play33

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime

/**
 * 예약 페이지 HTML 에서 테마별 시간표를 뽑아낸다.
 *
 * 페이지 구조:
 * ```
 * <section class="reslist">
 *   <figure class="reslist-img"><img src="포스터"></figure>
 *   <div class="reslist-text">
 *     <strong>목격자</strong>
 *     <table><tr><th>장르</th><td>드라마/스릴러</td></tr> ...</table>
 *     <div class="restimes"><ul>
 *       <li><button class="eveReservationButton">예약 가능 <span>10:35</span></button></li>
 *       <li><button disabled>예약 불가 <span>18:15</span></button></li>
 * ```
 */
@Component
class Play33Parser {

    fun parse(html: String): ParsedPage =
        Jsoup.parse(html).let { document ->
            val themeIds = document.themeIds()
            ParsedPage(
                renderedDate = document.renderedDate(),
                reservationRangeDays = document.reservationRangeDays(),
                themes = document.select("section.reslist").map { it.toThemeSchedule(themeIds) },
            )
        }

    /**
     * `<select name="theme"><option value="18">목격자</option>` 에서 이름 -> 사이트 고유 ID.
     * 이름만으로 테마를 식별하면 테마명이 바뀔 때 다른 테마로 갈라진다.
     */
    private fun Document.themeIds(): Map<String, String> =
        select("select[name=theme] option")
            .filter { it.attr("value").isNotBlank() }
            .associate { it.text().trim() to it.attr("value") }

    /** 페이지가 실제로 어떤 날짜를 렌더했는지. 요청한 날짜와 대조하는 데 쓴다. */
    private fun Document.renderedDate(): LocalDate? =
        selectFirst("input[name=date].mask-date")
            ?.attr("value")
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    /** `<input id="reservation_range_day" value="7">` — 며칠 앞까지 예약이 열리는지 */
    private fun Document.reservationRangeDays(): Int? =
        selectFirst("#reservation_range_day")?.attr("value")?.toIntOrNull()

    private fun Element.toThemeSchedule(themeIds: Map<String, String>): ThemeSchedule {
        val info = infoTable()
        val name = selectFirst(".reslist-text > strong")?.text().orEmpty()
        return ThemeSchedule(
            externalId = themeIds[name],
            themeName = name,
            posterUrl = selectFirst(".reslist-img img")?.attr("src"),
            genre = info["장르"],
            capacity = info["인원"],
            // "65분" 과 "60" 이 섞여 있어서 숫자만 남긴다
            runningMinutes = info["시간"]?.digitsOrNull(),
            horrorLevel = info["공포"]?.digitsOrNull(),
            difficulty = difficulty(),
            slots = slots(),
        )
    }

    /** `<tr><th>장르</th><td>드라마/스릴러</td></tr>` 를 라벨 -> 값 으로 */
    private fun Element.infoTable(): Map<String, String> =
        select(".reslist-text table tr").mapNotNull { row ->
            val label = row.selectFirst("th")?.text() ?: return@mapNotNull null
            val value = row.selectFirst("td")?.text() ?: return@mapNotNull null
            label to value
        }.toMap()

    /** 난이도는 별 개수가 `<div class="resstep size3 cba">` 의 sizeN 으로 들어온다 */
    private fun Element.difficulty(): Int? =
        selectFirst(".resstep")
            ?.classNames()
            ?.firstOrNull { it.startsWith("size") }
            ?.removePrefix("size")
            ?.toIntOrNull()

    /**
     * 예약 가능 여부는 `disabled` 속성 유무로 판단한다.
     * 버튼 문구("예약 가능"/"예약 불가")보다 바뀔 가능성이 낮다.
     */
    private fun Element.slots(): List<Slot> =
        select(".restimes li button").mapNotNull { button ->
            val time = button.selectFirst("span")?.text()?.toLocalTimeOrNull() ?: return@mapNotNull null
            Slot(time = time, available = !button.hasAttr("disabled"))
        }

    private fun String.toLocalTimeOrNull(): LocalTime? =
        runCatching { LocalTime.parse(trim()) }.getOrNull()

    private fun String.digitsOrNull(): Int? = filter { it.isDigit() }.toIntOrNull()
}
