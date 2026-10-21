package com.my_dream.server.crawler.aspres

import com.my_dream.server.crawler.CrawlHttp
import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.crawler.openWithin
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * **ASP 예약 솔루션** (IIS · cp949) 을 쓰는 매장 하나 (아키텍처 D41). 코드케이와 싸인이스케이프가 같은 솔루션이다.
 *
 * ```
 * GET /sub/<목록 페이지>?R_JIJEM=S1&chois_date=YYYY-MM-DD[&R_THEMA=A]
 *   <li class="timeOff">★ 14:00</li>                                  ← 불가
 *   <a href="<예약 페이지>?chois_date=…&room_time=…&room_code=…"><li class="timeOn">☆ 12:40</li></a>  ← 가능
 * ```
 *
 * **매장마다 갈리는 것은 요청 단위 하나다** (2026-10-21 조사):
 * ```
 * 코드케이   지점 × 날짜 — R_THEMA 를 빼면 15테마가 한 페이지에 온다 (접혀 있을 뿐)
 * 싸인       테마 × 날짜 — R_THEMA 를 빼면 **첫 테마만** 온다. 테마마다 묻는다
 * ```
 *
 * 매장을 더하려면 [AspSites.ALL] 에 한 줄을 더하고, 응답을 픽스처로 넣어 `AspReservationTest` 에 한 줄을 더한다.
 */
data class AspSite(
    val brand: String,
    val host: String,
    val branchName: String,
    /** 서버 지점 키. 한 번 정하면 바꾸지 않는다 */
    val key: String,
    /** `R_JIJEM` 값. **빼면 다른 지점이 답한다** — 싸인은 수원 성대역점(S2)이 기본값이다 */
    val jijem: String,
    /** 회차 목록 페이지 (`/sub/code_sub04_1.html`) */
    val listPath: String,
    /** 가능한 회차가 거는 예약 페이지 이름 (`code_sub04_2.html`) — 이 링크가 있어야 가능이다 */
    val bookPage: String,
    /**
     * 테마마다 따로 물어야 하는 매장의 테마들 (`R_THEMA` 코드 → 이름). 비어 있으면 지점 한 번에 전 테마가 온다.
     * 이름은 응답의 선택된 탭과 대조한다 — 빈 `R_THEMA` 가 첫 테마로 조용히 바뀌는 것을 잡으려고.
     */
    val themesPerRequest: Map<String, String> = emptyMap(),
    val openDays: Int = 7,
) {
    val baseUrl: String get() = "http://$host"
}

object AspSites {

    val CODEK_GANGNAM = AspSite(
        brand = "코드케이",
        host = "code-k.co.kr",
        branchName = "강남점",
        key = "codek-gangnam",
        jijem = "S1",
        listPath = "/sub/code_sub04_1.html",
        bookPage = "code_sub04_2.html",
        // 페이지가 "최대일수 30" 이라고 밝힌다 → 오늘~+30. 실제로 보는 건 계층 폴링이 고른 날짜(+14 까지)뿐이다 (D21)
        openDays = 31,
    )

    /**
     * 테마 셋 중 EXPRESS(C) 는 뺐다 — 탭은 있는데 회차가 0개고 가격도 비어 있다(2026-10-21 · 쉬는 테마로 보인다).
     * 넣어 두면 매 바퀴 요청 7건이 빈 응답을 받는다. 회차가 생기면 여기 한 줄로 되살린다.
     */
    val SIGN_GANGNAM_CITY = AspSite(
        brand = "싸인이스케이프",
        host = "www.signescape.com",
        branchName = "강남시티점",
        key = "signescape-gangnamcity",
        jijem = "S6",
        listPath = "/sub/sub03_1.html",
        bookPage = "sub03_2.html",
        themesPerRequest = linkedMapOf("A" to "러너웨이", "B" to "MUST"),
    )

    val ALL = listOf(CODEK_GANGNAM, SIGN_GANGNAM_CITY)
}

class AspCrawlException(message: String) : RuntimeException(message)

/**
 * 요청 + 파싱 + 검증.
 *
 * ⚠️ **jsoup 이 아니라 원문 패턴으로 읽는다.** 마크업이 `<ul>` 안에 `<a><li>…</li></a>` 를 넣는 비표준 중첩이라,
 * HTML 파서가 트리를 고쳐 쓰는 방식에 따라 링크와 회차의 짝이 달라질 수 있다. 짝이 바뀌면 가능·불가가 뒤섞인다 —
 * 원문에서 "링크 바로 뒤의 회차" 를 읽는 게 이 마크업에선 더 단단하다. 모양이 바뀌면 회차가 0개가 되어 예외로 드러난다.
 */
@Component
class AspCrawler(private val rateLimiter: HostRateLimiter) {

    private val clients = ConcurrentHashMap<String, RestClient>()

    fun fetch(site: AspSite, date: LocalDate, themeCode: String? = null, today: LocalDate = LocalDate.now()): DaySchedule =
        parse(site, date, themeCode, request(site, date, themeCode), today)

    private fun request(site: AspSite, date: LocalDate, themeCode: String?): String {
        val bytes = rateLimiter.throttled(site.host) {
            clients.computeIfAbsent(site.baseUrl) { CrawlHttp.client(it) }.get()
                .uri { uri ->
                    uri.path(site.listPath)
                        .queryParam("R_JIJEM", site.jijem)
                        .queryParam("chois_date", date)
                        .apply { themeCode?.let { queryParam("R_THEMA", it) } }
                        .build()
                }
                .retrieve()
                .body(ByteArray::class.java)
        } ?: ByteArray(0)
        // 응답 헤더에 charset 이 없고 본문은 cp949 다. 문자열로 받으면 한글이 깨진 채 비교가 전부 어긋난다
        return String(bytes, CP949)
    }

    /** 테스트가 픽스처로 부른다. [today] 는 "최대일수" 경계를 재는 기준이다 */
    fun parse(site: AspSite, date: LocalDate, themeCode: String?, html: String, today: LocalDate = LocalDate.now()): DaySchedule {
        val rendered = (INPUT_DATE.find(html) ?: CALENDAR_SELECTED.find(html))?.groupValues?.get(1)
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: throw AspCrawlException("예약 페이지가 아닌 응답: ${site.brand} $date")
        if (rendered != date) throw AspCrawlException("요청한 날짜와 렌더된 날짜가 다름: ${site.brand} 요청=$date 응답=$rendered")

        val jijem = JIJEM.find(html)?.groupValues?.get(1)
        if (jijem != site.jijem) throw AspCrawlException("요청한 지점과 응답 지점이 다름: ${site.brand} 요청=${site.jijem} 응답=$jijem")

        // 페이지가 직접 밝힌 창. 그 밖은 매장마다 "전 회차 불가"(코드케이) · "링크 없는 timeOn"(싸인) 으로 온다 —
        // 저장하면 여는 순간 그날 회차가 한꺼번에 불가→가능이 된다. 묻지도 저장하지도 않는다
        val maxDays = MAX_DAYS.find(html)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw AspCrawlException("'예약가능 최대일수' 를 못 찾았다 — 마크업이 바뀌었다: ${site.brand} $date")
        if (date.isAfter(today.plusDays(maxDays))) {
            throw AspCrawlException("매장이 밝힌 창(+${maxDays}일) 밖이다: ${site.brand} $date — openDays 를 줄일 것")
        }

        val themes = if (themeCode == null) branchPage(site, html) else listOf(themePage(site, themeCode, html))
        if (themes.isEmpty() || themes.all { it.slots.isEmpty() }) {
            throw AspCrawlException("회차가 하나도 없다 — 지점이 비었거나 마크업이 바뀌었다: ${site.brand} $date ${themeCode ?: ""}")
        }
        return DaySchedule(site.storeRef(), date, maxDays.toInt() + 1, site.openDays, themes)
    }

    /** 코드케이 — 지점 한 페이지에 테마가 `<li class="thema1">이름</li>` 로 줄줄이 온다 */
    private fun branchPage(site: AspSite, html: String): List<ThemeSchedule> {
        val chunks = html.split(THEME_HEAD)
        return chunks.drop(1).mapIndexed { i, chunk ->
            val name = chunk.substringBefore("</li>").trim()
            val body = chunk.substringBefore(THEME_END)
            // 테마 번호는 블록 바로 앞에 주석으로 남은 옛 링크(R_THEMA=codek_s1)에 늘 있다.
            // 회차 링크의 room_code 에도 있지만, 전 회차가 매진인 테마는 링크가 하나도 없어서 거기선 못 얻는다
            val id = THEME_CODE.findAll(chunks[i].takeLast(800)).lastOrNull()?.groupValues?.get(1)
                ?: ROOM_CODE.find(body)?.groupValues?.get(1)
            theme(id, name, slots(site, body))
        }
    }

    /** 싸인 — 테마 하나. 선택된 탭이 물어본 테마인지 본다 */
    private fun themePage(site: AspSite, code: String, html: String): ThemeSchedule {
        val selected = SELECTED_TAB.find(html)
        val name = site.themesPerRequest[code] ?: throw AspCrawlException("설정에 없는 테마 코드: ${site.brand} $code")
        if (selected == null || selected.groupValues[1] != code || selected.groupValues[2].trim() != name) {
            throw AspCrawlException(
                "요청한 테마와 응답 테마가 다름: ${site.brand} 요청=$code($name) 응답=${selected?.groupValues?.drop(1)}",
            )
        }
        return theme(code, name, slots(site, html))
    }

    /** 가능 = 예약 페이지 링크가 걸린 회차. **클래스(`timeOn`)로 보지 않는다** — 싸인은 범위 밖에도 timeOn 이다 */
    private fun slots(site: AspSite, body: String): List<Slot> =
        Regex("""(<a href="${Regex.escape(site.bookPage)}\?[^"]*">\s*)?<li class="time(?:On|Off)">\s*[★☆]?\s*([0-9]{1,2}:[0-9]{2})""")
            .findAll(body)
            .map { Slot(LocalTime.parse(it.groupValues[2].padStart(5, '0')), available = it.groupValues[1].isNotEmpty()) }
            .toList()

    private fun theme(id: String?, name: String, slots: List<Slot>) = ThemeSchedule(
        externalId = id, themeName = name, posterUrl = null, genre = null, capacity = null,
        runningMinutes = null, horrorLevel = null, difficulty = null, slots = slots,
    )

    companion object {
        private val CP949: Charset = Charset.forName("MS949")

        private val INPUT_DATE = Regex("""name="chois_date" value="([0-9-]{10})"""")
        /** 싸인은 입력칸이 없고, 달력에서 고른 날의 칸만 배경이 주황(#fda305)이다 */
        private val CALENDAR_SELECTED = Regex("""fda305;'><a href=\?chois_date=([0-9-]{10})""")
        private val JIJEM = Regex("""R_JIJEM" value="([A-Za-z0-9]+)"""")
        private val MAX_DAYS = Regex("""최대일수는\s*<span class="orange_b">\s*([0-9]+)""")

        private const val THEME_HEAD = "<li class=\"thema1\">"
        private const val THEME_END = "<div style=\"clear: both\"></div>"
        private val THEME_CODE = Regex("""R_THEMA=([A-Za-z0-9_]+)&""")
        private val ROOM_CODE = Regex("""room_code=([A-Za-z0-9_]+)&""")
        private val SELECTED_TAB = Regex("""R_THEMA=([A-Za-z0-9_]+)[^"]*">\s*<li class='tabon'[^>]*>([^<]*)</li>""")
    }
}

fun AspSite.storeRef() = StoreRef(key = key, brand = brand, branchName = branchName)

/** ASP 매장 하나 = 지점 하나 = 어댑터 하나. 테마마다 묻는 매장은 날짜 × 테마로 곱해진다 (D15) */
class AspAdapter(private val site: AspSite, private val crawler: AspCrawler) : StoreAdapter {

    override val host = site.host
    override val brand = site.brand
    override val branches = listOf(site.storeRef())

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(site.openDays).flatMap { date ->
            if (site.themesPerRequest.isEmpty()) {
                listOf(FetchUnit("${site.brand} ${site.branchName} $date", site.key, date) { crawler.fetch(site, date) })
            } else {
                site.themesPerRequest.map { (code, name) ->
                    FetchUnit("${site.brand} ${site.branchName} $name $date", site.key, date, code) { crawler.fetch(site, date, code) }
                }
            }
        }
}

/** [AspSites.ALL] 의 매장마다 어댑터 빈을 만든다 — 신비웹과 같은 이유다 */
@Component
class AspAdapterRegistrar : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        AspSites.ALL.forEach { site ->
            registry.registerBeanDefinition(
                "aspAdapter:${site.key}",
                BeanDefinitionBuilder.genericBeanDefinition(AspAdapter::class.java)
                    .addConstructorArgValue(site)
                    .addConstructorArgReference("aspCrawler")
                    .beanDefinition,
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
}
