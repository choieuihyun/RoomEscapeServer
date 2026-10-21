package com.my_dream.server.crawler.sinbiweb

import com.my_dream.server.crawler.CrawlHttp
import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.crawler.openWithin
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

/**
 * 신비웹 **AJAX 세대** 매장 하나 (아키텍처 D41). GET 세대([SinbiwebSite])와 마크업·요청이 전혀 다르다.
 *
 * ```
 * POST /core/res/rev.make.ajax.php
 *   act=calendar&rev_days=D[&year=Y&month=M]   → 열린 날만 fun_days_select('YYYY-MM-DD',i) 링크
 *   act=time&rev_days=D&theme_num=T             → 테마 하나의 회차 (가능 = href 있는 <a>)
 * ```
 *
 * ### 🕳️ 회차 요청은 창을 검사하지 않는다 (프랭크 2026-10-21 실측)
 * ```
 * 10-29 (+8)  act=time → 9회차 **전부 링크** — 다 예약 가능처럼 보인다
 * ```
 * 그대로 저장하면 가짜 빈자리가 화면에 나간다. 그래서 **달력이 연 날만 묻는다** ([SinbiwebAjaxCalendar]).
 * 창을 우리 숫자(openDays)만 믿고 묻지 않는 이유는, 매장이 창을 줄이는 날 그 차이만큼이 조용히 가짜가 되기 때문이다.
 */
data class SinbiwebAjaxSite(
    val brand: String,
    val host: String,
    val branchName: String,
    val key: String,
    /** `theme_num` → 테마. 회차 응답에는 테마 이름이 없어서 여기 적는다 (`act=theme` 응답에서 옮긴 값) */
    val themes: Map<String, SinbiwebAjaxTheme>,
    /**
     * 오늘부터 며칠치. 프랭크는 달력이 +7 까지 링크를 거는데, +7 회차는 **23:30 까지 "예약오픈시간" 으로 거절**한다.
     * 그래서 +6 까지만 본다 — +7 을 넣으면 매일 23:30 전까지 그 날짜가 테마 수만큼 실패로 쌓인다
     */
    val openDays: Int = 7,
    val scheme: String = "https",
) {
    val baseUrl: String get() = "$scheme://$host"
}

data class SinbiwebAjaxTheme(val name: String, val runningMinutes: Int?)

object SinbiwebAjaxSites {

    val THE_FRANK = SinbiwebAjaxSite(
        brand = "프랭크의 골동품가게",
        host = "thefrank.co.kr",
        branchName = "강남점",
        key = "thefrank-gangnam",
        themes = linkedMapOf(
            "5" to SinbiwebAjaxTheme("My Private Heaven", 75),
            "6" to SinbiwebAjaxTheme("Brooklyn My Love", 75),
            "7" to SinbiwebAjaxTheme("Plan to save my dear", 70),
        ),
        // ⚠️ https 는 자바에서 실패한다 — 매장 서버가 **엉뚱한 중간 인증서**를 보낸다(2026-10-21 확인).
        //    서버 인증서 발급자는 "Sectigo RSA Domain Validation Secure Server CA" 인데 함께 오는 중간은 "DV R36" 이다.
        //    curl(macOS)은 빠진 고리를 스스로 채워서 성공하고, 자바는 "PKIX path building failed" 로 끊는다.
        //    **검증을 끄지 않는다.** http 가 리다이렉트 없이 같은 응답을 줘서 그쪽으로 묻는다 — 읽기만 하고 보낼 개인정보가 없다.
        //    매장이 인증서를 고치면 https 로 되돌린다
        scheme = "http",
    )

    val ALL = listOf(THE_FRANK)
}

private const val AJAX_PATH = "/core/res/rev.make.ajax.php"

/** 매장 하나가 쓰는 HTTP. 달력과 회차가 같은 줄([HostRateLimiter])에 선다 */
@Component
class SinbiwebAjaxClient(private val rateLimiter: HostRateLimiter) {

    private val clients = ConcurrentHashMap<String, RestClient>()

    fun post(site: SinbiwebAjaxSite, form: Map<String, String>): String =
        rateLimiter.throttled(site.host) {
            clients.computeIfAbsent(site.baseUrl) { CrawlHttp.client(it) }.post()
                .uri(AJAX_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값(Java-http-client/…)이 대신 나간다
                .header("User-Agent", "")
                .body(LinkedMultiValueMap<String, String>().apply { form.forEach { (k, v) -> add(k, v) } })
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }
}

/**
 * 매장이 **연 날짜**. 30분 동안 다시 묻지 않는다 — 새 날은 하루 한 번(23:30) 열리므로 바퀴마다 물을 이유가 없다.
 *
 * 한 달 화면이라, 창이 다음 달로 넘어가면 다음 달도 한 번 더 묻는다.
 * **못 받으면 빈 집합이다** — 그 바퀴에서 이 매장을 통째로 건너뛴다. 창을 추측해서 묻는 것보다 낫다(위 함정).
 */
@Component
class SinbiwebAjaxCalendar(private val client: SinbiwebAjaxClient) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, Pair<Instant, Set<LocalDate>>>()
    internal var clock: Clock = Clock.systemDefaultZone()

    /** 어댑터도 이 시계로 오늘을 정한다 — 달력과 창이 서로 다른 "오늘" 을 보지 않게 */
    fun today(): LocalDate = LocalDate.now(clock)

    fun openDates(site: SinbiwebAjaxSite, today: LocalDate = today()): Set<LocalDate> {
        cache[site.host]?.let { (at, dates) -> if (Duration.between(at, clock.instant()) < TTL) return dates }
        val dates = runCatching {
            val months = setOf(YearMonth.from(today), YearMonth.from(today.plusDays(site.openDays.toLong() - 1)))
            months.flatMap { ym ->
                val form = linkedMapOf("act" to "calendar", "rev_days" to today.toString())
                if (ym != YearMonth.from(today)) form += mapOf("year" to ym.year.toString(), "month" to ym.monthValue.toString())
                parseCalendar(client.post(site, form))
            }.toSet()
        }.getOrElse {
            log.warn("{} 달력을 못 받았다 — 이번엔 건너뛴다 : {}", site.brand, it.message)
            return emptySet()
        }
        if (dates.isEmpty()) log.warn("{} 달력에 연 날이 하나도 없다 — 마크업이 바뀌었거나 휴업이다. 건너뛴다", site.brand)
        cache[site.host] = clock.instant() to dates
        return dates
    }

    companion object {
        private val TTL: Duration = Duration.ofMinutes(30)
        private val OPEN_DAY = Regex("""fun_days_select\('([0-9]{4}-[0-9]{2}-[0-9]{2})'""")

        fun parseCalendar(html: String): Set<LocalDate> =
            OPEN_DAY.findAll(html).mapNotNull { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }.toSet()
    }
}

/** 회차 요청 + 검증 */
@Component
class SinbiwebAjaxCrawler(private val client: SinbiwebAjaxClient) {

    fun fetch(site: SinbiwebAjaxSite, themeNum: String, date: LocalDate): DaySchedule =
        parseTime(site, themeNum, date, client.post(site, mapOf("act" to "time", "rev_days" to date.toString(), "theme_num" to themeNum)))

    /**
     * ```html
     * <a class="none"><span>… 15시 10분 </span></a>                                   ← 불가
     * <a href="javascript:fun_theme_time_select('311','0')"><span>… 12시 10분 </span></a>  ← 가능
     * ```
     * ⚠️ 오늘 **지난 회차는 목록에서 사라진다**(마감으로 남지 않는다). 회차가 줄었다고 경고하지 않는다.
     */
    fun parseTime(site: SinbiwebAjaxSite, themeNum: String, date: LocalDate, html: String): DaySchedule {
        val theme = site.themes[themeNum] ?: throw SinbiwebCrawlException("설정에 없는 테마: ${site.brand} $themeNum")
        NOT_YET.find(html)?.let {
            // 아직 대답할 시각이 아니다 — 결론이 아니라 보류라 실패로 둔다 (키이스케이프 "예약오픈시간" 과 같은 판단)
            throw SinbiwebCrawlException("아직 안 열었다: ${site.brand} ${theme.name} $date — ${it.value}")
        }
        val slots = SLOT.findAll(html).map { m ->
            val time = LocalTime.of(m.groupValues[2].toInt(), m.groupValues[3].toInt())
            Slot(time, available = m.groupValues[1].startsWith("href=\"javascript:fun_theme_time_select"))
        }.toList()
        if (slots.isEmpty()) {
            throw SinbiwebCrawlException("회차가 0개 — 테마가 없어졌거나 마크업이 바뀌었다: ${site.brand} ${theme.name} $date")
        }
        return DaySchedule(
            store = site.storeRef(),
            date = date,
            reservationRangeDays = null,
            openDays = site.openDays,
            themes = listOf(
                ThemeSchedule(
                    externalId = themeNum, themeName = theme.name, posterUrl = null, genre = null, capacity = null,
                    runningMinutes = theme.runningMinutes, horrorLevel = null, difficulty = null,
                    slots = slots.sortedBy { it.time },
                ),
            ),
        )
    }

    companion object {
        /** `<a …속성…><span><img …> &nbsp; 12시 10분 </span></a>` — 속성 자리로 가능·불가를 가른다 */
        private val SLOT = Regex("""<a ([^>]*)><span>[^<]*<img[^>]*>[^0-9<]*([0-9]{1,2})시\s*([0-9]{1,2})분""")
        private val NOT_YET = Regex("""예약오픈시간[^<]*""")
    }
}

fun SinbiwebAjaxSite.storeRef() = StoreRef(key = key, brand = brand, branchName = branchName)

/** 테마 × 날짜 = 요청 하나 (D15). 날짜는 달력이 연 날만 */
class SinbiwebAjaxAdapter(
    private val site: SinbiwebAjaxSite,
    private val calendar: SinbiwebAjaxCalendar,
    private val crawler: SinbiwebAjaxCrawler,
) : StoreAdapter {

    override val host = site.host
    override val brand = site.brand
    override val branches = listOf(site.storeRef())

    override fun plan(dates: List<LocalDate>): List<FetchUnit> {
        val today = calendar.today()
        val window = dates.openWithin(site.openDays, today)
        if (window.isEmpty()) return emptyList()
        // 달력은 제로월드 테마 목록처럼 plan 에서 받는다 — 30분 캐시라 바퀴마다 나가지 않는다
        val open = calendar.openDates(site, today)
        return window.filter { it in open }.flatMap { date ->
            site.themes.map { (num, theme) ->
                FetchUnit("${site.brand} ${theme.name} $date", site.key, date, num) { crawler.fetch(site, num, date) }
            }
        }
    }
}
