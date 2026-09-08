package com.my_dream.server.crawler.playpointlab

import com.my_dream.server.crawler.ParsedPage
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.ThemeSchedule
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalTime

/**
 * 응답이 **완전한 페이지가 아니라 HTML 조각**이다 — 화면이 `$('#booking_list').html(msg)` 로
 * 그대로 꽂는다. 그래서 다른 매장 파서와 두 가지가 다르다.
 *
 * 1. **[ParsedPage.renderedDate] 가 항상 null 이다.** 조각에 날짜가 없다. 대조할 수 없다
 *    ([PlayPointLabCrawler] 에 왜 위험한지 적어 뒀다)
 * 2. **지점도 `data-room-id` 로만 알 수 있다.** `store` 도 지점명도 안 실려 온다
 *
 * ⚠️ **`/office/detail?bid=N` 의 HTML 을 긁으면 안 된다.** 거기에도 `theme-booking-card`
 * 마크업이 회차까지 그럴듯하게 들어 있는데 **통째로 `<!-- -->` 주석 안**이고, 실제
 * `#booking_list` 는 비어 있다. 주석 안 예시는 **진짜 부산 지점 테마 이름**
 * (`문앞에두고벨눌러주세요`)을 담고 있어서 더 그럴듯하다 — 그걸 파싱하면
 * **매일 똑같은 고정 시간표를 수집하면서 성공한 줄 안다.**
 */
@Component
class PlayPointLabParser {

    private val log = LoggerFactory.getLogger(javaClass)

    fun parse(html: String): ParsedPage {
        // ⚠️ 조각이라 body 파서로 읽는다. 그리고 **응답 HTML 의 따옴표가 깨져 있다** —
        // `data-room-name='스팀스파이어사가'  '>` 처럼 짝 없는 홑따옴표가 태그 끝에 붙는다.
        // Jsoup 은 관대해서 넘어가지만, 엄격한 XML 파서로 바꾸면 여기서 터진다
        val doc = Jsoup.parse(html, BASE_URI, Parser.htmlParser())
        return ParsedPage(
            // 조각에 날짜가 없다. 지어내면 크롤러의 대조가 자기 자신과 비교하게 된다
            renderedDate = null,
            reservationRangeDays = null,
            themes = doc.select("article.theme-booking-card").map { it.toThemeSchedule() },
        )
    }

    /** 응답에 실제로 들어 있는 `data-room-id` 전부. 지점 대조에 쓴다 ([PlayPointLabBranch.roomIds]). */
    fun roomIds(html: String): Set<String> =
        Jsoup.parse(html, BASE_URI, Parser.htmlParser())
            .select("article.theme-booking-card[data-room-id]")
            .mapNotNull { it.attr("data-room-id").trim().ifBlank { null } }
            .toSet()

    private fun Element.toThemeSchedule(): ThemeSchedule {
        // 사이트가 매긴 테마 번호. 이름이 바뀌어도 이력이 안 끊긴다
        val roomId = attr("data-room-id").trim().ifBlank { null }
        val name = attr("data-room-name").trim().ifBlank { null }
            ?: selectFirst(".theme-poster span")?.text()?.trim().orEmpty()
        val meta = selectFirst(".theme-meta")

        return ThemeSchedule(
            externalId = roomId,
            themeName = name,
            posterUrl = selectFirst(".theme-poster img")?.absUrl("src")?.ifBlank { null },
            genre = meta?.genre(),
            capacity = meta?.select("span")?.getOrNull(1)?.text()?.trim()?.ifBlank { null },
            runningMinutes = meta?.runningMinutes(),
            horrorLevel = null,
            difficulty = meta?.difficulty(),
            slots = slots(),
        )
    }

    /**
     * `<p>#75분 #어드벤쳐 #장치방 </p>` — **소요시간과 장르가 해시태그 한 줄에 섞여 있다.**
     *
     * `#75분` 처럼 숫자로 시작하는 것이 소요시간, 나머지가 장르다. 순서를 믿지 않는다 —
     * 사람이 손으로 적는 칸이라 `#어드벤쳐 #75분` 순서가 나와도 이상하지 않다.
     */
    private fun Element.tags(): List<String> =
        selectFirst("p")?.text().orEmpty()
            .split("#").map { it.trim() }.filter { it.isNotBlank() }

    private fun Element.runningMinutes(): Int? =
        tags().firstNotNullOfOrNull { RUNNING.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private fun Element.genre(): String? =
        tags().filterNot { RUNNING.containsMatchIn(it) }
            .joinToString("/").ifBlank { null }

    /**
     * `<span>난이도 4</span>`. 숫자만 뽑는다.
     *
     * ⚠️ **`난이도 3+2` 가 실제로 온다** (해운대 BANG — 2인 플레이 보정으로 보인다).
     * `toDoubleOrNull()` 로 통째로 읽으면 null 이 되어 난이도가 조용히 빈다.
     * **앞의 수만 쓴다** — 만점을 모르므로 환산하지 않는 것은 다른 매장과 같다.
     */
    private fun Element.difficulty(): Double? {
        val text = select("span").firstOrNull { it.text().contains("난이도") }?.text()
        if (text == null) {
            log.warn("난이도 칸을 못 찾았다 — \"{}\". 비워 둔다", parent()?.parent()?.attr("data-room-name"))
            return null
        }
        return DIFFICULTY.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    /**
     * 예약 여부는 **`available` 클래스의 유무**로 본다.
     *
     * ```html
     * <button class='booking-slot available ' data-booking-time='12:20'>12:20<span>예약가능</span></button>
     * <button class='booking-slot booked'                              >13:50<span>예약완료</span></button>
     * ```
     *
     * **문구(`예약가능`)나 `booked` 의 부재로 판정하지 않는다.** 실패 방향이 다르기 때문이다 —
     * 클래스 이름이 바뀌면 전 회차가 **매진**으로 읽혀 알림이 멎을 뿐이지만, `booked` 부재로
     * 보면 마크업이 바뀌는 순간 **전 회차가 가능으로 읽혀 헛알림이 대량으로 나간다.**
     *
     * 시각은 `data-booking-time` 에 있는데 **가능한 자리에만 붙는다.** 매진 자리는 버튼
     * 본문(`13:50<span>…`)에서 읽어야 한다 — 속성만 보면 매진 회차가 통째로 사라진다.
     */
    private fun Element.slots(): List<Slot> =
        select("button.booking-slot").mapNotNull { button ->
            val attrTime = button.attr("data-booking-time").trim().toLocalTimeOrNull()
            val textTime = button.ownText().trim().toLocalTimeOrNull()
            val time = attrTime ?: textTime ?: return@mapNotNull null
            Slot(time = time, available = button.hasClass("available"))
        }

    private fun String.toLocalTimeOrNull(): LocalTime? =
        runCatching { LocalTime.parse(trim()) }.getOrNull()

    companion object {
        /** 포스터가 `http://www.master-key.co.kr//upload/room/220_img1.png` 로 이미 절대경로지만,
         *  상대경로가 섞여 나올 때를 대비해 base 를 준다 */
        private const val BASE_URI = "https://" + PlayPointLabBranch.HOST + "/"

        private val RUNNING = Regex("^([0-9]+)\\s*분$")
        private val DIFFICULTY = Regex("난이도\\s*([0-9]+(?:\\.[0-9]+)?)")
    }
}
