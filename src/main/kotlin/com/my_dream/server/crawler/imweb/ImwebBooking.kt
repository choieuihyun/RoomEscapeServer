package com.my_dream.server.crawler.imweb

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
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * **아임웹(imweb) 예약 위젯**을 쓰는 매장 하나 (아키텍처 D41).
 *
 * ```
 * POST /booking/get_prod_list.cm   start_date=D&end_date=D   (폼 인코딩 · Content-Type 은 text/html 인데 본문은 JSON)
 *   → {"msg":"SUCCESS", "available":[…], "unavailable":[…]}
 * ```
 *
 * 아임웹은 **"테마 + 시각" 이 상품 하나**다(`상자 / 10:00`). 그래서 응답에 테마가 따로 없고, 상품 이름을 갈라 테마로 묶는다.
 * 상품 이름은 짧은 별칭이라(`상자`) 화면에 보일 정식 이름은 [themes] 에 적어 둔다 — 매장 페이지 본문에서 옮긴 값이다.
 *
 * 매장을 더하려면 [ImwebSites.ALL] 에 한 줄을 더하고, 응답을 픽스처로 넣어 `ImwebBookingTest` 에 한 줄을 더한다.
 */
data class ImwebSite(
    val brand: String,
    val host: String,
    val branchName: String,
    /** 서버 지점 키. 한 번 정하면 바꾸지 않는다 */
    val key: String,
    /** 상품 이름의 앞부분(` / ` 앞) → 화면에 보일 테마. 모르는 별칭이 오면 별칭 그대로 쓰고 경고한다 */
    val themes: Map<String, ImwebTheme>,
    /** 오늘부터 며칠치. 위젯 설정 `booking_available_day` 가 6 이면 오늘~+6 이라 7 */
    val openDays: Int = 7,
) {
    val baseUrl: String get() = "https://$host"
}

data class ImwebTheme(val name: String, val runningMinutes: Int?)

object ImwebSites {

    /**
     * 강남만이다. **성수는 지점 파라미터가 아니라 다른 사이트**(`dpsnnn-s.imweb.me`)다 — 테마가 하나도 안 겹친다(2026-10-21 실측).
     * 붙이려면 여기에 한 줄을 더 둔다.
     */
    val DANPYEONSEON_GANGNAM = ImwebSite(
        brand = "단편선",
        host = "www.dpsnnn.com",
        branchName = "강남점",
        key = "dpsnnn-gangnam",
        themes = mapOf(
            "상자" to ImwebTheme("그림자 없는 상자", 75),
            "행복" to ImwebTheme("사람들은 그것을 행복이라 부르기로 했다", 80),
        ),
    )

    val ALL = listOf(DANPYEONSEON_GANGNAM)
}

class ImwebCrawlException(message: String) : RuntimeException(message)

@Component
class ImwebCrawler(private val rateLimiter: HostRateLimiter) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val clients = ConcurrentHashMap<String, RestClient>()
    private val json = JsonMapper.builder().build()

    fun fetch(site: ImwebSite, date: LocalDate): DaySchedule = validate(site, date, request(site, date))

    private fun request(site: ImwebSite, date: LocalDate): String =
        rateLimiter.throttled(site.host) {
            val form = LinkedMultiValueMap<String, String>().apply {
                // 위젯이 `booking_only_one_day` 라 시작과 끝을 같은 날로 묻는다 — 하루씩
                add("start_date", date.toString())
                add("end_date", date.toString())
            }
            clients.computeIfAbsent(site.baseUrl) { CrawlHttp.client(it) }.post()
                .uri("/booking/get_prod_list.cm")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값(Java-http-client/…)이 대신 나간다
                .header("User-Agent", "")
                .body(form)
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    /**
     * ⚠️ **실패도 범위 밖도 HTTP 200 + 빈 배열로 온다.** 배열만 보면 "그날 회차 0개" 로 읽힌다 (2026-10-21 실측).
     *
     * ```
     * msg = SUCCESS · 배열 18개                         정상
     * msg = "Exceeds available booking period (6 days)." 범위 밖 — 사이트가 직접 말했다 → 빈 하루
     * msg = "date is out of valid range."                 지난 날짜 → 예외
     * msg = SUCCESS · 배열 둘 다 빈 것                    날짜가 안 먹었다 → 예외
     * ```
     * 넷째가 함정이다 — 날짜를 빼먹고 부르면 SUCCESS 인데 `total` 에만 상품이 들어 있고 두 배열은 비어 있다.
     * 그날 상품이 정말 0개인 날과 구분할 방법이 응답에 없어서, **0개는 늘 실패로 본다.** 문 닫는 날이 생기면 그때 문구를 보고 더한다.
     */
    fun validate(site: ImwebSite, date: LocalDate, body: String): DaySchedule {
        val root = runCatching { json.readTree(body) }.getOrNull()
            ?: throw ImwebCrawlException("JSON 이 아닌 응답: ${site.brand} $date")
        val msg = root.path("msg").asString("")

        if (msg.startsWith(OUT_OF_PERIOD)) {
            log.info("범위 밖이라고 답했다 — {} {} ({})", site.brand, date, msg)
            return day(site, date, emptyList())
        }
        if (msg != "SUCCESS") throw ImwebCrawlException("거절됨: ${site.brand} $date — $msg")

        val products: List<Pair<JsonNode, Boolean>> =
            root.path("available").toList().map { node -> node to true } +
                root.path("unavailable").toList().map { node -> node to false }
        if (products.isEmpty()) {
            throw ImwebCrawlException("SUCCESS 인데 상품이 0개 — 날짜가 안 먹은 응답이다: ${site.brand} $date")
        }

        val themes = products
            .map { (node, available) -> parseProduct(site, date, node) to available }
            .groupBy({ it.first.alias }, { it })
            .map { (alias, items) ->
                val theme = site.themes[alias] ?: ImwebTheme(alias, null).also {
                    log.warn("모르는 테마 별칭 — {} \"{}\". 이름을 별칭 그대로 쓴다. ImwebSites 에 더할 것", site.brand, alias)
                }
                ThemeSchedule(
                    // 사이트 테마 번호가 없다 — 상품(idx)은 회차마다 따로라 테마 키가 못 된다. 별칭을 키로 쓴다
                    externalId = alias,
                    themeName = theme.name,
                    posterUrl = items.firstNotNullOfOrNull { it.first.thumbnail },
                    genre = null,
                    capacity = null,
                    runningMinutes = theme.runningMinutes,
                    horrorLevel = null,
                    difficulty = null,
                    slots = items.map { (p, available) -> Slot(p.time, available) }.sortedBy { it.time },
                )
            }
        return day(site, date, themes)
    }

    private class Product(val alias: String, val time: LocalTime, val thumbnail: String?)

    /** `상자 / 10:00` → 상자 · 10:00. **모양이 다르면 끊는다** — 매장이 상품 이름을 바꾸면 여기서 시끄럽게 깨진다 */
    private fun parseProduct(site: ImwebSite, date: LocalDate, node: JsonNode): Product {
        val name = node.path("name").asString("")
        val m = PRODUCT.matchEntire(name.trim())
            ?: throw ImwebCrawlException("상품 이름이 '테마 / 시각' 모양이 아니다: ${site.brand} $date \"$name\"")
        val time = runCatching { LocalTime.parse(m.groupValues[2].padStart(5, '0')) }.getOrNull()
            ?: throw ImwebCrawlException("시각을 못 읽었다: ${site.brand} $date \"$name\"")
        return Product(m.groupValues[1].trim(), time, node.path("thumbnail").asString("").ifBlank { null })
    }

    private fun day(site: ImwebSite, date: LocalDate, themes: List<ThemeSchedule>) = DaySchedule(
        store = site.storeRef(),
        date = date,
        reservationRangeDays = null,
        openDays = site.openDays,
        themes = themes,
    )

    companion object {
        private const val OUT_OF_PERIOD = "Exceeds available booking period"
        private val PRODUCT = Regex("^(.+?)\\s*/\\s*([0-9]{1,2}:[0-9]{2})$")
    }
}

fun ImwebSite.storeRef() = StoreRef(key = key, brand = brand, branchName = branchName)

/** 아임웹 매장 하나 = 지점 하나 = 어댑터 하나. 날짜마다 요청 하나 (D15) */
class ImwebAdapter(private val site: ImwebSite, private val crawler: ImwebCrawler) : StoreAdapter {

    override val host = site.host
    override val brand = site.brand
    override val branches = listOf(site.storeRef())

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(site.openDays).map { date ->
            FetchUnit("${site.brand} ${site.branchName} $date", site.key, date) { crawler.fetch(site, date) }
        }
}

/** [ImwebSites.ALL] 의 매장마다 어댑터 빈을 만든다 — 신비웹과 같은 이유다 ([com.my_dream.server.crawler.sinbiweb.SinbiwebAdapterRegistrar]) */
@Component
class ImwebAdapterRegistrar : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        ImwebSites.ALL.forEach { site ->
            registry.registerBeanDefinition(
                "imwebAdapter:${site.key}",
                BeanDefinitionBuilder.genericBeanDefinition(ImwebAdapter::class.java)
                    .addConstructorArgValue(site)
                    .addConstructorArgReference("imwebCrawler")
                    .beanDefinition,
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
}
