package com.my_dream.server.crawler.smurf

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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * **스머프 매직 포레스트** 지점 하나. 게임은 하나인데 **관(방)이 여럿**이다 (조사 문서 "스머프").
 *
 * ```
 * POST /core/res/rev.act.php   (not_html=Y · theme_info_num · area · zizum_num)
 *   act=days                → 연 날짜 fun_days_select('YYYY-MM-DD')
 *   act=time&rev_days=D     → <div class="time_box"><p>1관</p><ul class="ra_bl02"><li>…  관 전체
 * ```
 *
 * ### 관을 합쳐 센다 — "그 시각에 빈 관이 하나라도 있나"
 * 1관·3관이 같은 시각(10:00 · 11:30 …)에, 2관·4관이 같은 시각(10:30 · 12:00 …)에 돈다. 저장 키가 `(테마, 날짜, 시각)` 이라
 * 그대로 넣으면 같은 시각 두 관이 서로 덮어쓴다 — **마지막에 읽힌 관이 이긴다.** 3관이 차 있으면 1관이 비어도 "매진" 이 된다.
 * 관을 테마 넷으로 나누면 키는 풀리지만, 해 본 테마 · 하고 싶은 테마에 같은 방이 네 줄로 뜬다.
 * 손님이 고르는 것은 시각이라 합친다. 전이도 정확히 *"그 시각 관이 다 찼다가 하나가 풀렸다"* 가 된다.
 */
data class SmurfSite(
    val brand: String,
    val host: String,
    val branchName: String,
    val key: String,
    val zizum: Int,
    /**
     * 이 지점 관 수. **지점을 되읽을 자리가 응답에 없어서 이것으로 대신 본다** — 강남 4 · 홍대 5 (2026-10-23 실측).
     * `zizum_num` 이 무시되고 다른 지점이 답하면 관 수가 어긋나 끊긴다. 매장이 관을 늘려도 끊긴다 — 그때 고친다
     */
    val halls: Int,
    val themeNum: String = "1",
    val themeName: String,
) {
    val baseUrl: String get() = "https://$host"
}

object SmurfSites {

    val GANGNAM = SmurfSite(
        brand = "스머프 매직 포레스트",
        host = "smurfmagic.com",
        branchName = "강남점",
        key = "smurf-gangnam",
        zizum = 6,
        halls = 4,
        themeName = "파파스머프의 마법실험실",
    )

    val ALL = listOf(GANGNAM)
}

class SmurfCrawlException(message: String) : RuntimeException(message)

private const val ACT_PATH = "/core/res/rev.act.php"

@Component
class SmurfClient(private val rateLimiter: HostRateLimiter) {

    private val clients = ConcurrentHashMap<String, RestClient>()

    fun post(site: SmurfSite, act: String, date: LocalDate?): String =
        rateLimiter.throttled(site.host) {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("act", act)
                add("not_html", "Y")
                add("theme_info_num", site.themeNum)
                add("area", "A")
                add("zizum_num", site.zizum.toString())
                add("rev_days", date?.toString().orEmpty())
            }
            clients.computeIfAbsent(site.baseUrl) { CrawlHttp.client(it) }.post()
                .uri(ACT_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값(Java-http-client/…)이 대신 나간다
                .header("User-Agent", "")
                .body(form)
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }
}

/**
 * 매장이 **연 날짜**. `act=time` 이 창을 검사하지 않아서(+20 에도 회차를 준다) 이게 없으면 창 밖에 가짜 회차가 저장된다 —
 * 프랭크와 같은 함정이다. 30분 동안 다시 묻지 않는다. **못 받으면 빈 집합** — 그 바퀴에서 이 매장을 건너뛴다.
 */
@Component
class SmurfCalendar(private val client: SmurfClient) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, Pair<Instant, Set<LocalDate>>>()
    internal var clock: Clock = Clock.systemDefaultZone()

    /** 어댑터도 이 시계로 오늘을 정한다 — 달력과 창이 서로 다른 "오늘" 을 보지 않게 */
    fun today(): LocalDate = LocalDate.now(clock)

    fun openDates(site: SmurfSite): Set<LocalDate> {
        cache[site.key]?.let { (at, dates) -> if (Duration.between(at, clock.instant()) < TTL) return dates }
        val dates = runCatching { parseDays(client.post(site, "days", null)) }.getOrElse {
            log.warn("{} {} 날짜 목록을 못 받았다 — 이번엔 건너뛴다 : {}", site.brand, site.branchName, it.message)
            return emptySet()
        }
        if (dates.isEmpty()) log.warn("{} {} 날짜 목록이 비었다 — 마크업이 바뀌었거나 휴업이다. 건너뛴다", site.brand, site.branchName)
        cache[site.key] = clock.instant() to dates
        return dates
    }

    companion object {
        private val TTL: Duration = Duration.ofMinutes(30)
        private val DAY = Regex("""fun_days_select\('([0-9]{4}-[0-9]{2}-[0-9]{2})'\)""")

        fun parseDays(html: String): Set<LocalDate> =
            DAY.findAll(html).mapNotNull { runCatching { LocalDate.parse(it.groupValues[1]) }.getOrNull() }.toSet()
    }
}

@Component
class SmurfCrawler(private val client: SmurfClient) {

    fun fetch(site: SmurfSite, date: LocalDate): DaySchedule = parse(site, date, client.post(site, "time", date))

    /**
     * ```html
     * <div class="time_msg">고정휴무</div>                                              ← 휴무 (관도 날짜도 없다)
     * <font …>2026-10-23 금</font>
     * <div class="time_box"><p>1관</p><ul class="ra_bl02">
     *   <li><span><div>10:00 </div></span></li>                                        ← 불가
     *   <li><span><a class='open' href="javascript:fun_time_select('266')">13:00 </a>  ← 가능
     * ```
     */
    fun parse(site: SmurfSite, date: LocalDate, html: String): DaySchedule {
        val doc = Jsoup.parse(html)

        doc.selectFirst("div.time_msg")?.let { msg ->
            val text = msg.text().trim()
            // 휴무가 아닌 안내문은 모르는 것이다 — "예약 마감" 같은 문구를 휴무로 읽으면 하루가 조용히 빈다
            if ("휴무" !in text) throw SmurfCrawlException("모르는 안내문: ${site.brand} ${site.branchName} $date — $text")
            return day(site, date, emptyList())
        }

        val shown = doc.selectFirst("font")?.text()?.trim().orEmpty()
        if (!shown.startsWith(date.toString())) {
            throw SmurfCrawlException("요청한 날짜와 응답 날짜가 다름: ${site.brand} ${site.branchName} 요청=$date 응답='$shown'")
        }

        val halls = doc.select("div.time_box")
        if (halls.size != site.halls) {
            throw SmurfCrawlException("관이 ${halls.size}개 — ${site.halls}개여야 한다. 다른 지점이 답했거나 관이 바뀌었다: ${site.brand} ${site.branchName} $date")
        }

        val slots = halls.flatMap { hall ->
            hall.select("ul.ra_bl02 > li").map { li ->
                val open = li.selectFirst("a.open")
                val label = (open ?: li.selectFirst("div"))?.text()?.trim()
                    ?: throw SmurfCrawlException("회차 칸 모양이 바뀌었다: ${site.brand} $date ${li.outerHtml().take(120)}")
                val time = runCatching { LocalTime.parse(label) }.getOrNull()
                    ?: throw SmurfCrawlException("시각을 못 읽었다: ${site.brand} $date '$label'")
                Slot(time, available = open != null && open.attr("href").startsWith("javascript:fun_time_select"))
            }
        }
        if (slots.isEmpty()) throw SmurfCrawlException("관은 있는데 회차가 0개 — 마크업이 바뀌었다: ${site.brand} ${site.branchName} $date")

        val merged = slots.groupBy { it.time }
            .map { (time, same) -> Slot(time, available = same.any { it.available }) }
            .sortedBy { it.time }
        return day(site, date, merged)
    }

    /** 휴무일도 테마는 남기고 회차만 비운다 (D22) — 테마 0개로 주면 "파서가 깨졌나" 경고가 매주 수·목마다 찍힌다 */
    private fun day(site: SmurfSite, date: LocalDate, slots: List<Slot>) = DaySchedule(
        store = site.storeRef(),
        date = date,
        reservationRangeDays = null,
        openDays = OPEN_DAYS,
        themes = listOf(
            ThemeSchedule(
                externalId = site.themeNum, themeName = site.themeName, posterUrl = null, genre = null, capacity = null,
                runningMinutes = null, horrorLevel = null, difficulty = null, slots = slots,
            ),
        ),
    )

    companion object {
        /** 강남 날짜 목록이 오늘~+19 다(2026-10-21). 수집은 +14 까지만 물어서 이 값이 묻는 날을 줄이지 않는다 — 진짜 문은 [SmurfCalendar] */
        const val OPEN_DAYS = 20
    }
}

fun SmurfSite.storeRef() = StoreRef(key = key, brand = brand, branchName = branchName)

/** 지점 × 날짜 = 요청 하나 (D15). 날짜는 매장 날짜 목록에 있는 날만 */
class SmurfAdapter(
    private val site: SmurfSite,
    private val crawler: SmurfCrawler,
    private val calendar: SmurfCalendar,
) : StoreAdapter {

    override val host = site.host
    override val brand = site.brand
    override val branches = listOf(site.storeRef())

    override fun plan(dates: List<LocalDate>): List<FetchUnit> {
        val window = dates.openWithin(SmurfCrawler.OPEN_DAYS, calendar.today())
        if (window.isEmpty()) return emptyList()
        val open = calendar.openDates(site)
        return window.filter { it in open }.map { date ->
            FetchUnit("${site.brand} ${site.branchName} $date", site.key, date) { crawler.fetch(site, date) }
        }
    }
}

/** [SmurfSites.ALL] 의 지점마다 어댑터 빈을 만든다 — 신비웹과 같은 이유다 (D41) */
@Component
class SmurfAdapterRegistrar : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        SmurfSites.ALL.forEach { site ->
            registry.registerBeanDefinition(
                "smurfAdapter:${site.key}",
                BeanDefinitionBuilder.genericBeanDefinition(SmurfAdapter::class.java)
                    .addConstructorArgValue(site)
                    .addConstructorArgReference("smurfCrawler")
                    .addConstructorArgReference("smurfCalendar")
                    .beanDefinition,
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
}
