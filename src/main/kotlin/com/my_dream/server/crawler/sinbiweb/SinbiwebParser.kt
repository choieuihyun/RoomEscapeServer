package com.my_dream.server.crawler.sinbiweb

import com.my_dream.server.crawler.ParsedPage
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.ThemeSchedule
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime

/** 파서가 읽은 날것 + 신비웹만의 신호 둘. 아직 검증 전이다 */
data class SinbiwebPage(
    val page: ParsedPage,
    /** 응답이 밝힌 지점 번호. 사이트 설정에 따라 select · hidden 에서 읽는다. 없으면 null */
    val renderedZizum: Int?,
    /**
     * `<div class='reserve_info'>…</div>` 에 **사이트가 직접 "그날은 회차가 없다" 고 말했다** — 범위 밖이거나 휴무일.
     * 테마가 0개일 때 이게 있으면 정상이고, 없으면 틀린 요청(없는 지점)이거나 파서 고장이다 (D41).
     */
    val saysNotOpen: Boolean,
)

/**
 * 신비웹 GET 세대 공통 파서 (아키텍처 D41). 다이아에그 파서에서 출발했고, 강남 4곳 조사에서 갈린 자리만 바꿨다.
 */
@Component
class SinbiwebParser {

    fun parse(html: String, site: SinbiwebSite): SinbiwebPage {
        val doc = Jsoup.parse(html, "${site.baseUrl}/layout/res/")
        val themeIds = doc.themeIdsByName()
        return SinbiwebPage(
            page = ParsedPage(
                renderedDate = doc.selectFirst("input[name=rev_days]")?.attr("value")?.toLocalDateOrNull(),
                // 사이트가 오픈 범위를 화면에 밝히지 않는다. openDays 는 우리 실측이다 — 지어내지 않는다
                reservationRangeDays = null,
                themes = doc.select("div.theme_box").map { it.toThemeSchedule(themeIds) },
            ),
            renderedZizum = when (site.branchReadback) {
                BranchReadback.NONE -> null
                BranchReadback.HIDDEN -> doc.selectFirst("input[name=s_zizum]")?.attr("value")?.trim()?.toIntOrNull()
                BranchReadback.SELECT ->
                    doc.selectFirst("select[name=s_zizum] option[selected]")?.attr("value")?.trim()?.toIntOrNull()
            },
            saysNotOpen = doc.select(".reserve_info").any { info -> NOT_OPEN.any { it in info.text() } },
        )
    }

    /**
     * 드롭다운의 **사이트 테마 번호**. 이름이 바뀌어도 이력이 안 끊기게 이걸 키로 쓴다.
     * 황금열쇠처럼 드롭다운이 없는 매장은 빈 지도다 — 그때는 이름이 키가 된다 (ScheduleSyncService).
     */
    private fun Document.themeIdsByName(): Map<String, String> =
        select("select[name=s_theme_num] option[value~=^[0-9]+$]")
            .associate { squash(it.text()) to it.attr("value").trim() }

    private fun Element.toThemeSchedule(themeIds: Map<String, String>): ThemeSchedule {
        // wholeText — text() 는 공백을 하나로 합쳐서 장르를 가르는 "공백 두 칸" 이 사라진다
        val (name, genre) = splitNameAndGenre(selectFirst("h3.h3_theme")?.wholeText().orEmpty())
        val meta = select(".theme_div span").joinToString(" ") { it.text() }
        return ThemeSchedule(
            // 드롭다운이 있는데 못 맞추면 이름이 키가 된다. 그 경우는 SinbiwebParserTest 가 매장마다 막는다
            externalId = themeIds[squash(name)],
            themeName = name,
            posterUrl = selectFirst(".theme_pic img")?.absUrl("src")?.ifBlank { null },
            genre = genre,
            capacity = CAPACITY.find(meta)?.groupValues?.get(1)?.trim(),
            runningMinutes = RUNNING.find(meta)?.groupValues?.get(1)?.toIntOrNull(),
            horrorLevel = null,
            difficulty = select(".level_img img").size.takeIf { it > 0 }?.toDouble(),
            slots = slots(),
        )
    }

    /**
     * 예약 여부는 **`href` 존재**로 본다. 문구가 아니다 — 포인트나인·다이아에그와 같다.
     *
     * ```html
     * <a class="end"><span class="time">17:15 </span><span class="impossible">예약마감</span></a>
     * <a href="home.php?go=rev.make.input&rev_days=…&theme_time_num=76"><span class="time">10:20 </span>…</a>
     * ```
     *
     * 문구로 보면 `예약마감` 이 `마감` 으로만 바뀌어도 전 회차가 가능으로 읽혀 헛알림이 쏟아진다.
     * `href` 로 보면 마크업이 바뀔 때 전 회차가 **매진**으로 읽혀 알림이 멎을 뿐이다 — 실패 방향이 다르다.
     */
    private fun Element.slots(): List<Slot> =
        select("ul.reserve_Time li a").mapNotNull { link ->
            val time = link.selectFirst("span.time")?.text()?.toLocalTimeOrNull() ?: return@mapNotNull null
            Slot(time = time, available = link.attr("href").isNotBlank())
        }

    companion object {
        /**
         * "그날 회차가 없다" 는 **답**. 모르는 문구는 예외로 둔다 — 그래야 새 문구가 드러난다 (키이스케이프 NOT_OPEN 과 같은 방식).
         *
         * - `예약가능일이 아닙니다` — 창 밖 (2026-10-21 GET 세대 넷 공통)
         * - `지점휴무일 입니다` — 정기 휴무. **배포 첫날 Studio ESC 월요일에 실패로 잡혀 알았다** — 조사한 날이 수요일이었다
         */
        val NOT_OPEN = listOf("예약가능일이 아닙니다", "휴무일")

        /** `인원 : 2~6명` · `인원 : 1~6명` */
        private val CAPACITY = Regex("인원\\s*:\\s*([0-9]+\\s*~\\s*[0-9]+\\s*명?)")

        /** `시간 : 75` · `시간 : 60분` · `시간 : 60분 + α` — 단위가 매장마다 붙었다 안 붙었다 한다 */
        private val RUNNING = Regex("시간\\s*:\\s*([0-9]+)")

        /** 이름과 장르 사이. 템플릿이 공백 **두 칸**을 넣는다 — 이름 안의 영문 부제는 한 칸이라 안 걸린다 */
        private val GENRE_TAIL = Regex("^(.*?)[\\s\\u00a0]{2,}\\((.*)\\)[\\s\\u00a0]*$", RegexOption.DOT_MATCHES_ALL)

        /**
         * `고인  (미스터리)` → 고인 · 미스터리
         * `The 대기실  (미니테마(예약방법 공지사항 참조))` → The 대기실 · 미니테마(예약방법 공지사항 참조)
         * `검은마법사 (The Dark Enchanter)  ` → 검은마법사 (The Dark Enchanter) · 없음
         *
         * ⚠️ **마지막 `(` 에서 자르면 안 된다**(포인트나인 방식). 돌로의 겹친 괄호가 `The 대기실  (미니테마` 가 된다.
         * **괄호를 다 장르로 봐도 안 된다.** Studio ESC 의 영문 부제가 장르가 된다.
         */
        fun splitNameAndGenre(raw: String): Pair<String, String?> {
            val m = GENRE_TAIL.find(raw) ?: return squash(raw) to null
            return squash(m.groupValues[1]) to squash(m.groupValues[2]).ifBlank { null }
        }

        private fun squash(s: String) = s.replace(' ', ' ').trim().replace(Regex("\\s+"), " ")

        private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

        private fun String.toLocalTimeOrNull(): LocalTime? = runCatching { LocalTime.parse(trim()) }.getOrNull()
    }
}
