package com.my_dream.server.crawler.reserv

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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * **Reserv Company** 예약 플랫폼을 쓰는 매장 지점 하나 (아키텍처 D41).
 *
 * ```
 * GET /api/booking/stores/{keycode}   (Accept: application/json)
 *   → {"result":"success","data":{"shop":{keycode, start_date, end_date}, "themes":[{id, title, slots:[{day_string, integer_to_time, can_book}]}]}}
 * ```
 *
 * **요청 한 번에 창 전체(전 테마 × 전 날짜)가 온다.** 날짜 파라미터가 없다. 그래서 수집기가 날짜마다 만든 작업이
 * 응답 하나를 나눠 쓴다 — [ReservCrawler] 가 60초 동안 들고 있다. 한 바퀴에 이 매장으로 가는 요청은 1건이다.
 *
 * ⚠️ 같은 플랫폼이어도 **푸터는 매장마다 따로 만든다** — 도어이스케이프만 "크롤링 등 무단 수집 금지" 를 넣어서 뺐다.
 * 매장을 더할 때마다 그 매장 번들의 푸터를 본다 (조사 문서 ⓓ).
 */
data class ReservSite(
    val brand: String,
    val host: String,
    val branchName: String,
    val key: String,
    /** 지점 키. 무작위 16자라 `/api/booking/stores` 목록에서 받는다. **틀리면 404 SHOP_NOT_ALLOWED** — 키가 바뀔 수 있다는 문구다 */
    val keycode: String,
    /** 수집하지 않는 테마 id. 늘 "가능" 인 안내용 가짜 테마 같은 것 */
    val skipThemes: Set<Long> = emptySet(),
    /** 응답의 start_date~end_date 일수. 다르면 기존 경고(`warnIfRangeChanged`)가 알려 준다 */
    val openDays: Int = 8,
) {
    val baseUrl: String get() = "https://$host"
}

object ReservSites {

    /** 룸즈에이는 지점이 21곳이다(대학로는 창이 31일 — 지점마다 다르다). 강남만 넣었다 */
    val ROOMSA_GANGNAM = ReservSite(
        brand = "룸즈에이",
        host = "roomsa.co.kr",
        branchName = "강남점",
        key = "roomsa-gangnam",
        keycode = "DDZfLJDVcss3dP7Q",
    )

    val PAGETODAY = ReservSite(
        brand = "오늘의 한 페이지",
        host = "page-today.co.kr",
        branchName = "강남점",
        key = "pagetoday-gangnam",
        keycode = "rjwaaAh3mVPbCdHA",
        // "예약 안내 (예약하지 마세요)" — 매장이 안내문으로 걸어 둔 가짜 테마. 8일 × 4회차가 늘 can_book:true 다.
        // 넣으면 화면에 매일 가짜 빈자리 32개가 뜬다
        skipThemes = setOf(342L),
    )

    val ALL = listOf(ROOMSA_GANGNAM, PAGETODAY)
}

class ReservCrawlException(message: String) : RuntimeException(message)

@Component
class ReservCrawler(private val rateLimiter: HostRateLimiter) {

    private val clients = ConcurrentHashMap<String, RestClient>()
    private val json = JsonMapper.builder().build()

    /** 지점별 마지막 응답. 같은 바퀴의 날짜 작업들이 나눠 쓴다 */
    private val cache = ConcurrentHashMap<String, Pair<Instant, String>>()
    internal var clock: Clock = Clock.systemDefaultZone()

    fun fetch(site: ReservSite, date: LocalDate): DaySchedule = parse(site, date, body(site))

    /**
     * **60초 안이면 다시 묻지 않는다.** 한 호스트의 작업은 1.2초 간격으로 차례로 돌아서 8일치가 10초 안에 끝난다 —
     * 그 사이 8번 같은 걸 받을 이유가 없다. 감시 빠른 확인(5~7분 간격)은 60초가 지나 있으니 늘 새로 받는다
     */
    private fun body(site: ReservSite): String {
        cache[site.keycode]?.let { (at, body) -> if (Duration.between(at, clock.instant()) < FRESH) return body }
        val body = rateLimiter.throttled(site.host) {
            clients.computeIfAbsent(site.baseUrl) { CrawlHttp.client(it) }.get()
                .uri("/api/booking/stores/{keycode}", site.keycode)
                .header("Accept", "application/json")
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }
        cache[site.keycode] = clock.instant() to body
        return body
    }

    /** 테스트가 픽스처로 부른다 */
    fun parse(site: ReservSite, date: LocalDate, body: String): DaySchedule {
        val root = runCatching { json.readTree(body) }.getOrNull()
            ?: throw ReservCrawlException("JSON 이 아닌 응답: ${site.brand} $date")
        if (root.path("result").asString("") != "success") {
            throw ReservCrawlException("거절됨: ${site.brand} — ${root.path("error").path("code").asString(root.toString().take(120))}")
        }
        val data = root.path("data")
        val shop = data.path("shop")
        val keycode = shop.path("keycode").asString("")
        if (keycode != site.keycode) throw ReservCrawlException("요청한 지점과 응답 지점이 다름: ${site.brand} 요청=${site.keycode} 응답=$keycode")

        val start = LocalDate.parse(shop.path("start_date").asString(""))
        val end = LocalDate.parse(shop.path("end_date").asString(""))
        if (date.isBefore(start) || date.isAfter(end)) {
            // 날짜를 묻는 요청이 아니라 응답 안에서 고르는 것이라, 창 밖 날짜는 "없다" 가 아니라 "모른다" 다
            throw ReservCrawlException("응답 창($start~$end) 밖 날짜: ${site.brand} $date")
        }

        val themes = data.path("themes").toList()
            .filterNot { it.path("is_deleted").asBoolean(false) || it.path("id").asLong(0) in site.skipThemes }
            .map { theme(it, date) }
        if (themes.isEmpty()) throw ReservCrawlException("테마가 0개 — 지점이 비었거나 응답 모양이 바뀌었다: ${site.brand}")

        return DaySchedule(
            store = site.storeRef(),
            date = date,
            reservationRangeDays = (end.toEpochDay() - start.toEpochDay() + 1).toInt(),
            openDays = site.openDays,
            themes = themes,
        )
    }

    /**
     * 판정은 `can_book`. 488회차에서 `left_stock`(1/0)과 늘 같이 움직였다(2026-10-21 룸즈에이 실측).
     * ⚠️ 창 마지막 날은 진짜 테마의 회차가 0개일 수 있다(아직 안 연 날) — 0개 테마도 그대로 넘긴다(D22)
     */
    private fun theme(node: JsonNode, date: LocalDate): ThemeSchedule {
        val day = date.toString()
        val slots = node.path("slots").toList()
            .filter { it.path("day_string").asString("") == day }
            .map { s ->
                val time = runCatching { LocalTime.parse(s.path("integer_to_time").asString("")) }.getOrNull()
                    ?: throw ReservCrawlException("시각을 못 읽었다: ${node.path("title").asString("")} $day ${s.path("integer_to_time")}")
                Slot(time, available = s.path("can_book").asBoolean(false))
            }
            .sortedBy { it.time }
        return ThemeSchedule(
            externalId = node.path("id").asLong().toString(),
            themeName = node.path("title").asString("").trim(),
            posterUrl = node.path("image_url").asString("").ifBlank { null },
            genre = null, capacity = null, runningMinutes = null, horrorLevel = null, difficulty = null,
            slots = slots,
        )
    }

    companion object {
        private val FRESH: Duration = Duration.ofSeconds(60)
    }
}

fun ReservSite.storeRef() = StoreRef(key = key, brand = brand, branchName = branchName)

/**
 * 날짜마다 작업 하나 — 그런데 **실제 요청은 응답을 받은 첫 작업 하나만** 보낸다(60초 캐시).
 * D15 의 "작업 하나 = 요청 하나" 에서 비켜 있다: 이 플랫폼은 날짜를 고르는 요청이 아예 없어서 작업을 날짜로 나눠야
 * 저장(DaySchedule)과 감시 빠른 확인(날짜로 고른다)이 그대로 동작한다. 요청 수는 작업 수보다 **적게만** 나간다.
 */
class ReservAdapter(private val site: ReservSite, private val crawler: ReservCrawler) : StoreAdapter {

    override val host = site.host
    override val brand = site.brand
    override val branches = listOf(site.storeRef())

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(site.openDays).map { date ->
            FetchUnit("${site.brand} ${site.branchName} $date", site.key, date) { crawler.fetch(site, date) }
        }
}

/** [ReservSites.ALL] 의 매장마다 어댑터 빈을 만든다 — 신비웹과 같은 이유다 */
@Component
class ReservAdapterRegistrar : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        ReservSites.ALL.forEach { site ->
            registry.registerBeanDefinition(
                "reservAdapter:${site.key}",
                BeanDefinitionBuilder.genericBeanDefinition(ReservAdapter::class.java)
                    .addConstructorArgValue(site)
                    .addConstructorArgReference("reservCrawler")
                    .beanDefinition,
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
}
