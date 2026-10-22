package com.my_dream.server.crawler.wpbooking

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
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 워드프레스 예약 플러그인 **`booking_system`**(7.1.3)을 쓰는 매장 지점 하나 — 딥띵커 · 쇼룸404 (2026-10-22 · 아키텍처 D41 · D45).
 *
 * ```
 * POST /wp-admin/admin-ajax.php   action=filter_rooms&location_id=L&theme_id=T&currentDate=D   → 지점 하루치 (조각 HTML)
 *   <div class="theme1-detail-info"> <h2>옹이의 꿈</h2> … <input type="hidden" name="id" value="1727">
 *     <a href="javascript:void(0)" id="1727" class="submit" data-time="11:00">     ← 가능
 *     <a href="javascript:void(0)" id="1727" data-time="10:00" class="disable">    ← 불가
 * ```
 * 예약 화면(`/reservation/` · `/booking/`)이 날짜를 고를 때 스스로 부르는 요청이다. robots 는 `/wp-admin/` 을 막고 `admin-ajax.php` 만 연다(Yoast 기본값).
 *
 * ### Cloudflare 뒤에 있다 — 챌린지가 없어서 붙였다 (사용자 결정 2026-10-22)
 * 두 사이트 다 `server: cloudflare` 다. 사무실 PC 와 **오라클 서버** 둘 다에서 챌린지 · 403 없이 진짜 응답이 왔다(2026-10-22).
 * 막히면(403 · 챌린지) **우회하지 않고** 쉬고(D39) 뺀다 — 확인 순서 3번.
 *
 * ### 🕳️ 매진된 날은 **빈 응답**이다 (공백 5바이트)
 * 화면도 "이 날짜에는 테마를 사용할 수 없습니다" 만 보여 준다 — 회차가 아예 없다. 지난 날짜 · 창 밖도 같은 모양이지만 우리는 오늘~+13 만 묻는다.
 * 그대로 두면 전에 "가능" 으로 저장한 회차가 남아 가짜 빈자리가 된다 → 테마를 **회차 0개 + `closeMissing`** 으로 넘겨 그날 회차를 마감으로 내린다(D45).
 * 응답에 테마 일부만 오면 빠진 테마도 같은 방식이다. 아직 안 연 날(+13 이 늦게 열리는 경우)은 저장된 행이 없어서 아무 일도 안 생긴다.
 * ⚠️ 매장 쪽 일시 오류로 비어 왔다가 돌아오면 실제 가능 회차가 "마감 → 가능" 으로 다시 바뀌며 전이가 날 수 있다(D45 "틀리면 어느 쪽으로").
 * 그리고 **처음 볼 때 이미 매진인 날**은 시각을 몰라 행을 못 만든다 — 그날엔 감시를 걸 수 없다(작업명세서 ▶ 숙제).
 *
 * 응답에 **날짜와 지점이 없다** — 되읽을 자리가 테마 번호(숨은 `id` · 회차 `id`)뿐이다. 딥띵커는 `location_id` 를 무시한다(지점이 하나라 상관없다).
 * 응답 안 주석(`<!--<li>…`)에 회차 모양이 있어서 주석부터 걷는다.
 */
data class WpBookingSite(
    val brand: String,
    val host: String,
    val branchName: String,
    val key: String,
    val locationId: String,
    /** 비우면 지점 전 테마가 한 번에 온다(쇼룸). 딥띵커는 화면처럼 테마를 넣는다 */
    val themeParam: String,
    /** 테마 번호 → 이름. 응답에 지점이 없어서 이 집합으로 대조한다 — 새 테마가 생기면 끊겨 여기 한 줄을 더한다 */
    val themes: Map<String, String>,
    /** 오늘~+13 — 화면 `maxDate` · 공지 "오늘 기준 2주일 치 오픈" (2026-10-22) */
    val openDays: Int = 14,
) {
    val baseUrl: String get() = "https://$host"
    val storeRef: StoreRef get() = StoreRef(key = key, brand = brand, branchName = branchName)
}

object WpBookingSites {

    val DEEPTHINKER = WpBookingSite(
        brand = "딥띵커",
        host = "deepthinker.co.kr",
        branchName = "홍대점",
        key = "deepthinker-hongdae",
        locationId = "24",
        themeParam = "1727",
        themes = mapOf("1727" to "옹이의 꿈"),
    )

    /** 플레이방탈은 showroom406 · 407 · 408.com 도 걸었지만 그 도메인들은 DNS 가 없다 — 404 하나가 쇼룸이다 */
    val SHOWROOM404 = WpBookingSite(
        brand = "쇼룸404",
        host = "showroom404.com",
        branchName = "홍대점",
        key = "showroom404-hongdae",
        locationId = "15",
        themeParam = "",
        themes = linkedMapOf("648" to "한라아파트 4동 404호", "653" to "PIG", "8281" to "꼬치 진다", "8284" to "블랙홀"),
    )

    val ALL = listOf(DEEPTHINKER, SHOWROOM404)
}

class WpBookingCrawlException(message: String) : RuntimeException(message)

@Component
class WpBookingCrawler(private val rateLimiter: HostRateLimiter) {

    private val clients = ConcurrentHashMap<String, RestClient>()

    fun fetch(site: WpBookingSite, date: LocalDate): DaySchedule = parse(site, date, request(site, date))

    private fun request(site: WpBookingSite, date: LocalDate): String =
        rateLimiter.throttled(site.host) {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("action", "filter_rooms")
                add("location_id", site.locationId)
                add("theme_id", site.themeParam)
                add("currentDate", date.toString())
            }
            clients.computeIfAbsent(site.baseUrl) { CrawlHttp.client(it) }.post()
                .uri("/wp-admin/admin-ajax.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    /** 테스트가 픽스처로 부른다 */
    fun parse(site: WpBookingSite, date: LocalDate, body: String): DaySchedule {
        val html = COMMENT.replace(body, "")
        if ("no_room_msg" in html) throw WpBookingCrawlException("\"이용 가능한 객실이 없습니다\" — 지점 값이 틀렸다: ${site.brand} $date")

        val seen = html.split(BLOCK).drop(1).map { block -> theme(site, date, block) }
        if (html.isNotBlank() && seen.isEmpty()) throw WpBookingCrawlException("빈 응답도 테마도 아니다 — 모양이 바뀌었다: ${site.brand} $date ${html.trim().take(120)}")

        // 빈 응답(매진된 날)이거나 응답에 빠진 테마 — 회차 0개 + closeMissing 으로 그날 회차를 마감으로 내린다 (D45)
        val missing = site.themes.filterKeys { id -> seen.none { it.externalId == id } }.map { (id, name) ->
            ThemeSchedule(
                externalId = id, themeName = name, posterUrl = null, genre = null, capacity = null, runningMinutes = null,
                horrorLevel = null, difficulty = null, slots = emptyList(), closeMissing = true,
            )
        }
        return DaySchedule(site.storeRef, date, null, site.openDays, seen + missing)
    }

    private fun theme(site: WpBookingSite, date: LocalDate, block: String): ThemeSchedule {
        val id = HIDDEN_ID.find(block)?.groupValues?.get(1) ?: throw WpBookingCrawlException("테마 번호가 없다: ${site.brand} $date")
        val name = site.themes[id] ?: throw WpBookingCrawlException("설정에 없는 테마 $id(${TITLE.find(block)?.groupValues?.get(1)?.trim()}): ${site.brand} $date — WpBookingSites 에 더한다")

        val slots = SLOT.findAll(block).map { m ->
            val attrs = m.groupValues[2]
            if (m.groupValues[1] != id) throw WpBookingCrawlException("회차의 테마 번호가 다르다: $name($id) $date — ${m.groupValues[1]}")
            val label = TIME.find(attrs)?.groupValues?.get(1) ?: throw WpBookingCrawlException("회차 시각이 없다: $name $date")
            val cls = CLASS.find(attrs)?.groupValues?.get(1)
            val available = when (cls) {
                "submit" -> true
                "disable" -> false
                else -> throw WpBookingCrawlException("모르는 회차 클래스 '$cls': $name $date $label")
            }
            Slot(LocalTime.parse(label.padStart(5, '0')), available)
        }.toList()
        if (slots.isEmpty()) throw WpBookingCrawlException("테마는 왔는데 회차가 0개 — 모양이 바뀌었다: $name $date")

        return ThemeSchedule(
            externalId = id,
            themeName = name,
            posterUrl = POSTER.find(block)?.groupValues?.get(1),
            genre = null,
            capacity = CAPACITY.find(block)?.groupValues?.let { "${it[1]}~${it[2]}명" },
            runningMinutes = MINUTES.find(block)?.groupValues?.get(1)?.toInt(),
            horrorLevel = null,
            difficulty = null,
            slots = slots.sortedBy { it.time },
            closeMissing = true,
        )
    }

    companion object {
        private val COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
        private val BLOCK = Regex("""<div class="theme[0-9]*-detail-info">""")
        private val HIDDEN_ID = Regex("""name="id" id="id" value="([0-9]+)"""")
        private val TITLE = Regex("""<h2>([^<]*)</h2>""")
        private val SLOT = Regex("""<a href="javascript:void\(0\)" id="([0-9]+)"([^>]*)>""")
        private val TIME = Regex("""data-time="([0-9]{1,2}:[0-9]{2})"""")
        private val CLASS = Regex("""class="([^"]*)"""")
        private val CAPACITY = Regex("""([0-9]+)\s*~\s*([0-9]+)\s*명""")
        private val MINUTES = Regex("""([0-9]{2,3})\s*분""")
        private val POSTER = Regex("""<div class="theme-image">\s*<figure>\s*<img src="([^"]+)"""")
    }
}

/** 지점 × 날짜 = 요청 하나 (D15) */
class WpBookingAdapter(private val site: WpBookingSite, private val crawler: WpBookingCrawler) : StoreAdapter {

    override val host = site.host
    override val brand = site.brand
    override val branches = listOf(site.storeRef)

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(site.openDays).map { date ->
            FetchUnit("${site.brand} ${site.branchName} $date", site.key, date) { crawler.fetch(site, date) }
        }
}

/** [WpBookingSites.ALL] 의 매장마다 어댑터 빈을 만든다 — 신비웹과 같은 이유다 (D41) */
@Component
class WpBookingAdapterRegistrar : BeanDefinitionRegistryPostProcessor {

    override fun postProcessBeanDefinitionRegistry(registry: BeanDefinitionRegistry) {
        WpBookingSites.ALL.forEach { site ->
            registry.registerBeanDefinition(
                "wpBookingAdapter:${site.key}",
                BeanDefinitionBuilder.genericBeanDefinition(WpBookingAdapter::class.java)
                    .addConstructorArgValue(site)
                    .addConstructorArgReference("wpBookingCrawler")
                    .beanDefinition,
            )
        }
    }

    override fun postProcessBeanFactory(beanFactory: ConfigurableListableBeanFactory) = Unit
}
