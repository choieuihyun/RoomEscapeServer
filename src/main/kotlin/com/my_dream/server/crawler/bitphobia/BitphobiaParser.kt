package com.my_dream.server.crawler.bitphobia

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
 * 신비웹 계열인데 **포인트나인과 세대가 다르다.** 같은 `layout/res/home.php` 인데
 * 안쪽 마크업이 전혀 다르다 — 그래서 파서를 공유하지 않는다.
 *
 * ```
 * 포인트나인   div.theme_box  h3.h3_theme        ul.reserve_Time li a[href]
 * 비트포비아   div.box        p.tit              div.time_box li.sale a[href]
 * ```
 *
 * **사이트가 주는 게 이름·포스터·테마 id 뿐이다.** 장르·인원·소요시간·공포도·난이도가
 * 페이지에 아예 없다 — 0 이 아니라 **모르는 것**이라 전부 null 로 둔다
 * (키이스케이프가 인원을 null 로 두는 것과 같다).
 */
@Component
class BitphobiaParser {

    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(html: String): ParsedPage {
        val doc = Jsoup.parse(html, BASE_URI)
        return ParsedPage(
            renderedDate = doc.selectFirst("input[name=rev_days]")?.attr("value")?.toLocalDateOrNull(),
            // 사이트가 "일주일 치" 라고 **글로** 밝히지만 값으로 주지는 않는다.
            // 문구를 긁어 숫자로 만들면 문구가 바뀔 때 조용히 틀린다 — 지점 openDays 에 적어 둔다
            reservationRangeDays = null,
            themes = doc.select("div.thm_box div.box").mapNotNull { it.toThemeSchedule() },
        )
    }

    /**
     * 응답이 어느 지점인지 되읽는다. `<option value="1" selected>던전101</option>`
     *
     * 포인트나인과 같은 표준 `selected` 다. 지점이 아홉이라 이게 없으면
     * `s_zizum` 이 무시돼도 **아홉 지점이 전부 던전101 데이터로 덮인다.**
     */
    fun renderedBranchId(html: String): Int? =
        Jsoup.parse(html).selectFirst("select[name=s_zizum] option[selected]")
            ?.attr("value")?.trim()?.toIntOrNull()

    private fun Element.toThemeSchedule(): ThemeSchedule? {
        val name = selectFirst("p.tit")?.text()?.trim().orEmpty()
        if (name.isBlank()) return null
        return ThemeSchedule(
            // `<a href="javascript:_fun_theme_view('1')">` 의 숫자.
            // **회차의 crypt_data 는 테마 id 가 아니다** — 암호화 덩어리라 안을 못 보고,
            // 매번 값이 달라질 수도 있다. 저장하지 않는다
            externalId = themeId(),
            themeName = name,
            posterUrl = selectFirst(".img_wrap img")?.absUrl("src")?.ifBlank { null },
            // 아래 다섯은 사이트가 아예 안 준다. 0 이 아니라 모르는 것이다
            genre = null,
            capacity = null,
            runningMinutes = null,
            horrorLevel = null,
            difficulty = null,
            slots = slots(),
        )
    }

    private fun Element.themeId(): String? =
        selectFirst(".img_wrap a")?.attr("href")
            ?.let { THEME_ID.find(it)?.groupValues?.get(1) }
            ?: run {
                // id 를 못 읽으면 ScheduleSyncService 가 이름을 키로 쓴다. 이름이 바뀌면
                // 다른 테마가 되어 이력이 끊기므로, 조용히 넘어가지 않고 알린다
                log.warn("테마 id 를 못 읽었다 — \"{}\". 이름을 키로 쓴다. 파서 확인 필요", selectFirst("p.tit")?.text())
                null
            }

    /**
     * 예약 여부는 **`href` 존재**로 본다.
     *
     * ```html
     * <li class="sale">      <a href="…crypt_data=…"><span>SALE</span>09:55</a></li>   가능
     * <li class="dead sale"> <a><span>SALE</span>10:45</a></li>                        매진
     * ```
     *
     * ⚠️ **`class="sale"` 은 판정 근거가 아니다.** 매진 회차에도 `sale` 이 붙어 있다
     * (`dead sale`). 세일 배지지 예약 가능 표시가 아니다 — 이름만 보고 쓰면 **전 회차가
     * 가능으로 읽혀 감시자 전원에게 헛알림이 나간다.**
     *
     * `dead` 클래스로도 갈리지만 근거는 `href` 다. 예약 폼으로 가는 링크는 **실제로
     * 예약이 될 때만 생긴다** — 클래스 이름은 디자인 사정으로 바뀌어도 링크는 안 바뀐다.
     *
     * 시각은 `<a>` 텍스트에서 `<span>SALE</span>` 을 뺀 나머지다.
     */
    private fun Element.slots(): List<Slot> =
        select("div.time_box li").mapNotNull { li ->
            val link = li.selectFirst("a") ?: return@mapNotNull null
            val time = link.ownText().trim().toLocalTimeOrNull() ?: return@mapNotNull null
            Slot(time = time, available = link.hasAttr("href") && link.attr("href").isNotBlank())
        }

    private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(trim()) }.getOrNull()

    private fun String.toLocalTimeOrNull(): LocalTime? = runCatching { LocalTime.parse(trim()) }.getOrNull()

    companion object {
        /** 포스터가 `/file/theme/1/1_….png` 로 루트 상대경로다 */
        private const val BASE_URI = "https://" + BitphobiaBranch.HOST + "/layout/res/"
        private val THEME_ID = Regex("_fun_theme_view\\('(\\d+)'\\)")
    }
}
