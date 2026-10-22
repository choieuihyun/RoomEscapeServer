package com.my_dream.server.crawler.escapers

import com.my_dream.server.crawler.CrawlHttp
import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.crawler.openWithin
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * **이스케이퍼스** 홍대 1호점 · 2호점 (`www.escapers.co.kr` · Laravel · 2026-10-22 조사 · 제작 토니빌리 — 서울이스케이프룸과 같은 제작사).
 *
 * ```
 * GET  /reservation/{groupPK}   → 세션 쿠키 · <meta name="csrf-token"> · hidden groupPhone · <div id="reservationHiddenData">{after, today, other}
 * POST /reservation/theme       reservationDate=D&groupPK=N&groupPhone=…   (X-CSRF-TOKEN)
 *   → {"data":[{PK,title,…}], "times":{"6":[{themePK,time:"11:00:00",reservation:false}]}}
 * ```
 *
 * ### 🕳️ 판정이 `reservation` 하나가 아니다 — 화면 JS 와 똑같이 다섯을 본다
 * 화면(`reservation.js`)은 아래 중 **하나라도** 참이면 그 회차를 막는다. 뒤의 셋은 회차 응답이 아니라 **예약 페이지 HTML 안의 숨은 JSON** 에서 온다 —
 * 회차 응답만 보면 매장이 하루를 통째로 닫은 날(10-22 1호점)도 전 회차 가능으로 읽힌다.
 * ```
 * reservation == true
 * 오늘이고 이미 지난 시각
 * other[테마][그 회차 epoch]      매장이 회차 하나를 막음
 * today[테마][그날 00:00 epoch]   매장이 그날을 통째로 막음
 * after[테마] <= 그 회차 epoch    그 시점 이후를 전부 막음
 * ```
 * epoch 은 **한국 시각 기준 초**다(화면의 moment 가 브라우저 시간대로 만든다 · 1792594800 = 10-22 00:00 KST).
 *
 * ### 창을 밝히지 않는다 — 그런데 **진짜로 다 열려 있다**
 * 서버가 창을 검사하지 않고 +93 까지 시간표를 준다. 머더파커처럼 **사용자가 사이트에서 "다 열려있음" 을 확인했다**(2026-10-22) —
 * 우리가 늘 보는 범위(+14 · D21)만 묻는다.
 *
 * ### 테마 번호(themePK)가 요일마다 바뀐다
 * 1호점 평일 1/3/5/7/13 · 토 2/4/6/8/12. 같은 방이 요일마다 다른 번호다 — **이름으로 잡는다**(externalId 를 비운다).
 * 응답엔 지점이 없어서, 지점마다 테마 이름 집합을 박아 두고 대조한다. 새 테마가 생기면 예외로 끊겨 여기 한 줄을 더하게 된다.
 *
 * ### 한 작업에 요청이 둘일 때가 있다 — D15 예외 (디코더 · Reserv 와 같은 종류)
 * 회차 요청엔 세션 · CSRF 가 필요하고, 막음 정보(숨은 JSON)도 그 페이지에 있다. 지점 페이지는 [PAGE] 동안 들고 쓴다 —
 * 매장이 막음을 바꾸면 최대 그만큼 늦게 안다.
 */
data class EscapersBranch(
    val groupPK: Int,
    val branchName: String,
    val key: String,
    /** 이 지점 테마 이름 — 응답에 지점이 없어서 이것으로 대조한다 */
    val themes: Set<String>,
) {
    val storeRef: StoreRef get() = StoreRef(key = key, brand = Escapers.BRAND, branchName = branchName)
}

object Escapers {
    const val BRAND = "이스케이퍼스"
    const val HOST = "www.escapers.co.kr"

    /** 우리가 늘 보는 범위 (D21). 매장은 창을 밝히지 않고 실제로 다 연다 — 위 설명 */
    const val OPEN_DAYS = 15

    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    /** groupPK 4(대전)는 네이버로 넘긴다 · 3 은 대구 — 홍대 둘만 넣었다 */
    val BRANCHES = listOf(
        EscapersBranch(1, "홍대 1호점", "escapers-hongdae1", setOf("서커스:공중그네", "싸이코메트리", "RS의 밀실", "붉은머리연맹", "라이브")),
        EscapersBranch(
            2, "홍대 2호점", "escapers-hongdae2",
            setOf("네모네모 마믈메는 무슨밀미 밋멋믈까?", "멍스타그램", "인생낚시", "만약, 당신이 길을 잃었다면", "테라포밍마스"),
        ),
    )
}

class EscapersCrawlException(message: String) : RuntimeException(message)

/** 지점 예약 페이지에서 얻는 것 — 회차 요청 자격(쿠키 · CSRF · groupPhone)과 매장이 막아 둔 자리 */
data class EscapersPage(
    val cookie: String,
    val csrf: String,
    val groupPhone: String,
    val blocks: Blocks,
)

/** 숨은 JSON — 테마번호 → (epoch 초 집합 / 시점) */
data class Blocks(
    val other: Map<String, Set<Long>>,
    val today: Map<String, Set<Long>>,
    val after: Map<String, Long>,
)

@Component
class EscapersCrawler(private val rateLimiter: HostRateLimiter) {

    private val client: RestClient by lazy { CrawlHttp.client("http://${Escapers.HOST}") }
    private val json = JsonMapper.builder().build()
    private val pages = ConcurrentHashMap<Int, Pair<Instant, EscapersPage>>()
    internal var clock: Clock = Clock.system(Escapers.ZONE)

    fun fetch(branch: EscapersBranch, date: LocalDate): DaySchedule {
        val page = page(branch)
        val body = rateLimiter.throttled(Escapers.HOST) {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("reservationDate", date.toString())
                add("groupPK", branch.groupPK.toString())
                add("groupPhone", page.groupPhone)
            }
            client.post()
                .uri("/reservation/theme")
                .header(HttpHeaders.COOKIE, page.cookie)
                .header("X-CSRF-TOKEN", page.csrf)
                .accept(MediaType.APPLICATION_JSON)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }
        return try {
            parse(branch, date, body, page.blocks, LocalDateTime.now(clock))
        } catch (e: EscapersCrawlException) {
            // 세션이 끊겼을 수 있다 — 페이지를 버려 다음 작업이 새로 받게 한다. 같은 작업 안에서 다시 묻지는 않는다
            pages.remove(branch.groupPK)
            throw e
        }
    }

    private fun page(branch: EscapersBranch): EscapersPage {
        pages[branch.groupPK]?.let { (at, page) -> if (Duration.between(at, clock.instant()) < PAGE) return page }
        val response = rateLimiter.throttled(Escapers.HOST) {
            client.get().uri("/reservation/{n}", branch.groupPK).retrieve().toEntity(String::class.java)
        }
        val cookie = response.headers[HttpHeaders.SET_COOKIE].orEmpty()
            .mapNotNull { SESSION_COOKIE.find(it)?.value }
            .joinToString("; ")
            .ifBlank { throw EscapersCrawlException("세션 쿠키가 없다: ${branch.branchName}") }
        return parsePage(branch, response.body.orEmpty(), cookie).also { pages[branch.groupPK] = clock.instant() to it }
    }

    /** 테스트가 픽스처로 부른다 */
    fun parsePage(branch: EscapersBranch, html: String, cookie: String): EscapersPage {
        val csrf = CSRF.find(html)?.groupValues?.get(1) ?: throw EscapersCrawlException("CSRF 토큰이 없다 — 페이지가 바뀌었다: ${branch.branchName}")
        val group = GROUP_PK.find(html)?.groupValues?.get(1)
        if (group != branch.groupPK.toString()) throw EscapersCrawlException("페이지의 지점이 다르다: ${branch.branchName} 기대=${branch.groupPK} 페이지=$group")
        val phone = GROUP_PHONE.find(html)?.groupValues?.get(1) ?: throw EscapersCrawlException("groupPhone 이 없다: ${branch.branchName}")
        val hidden = HIDDEN.find(html)?.groupValues?.get(1) ?: throw EscapersCrawlException("막음 정보(reservationHiddenData)가 없다 — 그대로 두면 막힌 회차가 가능으로 읽힌다: ${branch.branchName}")
        val root = runCatching { json.readTree(hidden) }.getOrNull() ?: throw EscapersCrawlException("막음 정보가 JSON 이 아니다: ${branch.branchName}")
        return EscapersPage(cookie, csrf, phone, Blocks(other = epochSets(root.path("other")), today = epochSets(root.path("today")), after = epochs(root.path("after"))))
    }

    /** 테스트가 픽스처로 부른다. [now] 는 한국 시각 — "오늘 지난 회차" 판정에 쓴다 */
    fun parse(branch: EscapersBranch, date: LocalDate, body: String, blocks: Blocks, now: LocalDateTime): DaySchedule {
        val root = runCatching { json.readTree(body) }.getOrNull() ?: throw EscapersCrawlException("JSON 이 아닌 응답(세션·CSRF 가 끊겼을 수 있다): ${branch.branchName} $date")
        val data = root.path("data").toList()
        // 없는 지점 · 빠진 groupPK 는 {"data":[],"times":[]} 다 — 빈 하루가 아니라 실패다
        if (data.isEmpty()) throw EscapersCrawlException("테마가 0개 — 지점 값이 틀렸거나 응답 모양이 바뀌었다: ${branch.branchName} $date")

        val titleByPk = data.associate { it.path("PK").asLong().toString() to it.path("title").asString("").trim() }
        val stray = titleByPk.values.filterNot { it in branch.themes }
        if (stray.isNotEmpty()) throw EscapersCrawlException("이 지점 테마가 아니다(다른 지점 응답이거나 새 테마): ${branch.branchName} $date — $stray")

        val dayStart = date.atStartOfDay(Escapers.ZONE).toEpochSecond()
        val times = root.path("times")
        val themes = titleByPk.map { (pk, title) ->
            val slots = times.path(pk).toList().map { t ->
                if (t.path("themePK").asLong().toString() != pk) throw EscapersCrawlException("회차의 테마 번호가 다르다: $title $date")
                val time = runCatching { LocalTime.parse(t.path("time").asString("")) }.getOrNull()
                    ?: throw EscapersCrawlException("시각을 못 읽었다: $title $date ${t.path("time")}")
                val at = date.atTime(time).atZone(Escapers.ZONE).toEpochSecond()
                val blocked = t.path("reservation").asBoolean(true) ||
                    (date == now.toLocalDate() && time.isBefore(now.toLocalTime())) ||
                    at in blocks.other[pk].orEmpty() ||
                    dayStart in blocks.today[pk].orEmpty() ||
                    blocks.after[pk]?.let { it <= at } == true
                Slot(time, available = !blocked)
            }.sortedBy { it.time }
            ThemeSchedule(
                // 번호가 요일마다 바뀌어 이름이 키다 — 번호를 넣으면 같은 방이 요일마다 다른 테마가 된다
                externalId = null, themeName = title, posterUrl = null, genre = null, capacity = null,
                runningMinutes = null, horrorLevel = null, difficulty = null, slots = slots,
            )
        }
        if (themes.all { it.slots.isEmpty() }) throw EscapersCrawlException("회차가 하나도 없다 — 응답 모양이 바뀌었다: ${branch.branchName} $date")
        return DaySchedule(branch.storeRef, date, null, Escapers.OPEN_DAYS, themes)
    }

    /** `{"5":{"1792594800":true}}` → 5 → {1792594800}. 비어 있으면 `[]` 로 온다 */
    private fun epochSets(node: JsonNode): Map<String, Set<Long>> =
        if (!node.isObject) emptyMap()
        else node.properties().associate { (pk, v) -> pk to v.properties().filter { it.value.asBoolean(false) }.mapNotNull { it.key.toLongOrNull() }.toSet() }

    /** `{"5":1792594800}` → 5 → 1792594800. 비어 있으면 `[]` 로 온다(지금이 그렇다) */
    private fun epochs(node: JsonNode): Map<String, Long> =
        if (!node.isObject) emptyMap()
        else node.properties().mapNotNull { (pk, v) -> (v.asString("").toLongOrNull() ?: v.asLong(0).takeIf { it > 0 })?.let { pk to it } }.toMap()

    companion object {
        /** 지점 페이지(세션 · 막음 정보)를 들고 쓰는 시간. 짧게 둔다 — 매장이 막음을 바꾸면 최대 이만큼 늦게 안다 */
        private val PAGE: Duration = Duration.ofMinutes(5)

        private val SESSION_COOKIE = Regex("""(?:XSRF-TOKEN|escapers_session)=[^;]+""")
        private val CSRF = Regex("""<meta name="csrf-token"[^>]*content="([^"]+)"""")
        private val GROUP_PK = Regex("""name="groupPK" value="([0-9]+)"""")
        private val GROUP_PHONE = Regex("""name="groupPhone" value="([^"]*)"""")
        private val HIDDEN = Regex("""<div id="reservationHiddenData"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
    }
}

/** 지점 × 날짜 = 작업 하나. 지점 페이지 요청이 5분에 한 번 더 붙는다 — 위 예외 설명 */
@Component
class EscapersAdapter(private val crawler: EscapersCrawler) : StoreAdapter {

    override val host = Escapers.HOST
    override val brand = Escapers.BRAND
    override val branches = Escapers.BRANCHES.map { it.storeRef }

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        Escapers.BRANCHES.flatMap { branch ->
            dates.openWithin(Escapers.OPEN_DAYS).map { date ->
                FetchUnit("${Escapers.BRAND} ${branch.branchName} $date", branch.key, date) { crawler.fetch(branch, date) }
            }
        }
}
