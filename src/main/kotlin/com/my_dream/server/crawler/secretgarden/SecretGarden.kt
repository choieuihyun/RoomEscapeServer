package com.my_dream.server.crawler.secretgarden

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
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime

/**
 * **비밀의 화원** 미드나잇 합정점 (`www.secretgardenescape.com` · 자체 · 2026-10-22 조사).
 *
 * ```
 * GET /reservation.html?k_shopno=7&rdate=YYYY-MM-DD&prdno=   → 지점 하루치 전 테마
 *   <input name="rdate" value="2026-10-24"> <input name="k_shopno" value="7">     ← 날짜 · 지점 되읽기
 *   <h3 class="h3_theme">비밀의 가족</h3>
 *   <a href="javascript:;" onclick="SendVar('88','2026-10-24','10:40');"><span class="time">10:40</span>   ← 가능
 *   <a href="javascript:;" class="end"><span class="time">14:00</span>                                  ← 불가
 * ```
 *
 * ⚠️ **불가에도 `href` 가 있다**(`javascript:;`) — 신비웹처럼 `a[href]` 로 보면 전부 가능이 된다. `SendVar` 가 있는지로 본다.
 * 플레이방탈 링크의 `k_shopno=8~11` 은 이 지점이 아니다 — 8 · 9 는 테마 0개다. 미드나잇 합정이 **7** 이다(사이트 탭).
 */
object SecretGarden {
    const val BRAND = "비밀의 화원"
    const val HOST = "www.secretgardenescape.com"
    const val SHOP = "7"
    const val BRANCH_NAME = "미드나잇 합정점"
    const val KEY = "secretgarden-midnight"

    /** 오늘~+6 (10:2x 실측 — +7 부터 닫힌다). +6 이 **몇 시에** 열리는지는 아직 못 봤다 — [SecretGardenCrawler.parse] 의 마지막 날 규칙 */
    const val OPEN_DAYS = 7

    /**
     * 테마 번호. **매진인 테마는 응답에 번호가 없다**(번호는 가능 회차의 `SendVar` 에만 있다) — 이름으로 찾는다.
     * 새 테마가 생기면 예외로 끊겨 여기 한 줄을 더하게 된다. 조용히 이름이 키가 되는 것보다 낫다
     */
    val THEMES = mapOf("비밀의 가족" to "88", "파리82" to "87", "팩토리 엠" to "86", "기다려, 금방 갈게[야외]" to "47")

    val storeRef = StoreRef(key = KEY, brand = BRAND, branchName = BRANCH_NAME)
}

class SecretGardenCrawlException(message: String) : RuntimeException(message)

@Component
class SecretGardenCrawler(private val rateLimiter: HostRateLimiter) {

    private val log = LoggerFactory.getLogger(javaClass)
    /** 조사를 http 로 했다(2026-10-22). https 는 시험하지 않았으므로 바꾸지 않는다 — 황금열쇠와 같다 */
    private val client by lazy { CrawlHttp.client("http://${SecretGarden.HOST}") }

    fun fetch(date: LocalDate): DaySchedule = parse(date, request(date))

    private fun request(date: LocalDate): String =
        rateLimiter.throttled(SecretGarden.HOST) {
            client.get()
                .uri { it.path("/reservation.html").queryParam("k_shopno", SecretGarden.SHOP).queryParam("rdate", date).queryParam("prdno", "").build() }
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    /** 테스트가 픽스처로 부른다. [today] 는 마지막 날 규칙의 기준이다 */
    fun parse(date: LocalDate, html: String, today: LocalDate = LocalDate.now()): DaySchedule {
        val doc = Jsoup.parse(html, "http://${SecretGarden.HOST}/")

        val shown = doc.selectFirst("input[name=rdate]")?.attr("value")
        if (shown != date.toString()) throw SecretGardenCrawlException("요청한 날짜와 렌더된 날짜가 다름: 요청=$date 응답=$shown")
        val shop = doc.selectFirst("input[name=k_shopno]")?.attr("value")
        if (shop != SecretGarden.SHOP) throw SecretGardenCrawlException("요청한 지점과 응답 지점이 다름: 요청=${SecretGarden.SHOP} 응답=$shop")

        val themes = doc.select("div.theme_box").map { box -> theme(box, date) }
        if (themes.isEmpty() || themes.all { it.slots.isEmpty() }) {
            throw SecretGardenCrawlException("회차가 하나도 없다 — 지점이 비었거나 마크업이 바뀌었다: $date")
        }

        // 🕳️ 창 밖은 "테마 있음 · 전 회차 마감" 으로 온다 — 매진과 모양이 같다. 묻는 건 창 안(+6 까지)뿐이지만,
        // +6 이 자정이 아니라 낮에 열린다면 그 전엔 이 모양이다. 저장하면 여는 순간 하루치가 한꺼번에 불가→가능이 된다.
        // 그래서 **창 마지막 날이 전부 마감이면 아직 안 연 날로 보고 비운다.** 오늘 · 중간 날짜의 전부 마감은 진짜 매진이라 그대로 둔다
        val lastDay = today.plusDays((SecretGarden.OPEN_DAYS - 1).toLong())
        if (date == lastDay && themes.all { t -> t.slots.none { it.available } }) {
            log.info("{} {} {} — 창 마지막 날이 전부 마감이다. 아직 안 연 날로 보고 저장하지 않는다", SecretGarden.BRAND, SecretGarden.BRANCH_NAME, date)
            return DaySchedule(SecretGarden.storeRef, date, null, SecretGarden.OPEN_DAYS, emptyList())
        }
        return DaySchedule(SecretGarden.storeRef, date, null, SecretGarden.OPEN_DAYS, themes)
    }

    private fun theme(box: Element, date: LocalDate): ThemeSchedule {
        val name = box.selectFirst("h3.h3_theme")?.text()?.trim().orEmpty()
        val id = SecretGarden.THEMES[name] ?: throw SecretGardenCrawlException("설정에 없는 테마: '$name' $date — SecretGarden.THEMES 에 번호를 더한다")

        val slots = box.select("ul.reserve_Time li a").map { a ->
            val label = a.selectFirst("span.time")?.text()?.trim().orEmpty()
            val time = runCatching { LocalTime.parse(label.padStart(5, '0')) }.getOrNull()
                ?: throw SecretGardenCrawlException("시각을 못 읽었다: $name $date '$label'")
            val send = SEND_VAR.find(a.attr("onclick"))
            if (send != null && (send.groupValues[1] != id || send.groupValues[2] != date.toString())) {
                throw SecretGardenCrawlException("회차 링크의 테마·날짜가 다르다: $name($id) $date — ${send.value}")
            }
            if (send == null && !a.hasClass("end")) throw SecretGardenCrawlException("모르는 회차 모양: $name $date ${a.outerHtml().take(120)}")
            Slot(time, available = send != null)
        }

        return ThemeSchedule(
            externalId = id,
            themeName = name,
            posterUrl = box.selectFirst("div.theme_pic img")?.absUrl("src")?.ifBlank { null },
            genre = null, capacity = null, runningMinutes = null, horrorLevel = null,
            // 장미 그림 다섯 개 중 켜진 수 — "빨간장미는 난이도를 나타냅니다"
            difficulty = box.select("span.level_img img").count { "_on" in it.attr("src") }.takeIf { it > 0 }?.toDouble(),
            slots = slots,
        )
    }

    companion object {
        private val SEND_VAR = Regex("""SendVar\('([0-9]+)','([0-9-]{10})','([0-9:]+)'\)""")
    }
}

/** 지점 × 날짜 = 요청 하나 (D15) */
// ⛔ 수집 목록에서 뺐다 (2026-10-22 14:49 · 배포 첫 바퀴) — 운영 서버(오라클 · 일본 IP)에 매장 호스팅이 봇 확인 화면(cupid.js · 403)을 보냈다.
//    사무실 PC 에서는 진짜 페이지였다. 확인 화면을 풀어 지나가면 우회라 하지 않는다 (CLAUDE.md 수집 윤리 · 매장 정보.md 장애 기록).
//    설정 · 파서 · 픽스처 테스트는 남긴다 — 풀리면 아래에 @Component 한 줄을 되살린다.
class SecretGardenAdapter(private val crawler: SecretGardenCrawler) : StoreAdapter {

    override val host = SecretGarden.HOST
    override val brand = SecretGarden.BRAND
    override val branches = listOf(SecretGarden.storeRef)

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(SecretGarden.OPEN_DAYS).map { date ->
            FetchUnit("${SecretGarden.BRAND} ${SecretGarden.BRANCH_NAME} $date", SecretGarden.KEY, date) { crawler.fetch(date) }
        }
}
