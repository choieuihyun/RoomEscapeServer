package com.my_dream.server.crawler.jigubyeol

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
 * 래빗홀·플레이33과 **같은 예약 솔루션**이다. `/reservation?branch=&date=` 관례도,
 * `eveReservationButton` · `<label>예약가능</label>` 마크업도 같다.
 *
 * **그래도 파서를 공유하지 않는다.** 안쪽 클래스명이 다르다 —
 * 래빗홀의 `.res-item-info h2` · `.res-item-image img` · `.res-item-step` 이
 * 여기서는 `.res-item-grp1 h2` · `figure img` 이고, 난이도는 **클래스가 아니라 표의 글자**다.
 * 지금 억지로 합치면 한쪽 사이트가 바뀔 때 양쪽이 같이 깨진다.
 */
@Component
class JigubyeolParser {

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

    /**
     * 응답이 **어느 지점인지** 되읽는다.
     *
     * ```html
     * <select class="bs-bb" name="branch" value="2">
     * ```
     *
     * `<select>` 에 `value` 속성은 표준이 아닌데 이 사이트는 요청한 값을 여기에 되돌려준다.
     * **날짜만 대조하던 래빗홀보다 한 겹 더 볼 수 있는 자리다** — 지점이 셋이라
     * `branch` 가 무시되면 세 지점이 전부 같은 데이터로 덮여도 아무도 모른다.
     *
     * ⚠️ **한계:** 이건 "우리가 보낸 값이 서버에 닿았다" 는 증거지 "그 지점으로 응답했다" 는 증거가 아니다.
     * 서버가 파라미터를 되돌려주기만 하고 무시할 수도 있다. 실제로 적용된다는 것은
     * 지점별로 테마 목록이 다르다는 것으로 손으로 확인했다(7 / 6 / 8개).
     * 그 확인은 픽스처 두 장에 박혀 있다.
     */
    fun renderedBranchId(html: String): Int? =
        Jsoup.parse(html).selectFirst("select[name=branch]")?.attr("value")?.trim()?.toIntOrNull()

    private fun Element.toThemeSchedule(themeIds: Map<String, String>): ThemeSchedule {
        val info = infoTable()
        val name = selectFirst(".res-item-grp1 h2")?.text()?.trim().orEmpty()
        return ThemeSchedule(
            externalId = themeIds[name],
            themeName = name,
            posterUrl = selectFirst("figure img")?.attr("src"),
            genre = info["장르"],
            capacity = info["인원"],
            // `80분` → 80
            runningMinutes = info["시간"]?.digitsOrNull(),
            horrorLevel = info["공포"]?.toRatingOrNull(),
            difficulty = info["난이도"]?.toRatingOrNull(),
            slots = slots(),
        )
    }

    private fun Element.infoTable(): Map<String, String> =
        select("table tr").mapNotNull { row ->
            val label = row.selectFirst("th")?.text() ?: return@mapNotNull null
            val value = row.selectFirst("td")?.text() ?: return@mapNotNull null
            label to value
        }.toMap()

    /**
     * 공포도·난이도. **여기는 글자로 온다** — 래빗홀·플레이33의 `sizeN` 클래스가 아니다.
     *
     * 그래서 D10 의 "두 자리는 반 칸(`size35` → 3.5)" 규칙을 **여기 가져오면 안 된다.**
     * 이 사이트는 `3.5` 를 그대로 적어 주므로 그냥 실수로 읽는다.
     * 규칙을 옮겨 붙이면 `35` 로 읽히는 자리다.
     *
     * `없음` 은 결측이 아니라 **"공포 없음" 이라는 값**이라 0 으로 읽는다.
     * 행 자체가 빠진 것(=사이트가 안 밝힘)과 구분해야 해서 null 로 두지 않는다.
     */
    private fun String.toRatingOrNull(): Double? {
        val text = trim()
        if (text == NONE) return 0.0
        val value = text.toDoubleOrNull()
        if (value == null) {
            // 사이트가 `조금`, `보통` 같은 말을 쓰기 시작하면 여기로 온다.
            // 숫자를 지어내지 않고 모른다고 둔다 — 없는 정보를 만들면 화면이 조용히 거짓말을 한다
            log.warn("공포도·난이도를 숫자로 못 읽었다 — \"{}\". 비워 둔다. 파서 확인 필요", text)
        }
        return value
    }

    /**
     * 예약 여부는 `<button>` 안의 `<label>` **문구**다. 래빗홀과 같다.
     *
     * ```html
     * <button type="button"><label>예약불가</label><span class="ff-bhs">10:40</span></button>
     * <button class="active1 eveReservationButton"><label>예약가능</label><span>17:30</span></button>
     * ```
     *
     * **왜 문구로 보나 —** 이 계열은 매진 버튼에도 `disabled` 가 없어서 구조로 못 가른다.
     * 플레이33은 `disabled` 가 있어 그쪽을 보고(D1), 포인트나인은 `href` 유무로 보는데,
     * 여기는 남는 게 문구뿐이다.
     *
     * **모르는 문구는 "불가" 로 본다.** 틀려도 안전한 쪽이다 —
     * 잘못 "가능" 으로 읽으면 감시 걸어둔 사람 전원에게 헛알림이 나가고,
     * 반대로 틀리면 알림이 안 갈 뿐이다. 래빗홀에서 실제로 이 경고가 오독을 잡아냈다.
     */
    private fun Element.slots(): List<Slot> =
        select("ul.res-times li button").mapNotNull { button ->
            val time = button.selectFirst("span")?.text()?.toLocalTimeOrNull() ?: return@mapNotNull null
            val label = button.selectFirst("label")?.text()?.trim()
            val available = when (label) {
                AVAILABLE -> true
                SOLD_OUT -> false
                null -> true
                else -> {
                    log.warn("모르는 예약 상태 문구 — \"{}\". 불가로 처리한다. 파서 확인 필요", label)
                    false
                }
            }
            Slot(time = time, available = available)
        }

    private fun String.digitsOrNull(): Int? = filter { it.isDigit() }.toIntOrNull()

    private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

    private fun String.toLocalTimeOrNull(): LocalTime? = runCatching { LocalTime.parse(trim()) }.getOrNull()

    companion object {
        private const val SOLD_OUT = "예약불가"
        private const val AVAILABLE = "예약가능"
        private const val NONE = "없음"
    }
}
