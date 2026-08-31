package com.my_dream.server.crawler.pointnine

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
 * 신비웹 계열이다 (`layout/res/home.php?go=rev.*`). **비트포비아·제로월드와 같은 업체지만
 * 세대가 달라 마크업이 다르다** — 합치지 않는다 ([작업명세서.md](작업명세서.md) M5 방침).
 */
@Component
class PointNineParser {

    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(html: String): ParsedPage {
        val doc = Jsoup.parse(html, BASE_URI)
        return ParsedPage(
            renderedDate = doc.selectFirst("input[name=rev_days]")?.attr("value")?.toLocalDateOrNull(),
            // 사이트가 오픈 범위를 밝히지 않는다. 화면 어디에도 안 적혀 있어서 모르면 null 이다.
            // **실측으로는 오늘+6일까지**(2026-08-31 기준 9/6 까지 차 있고 9/7 부터 빈다)인데,
            // 그건 우리가 재 본 값이지 사이트가 말해 준 값이 아니다. 지어내지 않는다
            reservationRangeDays = null,
            themes = doc.select("div.theme_box").map { it.toThemeSchedule() },
        )
    }

    /**
     * 응답이 **어느 지점인지** 되읽는다. `<option value='5' selected >건대점</option>`
     *
     * 지구별은 `<select>` 에 비표준 `value` 속성을 얹어 줘서 그걸 봤는데,
     * 여기는 **표준 `selected` 속성**이라 더 튼튼하다.
     *
     * ⚠️ **한계는 지구별과 같다.** 이건 "우리가 보낸 값이 서버에 닿았다" 는 증거지
     * "그 지점으로 응답했다" 는 증거가 아니다. 실제로 갈린다는 것은 지점별 테마 목록이
     * 하나도 안 겹치는 것으로 확인했다(4/3/2개). 그 확인은 픽스처 세 장에 박혀 있다.
     */
    fun renderedBranchId(html: String): Int? =
        Jsoup.parse(html).selectFirst("select[name=s_zizum] option[selected]")
            ?.attr("value")?.trim()?.toIntOrNull()

    private fun Element.toThemeSchedule(): ThemeSchedule {
        val (name, genre) = selectFirst("h3.h3_theme")?.text().orEmpty().splitNameAndGenre()
        val meta = select(".theme_div span").joinToString(" ") { it.text() }
        return ThemeSchedule(
            // 페이지 어디에도 테마 고유번호가 없다. 회차의 `theme_time_num` 은 회차 번호지
            // 테마 번호가 아니고, 포스터 파일명(`8_a.jpg`)의 숫자는 테마 ID 처럼 보이지만
            // **이미지를 다시 올리면 바뀐다** — 그걸 키로 쓰면 사진 교체가 테마를 갈아치운다.
            // null 로 두면 ScheduleSyncService 가 이름을 키로 쓴다 (그래서 이름 파싱이 중요하다)
            externalId = null,
            themeName = name,
            posterUrl = selectFirst(".theme_pic img")?.absUrl("src")?.ifBlank { null },
            genre = genre,
            capacity = meta.captureOrNull(CAPACITY),
            runningMinutes = meta.captureOrNull(RUNNING)?.toIntOrNull(),
            // 사이트가 공포도를 안 밝힌다
            horrorLevel = null,
            difficulty = difficulty(),
            slots = slots(),
        )
    }

    /**
     * `EP1 : 시간이 멈춘 마을  ()` → 이름 + 장르. **괄호 안이 비어 있을 수 있다.**
     *
     * ⚠️ **여기가 조용히 위험한 자리다.** 테마 고유번호가 없어서 **테마 이름이 곧 키**다
     * (`ScheduleSyncService` 가 `externalId ?: themeName` 을 쓴다).
     * 괄호를 안 떼고 통째로 이름으로 쓰면, 강남점이 언젠가 빈 장르를 채우는 순간
     * `EP1 : … ()` 과 `EP1 : … (추리)` 가 **서로 다른 테마가 된다** —
     * 옛 행의 이력이 끊기고 그 테마의 전이 감지가 조용히 멈춘다.
     *
     * 마지막 `(` 에서 자른다. 이름에 괄호가 있어도 장르 괄호가 항상 뒤에 있어서 안전하다.
     * 빈 괄호는 `""` 가 아니라 **null** 로 둔다 — 빈 문자열은 "장르가 없음" 이 아니라
     * "장르가 빈 문자열" 이라 화면이 빈 칸을 그린다.
     */
    private fun String.splitNameAndGenre(): Pair<String, String?> {
        val text = trim()
        if (!text.endsWith(")")) return text to null
        val open = text.lastIndexOf('(')
        if (open < 0) return text to null
        return text.substring(0, open).trim() to
            text.substring(open + 1, text.length - 1).trim().ifBlank { null }
    }

    /**
     * 난이도는 **아이콘 개수**다. 클래스도 글자도 아니다.
     *
     * ```html
     * <span class="level_img"> <img src="…/ico_level.png"/> × 4 </span>
     * ```
     *
     * **세 번째 표현 방식이다** — 래빗홀·플레이33은 `class="size35"`(D10), 지구별은 표의 글자,
     * 여기는 개수. 앞의 규칙을 옮겨 붙일 자리가 아니다.
     *
     * **센 값을 그대로 둔다. 5점 만점으로 환산하지 않는다** — 만점이 몇인지 확인한 적이 없다.
     * 분모를 지어내는 것은 D10 규칙을 잘못 옮기는 것과 같은 종류의 실수다.
     * 반 칸 아이콘은 없다(2026-08-31 기준 `ico_level.png` 한 종류뿐).
     */
    private fun Element.difficulty(): Double? {
        val count = select(".level_img img").size
        if (count == 0) {
            // 아이콘 이름이나 구조가 바뀌면 여기로 온다. 0 으로 저장하면 "쉬움" 으로 읽힌다
            log.warn("난이도 아이콘을 못 찾았다 — \"{}\". 비워 둔다. 파서 확인 필요", selectFirst("h3.h3_theme")?.text())
            return null
        }
        return count.toDouble()
    }

    /**
     * 예약 여부는 **`href` 존재**로 본다. 문구가 아니다 (아키텍처 D1 의 `disabled` 와 같은 성격).
     *
     * ```html
     * <li><a class="end"><span class="time">18:30 </span><span class="impossible">예약마감</span></a></li>
     * <li><a href="home.php?go=rev.make.input&rev_days=…&theme_time_num=1253">
     *       <span class="time">11:00 </span><span class="possible">예약가능</span></a></li>
     * ```
     *
     * **왜 문구가 아니라 링크인가 —** 예약 폼으로 가는 링크는 **실제로 예약이 될 때만 생긴다.**
     * 문구로 보면 사이트가 `예약마감` 을 `마감` 으로만 바꿔도 **전 회차가 가능으로 읽혀
     * 감시 걸어둔 사람 전원에게 헛알림이 쏟아진다.** 실패 방향이 다르다.
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
        /** 포스터가 `../../file/theme/8_a.jpg` 처럼 상대경로다. 이걸 줘야 절대 URL 로 펴진다 */
        private const val BASE_URI = "https://" + PointNineBranch.HOST + "/layout/res/"

        /**
         * `인원 : 1~4명   시간 : 70분` 한 덩어리에서 뽑는다.
         *
         * ⚠️ **단위가 테마마다 붙었다 안 붙었다 한다.** 사람이 손으로 적는 칸이라서다.
         *
         * ```
         * 강남 EP1    인원 : 1~4명    시간 : 70분
         * 건대 ALBA   인원 : 2~6명    시간 : 70      ← `분` 이 없다
         * ```
         *
         * 그래서 `분` 과 `명` 을 **선택**으로 둔다. 필수로 두면 어떤 테마는 소요시간이
         * 조용히 비고, 화면은 "정보 없음" 을 그리면서 아무도 이유를 모른다.
         * **픽스처 세 장을 넣었기 때문에 이게 드러났다** — 한 장만 뒀으면 못 봤다.
         */
        private val CAPACITY = Regex("인원\\s*:\\s*([0-9]+\\s*~\\s*[0-9]+\\s*명?)")
        private val RUNNING = Regex("시간\\s*:\\s*([0-9]+)\\s*분?")
    }
}
