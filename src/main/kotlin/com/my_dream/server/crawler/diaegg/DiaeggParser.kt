package com.my_dream.server.crawler.diaegg

import com.my_dream.server.crawler.ParsedPage
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.ThemeSchedule
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime

/**
 * 신비웹 계열이고, **마크업 세대는 포인트나인과 같다** — `theme_box` · `h3_theme` ·
 * `reserve_Time` · `<a class="end">` 가 그대로다. 그런데도 파서를 따로 두는 이유는
 * [작업명세서.md](작업명세서.md) M5 방침(각각 하드코딩한 뒤 공통점이 진짜로 남으면 그때 뽑는다)
 * 이기도 하지만, **실제로 두 곳이 다르기 때문**이다. 아래 [themeName]·[externalId] 참고.
 *
 * > 이제 신비웹이 네 곳(포인트나인·비트포비아·제로월드·다이아에그)이다.
 * > **공통 파서를 뽑을 근거는 생겼다.** 다만 그건 도는 매장 셋을 건드리는 작업이라
 * > 이번 커밋에서 같이 하지 않는다 — 작업명세서에 남긴다.
 */
@Component
class DiaeggParser {

    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(html: String): ParsedPage {
        val doc = Jsoup.parse(html, BASE_URI)
        val themeIds = doc.themeIdsByLabel()
        return ParsedPage(
            renderedDate = doc.selectFirst("input[name=rev_days]")?.attr("value")?.toLocalDateOrNull(),
            // 사이트가 오픈 범위를 화면에 밝히지 않는다. 우리가 잰 값(+4~+10)은 DiaeggBranch 에 있고,
            // 그건 **우리 실측이지 사이트가 말해 준 값이 아니다.** 지어내지 않는다
            reservationRangeDays = null,
            themes = doc.select("div.theme_box").map { it.toThemeSchedule(themeIds) },
        )
    }

    /**
     * 응답이 **어느 지점인지** 되읽는다.
     *
     * ⚠️ **포인트나인처럼 `select[name=s_zizum] option[selected]` 를 볼 수 없다.**
     * 여기는 지점이 `<select>` 가 아니라 **탭 링크**이고, 폼에는 `<input type=hidden
     * name=s_zizum value='1'>` 로 실려 있다. 셀렉터를 그대로 베꼈다면 **항상 null 이 나와서
     * 크롤러가 매 요청을 예외로 끊었을 것이다.**
     */
    fun renderedBranchId(html: String): Int? =
        Jsoup.parse(html).selectFirst("input[name=s_zizum]")?.attr("value")?.trim()?.toIntOrNull()

    /**
     * 테마 드롭다운(`s_theme_num`)에서 **사이트가 매긴 테마 번호**를 캔다.
     *
     * ```html
     * <option value='4'>[부산서면점]레드룸(RED ROOM)</option>
     * ```
     *
     * 포인트나인은 이 자리가 없어서 **테마 이름을 키로 쓴다.** 여기는 진짜 번호가 있으므로
     * 그걸 쓴다 — 이름이 바뀌어도 이력이 안 끊긴다. 포스터 파일명(`4_a.jpg`)의 숫자도
     * 같은 값처럼 보이지만 **이미지를 다시 올리면 바뀌므로** 키로 쓰지 않는다.
     *
     * 라벨을 **다듬지 않은 원문 그대로** 맞춘다 — 양쪽 다 `[지점명]` 접두사를 달고 있어서,
     * 접두사를 뗀 뒤에 맞추면 규칙이 두 곳으로 갈린다.
     */
    private fun Document.themeIdsByLabel(): Map<String, String> =
        select("select[name=s_theme_num] option[value~=^[0-9]+$]")
            .associate { it.text().trim() to it.attr("value").trim() }

    private fun Element.toThemeSchedule(themeIds: Map<String, String>): ThemeSchedule {
        val label = selectFirst("h3.h3_theme")?.text().orEmpty().trim()
        val meta = select(".theme_div span").joinToString(" ") { it.text() }
        val externalId = themeIds[label]
        if (externalId == null) {
            // 드롭다운과 카드가 어긋났다. 치명적이지는 않다 — ScheduleSyncService 가 이름을
            // 키로 쓴다. 다만 이름이 바뀌면 그때 이력이 끊기므로 눈에 띄게 남긴다
            log.warn("테마 번호를 못 찾았다 — \"{}\". 이름을 키로 쓴다. 드롭다운 확인 필요", label)
        }
        return ThemeSchedule(
            externalId = externalId,
            themeName = label.stripBranchPrefix(),
            posterUrl = selectFirst(".theme_pic img")?.absUrl("src")?.ifBlank { null },
            // 예약 페이지에는 장르가 없다. theme.list 쪽에는 있지만 **다른 요청**이라
            // 여기서 지어내지 않는다 — 한 바퀴 요청 수를 두 배로 만들 이유가 없다
            genre = null,
            capacity = meta.captureOrNull(CAPACITY),
            runningMinutes = meta.captureOrNull(RUNNING)?.toIntOrNull(),
            horrorLevel = null,
            difficulty = difficulty(),
            slots = slots(),
        )
    }

    /**
     * `[부산서면점]레드룸(RED ROOM)` → `레드룸(RED ROOM)`
     *
     * ⚠️ **괄호를 장르로 떼면 안 된다.** 포인트나인 파서는 마지막 괄호를 장르로 잘라내는데,
     * 여기서 그러면 이렇게 된다.
     *
     * ```
     * 레드룸(RED ROOM)        → 이름 "레드룸"      장르 "RED ROOM"     ✗
     * 무료한 하루(A Free Day) → 이름 "무료한 하루"  장르 "A Free Day"   ✗
     * ```
     *
     * **이 매장의 괄호는 전부 영문 제목이다** (RED ROOM · GRABBER · KEY X 5 · A Free Day ·
     * Time Cruise — 2026-09-04 기준 11테마 전수 확인). 같은 신비웹이라고 규칙을 옮겨 붙이면
     * 안 되는 자리다.
     *
     * 접두사는 **떼되 키로는 안 쓴다** — 위 [themeIdsByLabel] 이 번호를 주므로 이름이 바뀌어도
     * 이력이 안 끊긴다. 그리고 접두사가 **탭 이름과 다르다**(탭은 `다이아에그`, 접두사는
     * `부산서면점`)는 점에서 이 값을 지점 식별에 쓰면 안 된다는 것도 알 수 있다.
     */
    private fun String.stripBranchPrefix(): String {
        if (!startsWith("[")) return this
        val close = indexOf(']')
        if (close < 0) return this
        return substring(close + 1).trim()
    }

    /** 난이도는 **아이콘 개수**다 (포인트나인과 같다). 만점을 모르므로 환산하지 않는다. */
    private fun Element.difficulty(): Double? {
        val count = select(".level_img img").size
        if (count == 0) {
            log.warn("난이도 아이콘을 못 찾았다 — \"{}\". 비워 둔다", selectFirst("h3.h3_theme")?.text())
            return null
        }
        return count.toDouble()
    }

    /**
     * 예약 여부는 **`href` 존재**로 본다. 문구가 아니다.
     *
     * ```html
     * <a class="end"><span class="time">10:10 </span><span class="impossible">예약마감</span></a>
     * <a href="home.php?go=rev.make.input&rev_days=…&theme_time_num=232">
     *    <span class="time">11:10 </span><span class="possible">예약가능</span></a>
     * ```
     *
     * 문구로 보면 사이트가 `예약마감` 을 `마감` 으로만 바꿔도 **전 회차가 가능으로 읽혀
     * 감시 걸어둔 사람 전원에게 헛알림이 쏟아진다.** 실패 방향이 다르다.
     *
     * ⚠️ `possible` 이라는 낱말은 **페이지에 43번 나오는데 회차 수와 무관하다**(CSS·스크립트에도
     * 있다). 조사할 때 그걸 세다가 오픈 범위를 두 번 잘못 쟀다. 회차는 이 셀렉터로만 센다.
     */
    private fun Element.slots(): List<Slot> =
        select("ul.reserve_Time li a").mapNotNull { link ->
            val time = link.selectFirst("span.time")?.text()?.toLocalTimeOrNull() ?: return@mapNotNull null
            Slot(time = time, available = link.hasAttr("href") && link.attr("href").isNotBlank())
        }

    private fun String.captureOrNull(regex: Regex): String? =
        regex.find(this)?.groupValues?.get(1)?.trim()?.ifBlank { null }

    private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

    private fun String.toLocalTimeOrNull(): LocalTime? = runCatching { LocalTime.parse(trim()) }.getOrNull()

    companion object {
        /** 포스터가 `../../file/theme/4_a.jpg` 처럼 상대경로다. 이걸 줘야 절대 URL 로 펴진다 */
        private const val BASE_URI = "https://" + DiaeggBranch.HOST + "/layout/res/"

        /** `인원 : 2~6명   시간 : 40분`. 포인트나인처럼 단위를 **선택**으로 둔다 */
        private val CAPACITY = Regex("인원\\s*:\\s*([0-9]+\\s*~\\s*[0-9]+\\s*명?)")
        private val RUNNING = Regex("시간\\s*:\\s*([0-9]+)\\s*분?")
    }
}
