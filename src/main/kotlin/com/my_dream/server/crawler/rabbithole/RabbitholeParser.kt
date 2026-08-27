package com.my_dream.server.crawler.rabbithole

import com.my_dream.server.crawler.ParsedPage
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.ThemeSchedule
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime

/**
 * 플레이33과 같은 예약 솔루션으로 보인다 — URL 관례가 같고 별점이 `sizeN cba` 다.
 * **다만 예약 여부를 읽는 법이 다르다.**
 */
@Component
class RabbitholeParser {

    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(html: String): ParsedPage {
        val doc = Jsoup.parse(html)
        val themeIds = doc.select("select[name=theme] option")
            .filter { it.attr("value").isNotBlank() }
            .associate { it.text().trim() to it.attr("value") }

        return ParsedPage(
            renderedDate = doc.selectFirst("input[name=date].mask-date")?.attr("value")?.toLocalDateOrNull(),
            // 사이트가 예약 오픈 범위를 밝히지 않는다. 모르면 null 이다
            reservationRangeDays = null,
            themes = doc.select("section.res-item").map { it.toThemeSchedule(themeIds) },
        )
    }

    private fun Element.toThemeSchedule(themeIds: Map<String, String>): ThemeSchedule {
        val info = infoTable()
        val name = selectFirst(".res-item-info h2")?.text()?.trim().orEmpty()
        return ThemeSchedule(
            externalId = themeIds[name],
            themeName = name,
            posterUrl = selectFirst(".res-item-image img")?.attr("src"),
            genre = info["장르"],
            capacity = info["인원"],
            // `70min` 처럼 단위가 붙는다 (플레이33은 `65분`)
            runningMinutes = info["시간"]?.digitsOrNull(),
            // 사이트가 공포도를 주지 않는다
            horrorLevel = null,
            difficulty = difficulty(),
            slots = slots(),
        )
    }

    private fun Element.infoTable(): Map<String, String> =
        select(".res-item-info table tr").mapNotNull { row ->
            val label = row.selectFirst("th")?.text() ?: return@mapNotNull null
            val value = row.selectFirst("td")?.text() ?: return@mapNotNull null
            label to value
        }.toMap()

    /** 플레이33의 `resstep` 과 같은 규칙. **두 자리는 반 칸이다** (아키텍처 D10) */
    private fun Element.difficulty(): Double? =
        selectFirst(".res-item-step")
            ?.classNames()
            ?.firstOrNull { it.startsWith("size") }
            ?.removePrefix("size")
            ?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.let { when (it.length) { 1 -> it.toDouble(); 2 -> it.toDouble() / 10; else -> null } }

    /**
     * 예약 여부를 **`<label>` 이 있느냐**로 본다. 문구를 비교하지 않는다.
     *
     * ```
     * 문구 비교   "예약불가" → "마감" 으로 바뀌면 전 회차가 "가능" 으로 읽힌다   오알림 대량
     * 존재 여부   문구가 바뀌어도 label 은 있다 → 여전히 "불가"                안전한 실패
     * ```
     *
     * 최악이 "알림이 안 간다" 인 쪽을 고른다. 다만 변화를 놓치지 않게, 아는 문구가 아니면 남긴다.
     */
    private fun Element.slots(): List<Slot> =
        select("ul.res-times li button").mapNotNull { button ->
            val time = button.selectFirst("span")?.text()?.toLocalTimeOrNull() ?: return@mapNotNull null
            val label = button.selectFirst("label")?.text()?.trim()
            if (label != null && label != SOLD_OUT) {
                log.warn("모르는 예약 상태 문구 — \"{}\". 불가로 처리한다. 파서 확인 필요", label)
            }
            Slot(time = time, available = label == null)
        }

    private fun String.digitsOrNull(): Int? = filter { it.isDigit() }.toIntOrNull()

    private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

    private fun String.toLocalTimeOrNull(): LocalTime? =
        runCatching { LocalTime.parse(trim()) }.getOrNull()

    companion object {
        private const val SOLD_OUT = "예약불가"
    }
}
