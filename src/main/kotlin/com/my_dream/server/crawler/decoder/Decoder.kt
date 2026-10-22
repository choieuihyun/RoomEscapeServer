package com.my_dream.server.crawler.decoder

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
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap

/**
 * **디코더** (`decoder.kr` · 워드프레스 + Bookly 9.2 예약 플러그인 · 2026-10-22 조사). 지점 하나 · http 만.
 *
 * ```
 * GET  /book-room1/                         → PHPSESSID 쿠키 + data-form_id (페이지를 열 때마다 바뀐다)
 * GET  /wp-admin/admin-ajax.php?action=ab_render_time&form_id=F&selected_date=D   (쿠키 필요)
 *   → {"success":true,"has_slots":true,"date_min":[2026,9,22],"date_max":[2026,11,21],"disabled_days":[[…]],
 *      "slots":{"2026-10-22":"<button class=\"ab-available-hour\" data-staff_id=\"4\" value=\"2026-10-22 11:30:00\">…"}}
 * ```
 *
 * **테마 페이지가 예약 화면 자체이고, 그 화면이 회차를 부르는 요청이 admin-ajax 다** — 사이트가 스스로 부르는 길만 쓴다.
 * robots 는 `Allow:/` 다.
 *
 * ### 한 작업에 요청이 둘일 때가 있다 — D15 "작업 하나 = 요청 하나" 의 예외 (Reserv 와 같은 종류)
 * 회차 요청은 **세션(쿠키 + form_id)** 이 있어야 한다. 세션은 테마 페이지를 받아야 생긴다. 그래서 테마마다 첫 작업은
 * 페이지 GET + 회차 GET 두 건이고, 세션은 [SESSION] 동안 들고 쓴다. 회차 응답은 **한 달치**라 [FRESH] 동안 같은 바퀴의
 * 날짜 작업들이 나눠 쓴다. 두 건 다 [HostRateLimiter] 를 따로 지나므로 속도 규칙(D13)은 그대로다 — 요청 수는 작업 수보다 적게 나간다.
 *
 * ### 🕳️ 빈 결과가 실패와 같은 모양이다
 * form_id 가 틀리거나 세션이 끊기면 `{"success":true,"has_slots":false,"slots":[]}` 가 온다 — 정상 "빈 하루" 처럼 보인다.
 * 그래서 **`has_slots:false` 는 실패로 보고 세션을 버린다**(다음 바퀴가 새로 받는다 · 같은 작업 안에서 다시 묻지 않는다).
 * 물은 날짜가 `slots` 에 없으면 실패다 — **`disabled_days` 에 있는 날(휴무 · 12-04 금 관찰)만 회차 0개 정상**이다.
 *
 * ⚠️ 응답의 월은 **0부터 센다**(JS `Date` 식) — `[2026,9,22]` 는 10-22 다. 회차 시각은 "11:30 am" 글자가 아니라 `value` 에서 읽는다.
 * 응답엔 테마 이름이 없다 — 회차마다 붙은 **`data-staff_id`**(방 번호 · room1 4 · room2 5 · rubato 6)로 어느 테마 응답인지 대조한다.
 */
data class DecoderTheme(
    /** 페이지 속 `attributes.service_id` — 고정값이다. form_id 는 페이지마다 바뀌어서 키로 못 쓴다 */
    val serviceId: String,
    val name: String,
    val page: String,
    /** 회차 버튼의 `data-staff_id`. 세션이 엉켜 다른 방 응답이 오면 이것으로 끊는다 */
    val staffId: String,
)

object Decoder {
    const val BRAND = "디코더"
    const val HOST = "decoder.kr"
    const val BRANCH_NAME = "홍대점"
    const val KEY = "decoder-hongdae"

    /** 응답의 date_min~date_max — 오늘~+60 (2026-10-22 실측). 계층 폴링이 +14 까지만 물어서 이 값이 요청을 늘리지 않는다 (D21) */
    const val OPEN_DAYS = 61

    /**
     * **Tempo Rubato(service 3 · `/book-rubato/` · staff 6)는 뺐다** — 10월 · 11월 270회차가 **전부** booked 다(2026-10-22).
     * 두 달 뒤까지 한 자리도 없는 건 매진이 아니라 쉬는 테마로 보인다. 넣어 두면 늘 매진인 줄이 뜨고, 다시 열리는 날 하루치가
     * 한꺼번에 풀린다. 회차가 생기면 여기 한 줄로 되살린다 (싸인 EXPRESS 와 같다)
     */
    val THEMES = listOf(
        DecoderTheme("1", "Introverted Thief", "/book-room1/", "4"),
        DecoderTheme("2", "Furious Revenge", "/book-room2/", "5"),
    )

    val storeRef = StoreRef(key = KEY, brand = BRAND, branchName = BRANCH_NAME)
}

/** [sessionBroken] — 세션이 끊긴 모양(`has_slots:false` · `success:false`)이라 세션을 버려야 하는 실패 */
class DecoderCrawlException(message: String, val sessionBroken: Boolean = false) : RuntimeException(message)

@Component
class DecoderCrawler(private val rateLimiter: HostRateLimiter) {

    private val client: RestClient by lazy { CrawlHttp.client("http://${Decoder.HOST}") }
    private val json = JsonMapper.builder().build()

    private data class Session(val cookie: String, val formId: String, val at: Instant)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val months = ConcurrentHashMap<Pair<String, YearMonth>, Pair<Instant, String>>()
    internal var clock: Clock = Clock.systemDefaultZone()

    /**
     * 실패해도 **그 달 응답은 버리지 않는다** — 버리면 같은 바퀴의 남은 날짜 작업이 전부 페이지 + 회차를 다시 물어,
     * 이미 고장 난 사이트에 요청이 곱절로 간다(D39 의 뜻과 반대). 그 달 응답은 [FRESH] 동안 그대로 두어 남은 작업이 요청 없이 실패하게 하고,
     * **세션이 끊긴 모양일 때만 세션을 버린다** — 다음 바퀴가 새 세션으로 시작한다. 날짜 하나의 문제(날짜·방 어긋남)는 아무것도 안 버린다
     */
    fun fetch(theme: DecoderTheme, date: LocalDate): DaySchedule {
        val body = month(theme, date)
        return try {
            parse(theme, date, body, LocalDate.now(clock))
        } catch (e: DecoderCrawlException) {
            if (e.sessionBroken) sessions.remove(theme.serviceId)
            throw e
        }
    }

    private fun month(theme: DecoderTheme, date: LocalDate): String {
        val key = theme.serviceId to YearMonth.from(date)
        months[key]?.let { (at, body) -> if (Duration.between(at, clock.instant()) < FRESH) return body }
        val session = session(theme)
        val body = rateLimiter.throttled(Decoder.HOST) {
            client.get()
                .uri { it.path("/wp-admin/admin-ajax.php").queryParam("action", "ab_render_time").queryParam("form_id", session.formId).queryParam("selected_date", date).build() }
                .header(HttpHeaders.COOKIE, session.cookie)
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }
        months[key] = clock.instant() to body
        return body
    }

    private fun session(theme: DecoderTheme): Session {
        sessions[theme.serviceId]?.let { if (Duration.between(it.at, clock.instant()) < SESSION) return it }
        val page = rateLimiter.throttled(Decoder.HOST) {
            client.get().uri(theme.page).retrieve().toEntity(String::class.java)
        }
        val html = page.body.orEmpty()
        val cookie = page.headers[HttpHeaders.SET_COOKIE].orEmpty().firstNotNullOfOrNull { SESSION_COOKIE.find(it)?.value }
            ?: throw DecoderCrawlException("세션 쿠키가 없다: ${theme.name} ${theme.page}")
        val formId = FORM_ID.find(html)?.groupValues?.get(1) ?: throw DecoderCrawlException("form_id 가 없다 — 페이지가 바뀌었다: ${theme.name}")
        val service = SERVICE_ID.find(html)?.groupValues?.get(1)
        if (service != theme.serviceId) throw DecoderCrawlException("페이지의 테마 번호가 다르다: ${theme.name} 기대=${theme.serviceId} 페이지=$service")
        return Session(cookie, formId, clock.instant()).also { sessions[theme.serviceId] = it }
    }

    /** 테스트가 픽스처로 부른다. 한 달치 응답에서 [date] 하루를 꺼낸다. [today] 는 "지난 회차가 빠져 0개" 를 허락하는 날이다 */
    fun parse(theme: DecoderTheme, date: LocalDate, body: String, today: LocalDate = LocalDate.now()): DaySchedule {
        val root = runCatching { json.readTree(body) }.getOrNull()
            ?: throw DecoderCrawlException("JSON 이 아닌 응답: ${theme.name} $date")
        if (!root.path("success").asBoolean(false)) throw DecoderCrawlException("거절됨: ${theme.name} $date ${body.take(120)}", sessionBroken = true)
        if (!root.path("has_slots").asBoolean(false)) {
            throw DecoderCrawlException("has_slots:false — form_id 나 세션이 틀렸을 때와 같은 모양이라 '빈 달' 로 믿지 않는다: ${theme.name} $date", sessionBroken = true)
        }

        val min = day(root.path("date_min")) ?: throw DecoderCrawlException("date_min 이 없다: ${theme.name}")
        val max = day(root.path("date_max")) ?: throw DecoderCrawlException("date_max 가 없다: ${theme.name}")
        // 오늘은 예외 — 지난 회차가 목록에서 **빠지는** 매장이라, 밤늦게 마지막 회차가 지나면 오늘이 응답에서 사라지거나
        // date_min 이 내일로 넘어갈 수 있다(아직 못 본 모양 · 추정). 그때 매 바퀴 실패로 남기지 않고 "오늘 회차 0개" 로 받는다.
        // 세션 고장은 위 has_slots 검사가 따로 잡는다
        if (date.isAfter(max) || (date.isBefore(min) && date != today)) throw DecoderCrawlException("응답 창($min~$max) 밖 날짜: ${theme.name} $date")
        val disabled = root.path("disabled_days").toList().mapNotNull(::day).toSet()

        val html = root.path("slots").path(date.toString())
        val slots = if (html.isMissingNode || html.isNull) {
            if (date !in disabled && date != today) throw DecoderCrawlException("물은 날짜가 응답에 없다(휴무 표시도 없음): ${theme.name} $date")
            emptyList()   // 휴무 · 다 지난 오늘 — 테마는 남기고 회차만 비운다 (D22)
        } else {
            slots(theme, date, html.asString(""), today)
        }

        return DaySchedule(
            store = Decoder.storeRef,
            date = date,
            // date_min 이 아니라 오늘부터 센다 — 밤늦게 date_min 이 내일로 넘어가도 61 로 맞아 "범위가 바뀌었다" 경고가 안 뜬다
            reservationRangeDays = (max.toEpochDay() - today.toEpochDay() + 1).toInt(),
            openDays = Decoder.OPEN_DAYS,
            themes = listOf(
                ThemeSchedule(
                    externalId = theme.serviceId, themeName = theme.name, posterUrl = null, genre = null, capacity = null,
                    runningMinutes = null, horrorLevel = null, difficulty = null, slots = slots,
                ),
            ),
        )
    }

    private fun slots(theme: DecoderTheme, date: LocalDate, html: String, today: LocalDate): List<Slot> {
        val slots = BUTTON.findAll(html).mapNotNull { m ->
            val attrs = m.groupValues[1]
            val classes = CLASS.find(attrs)?.groupValues?.get(1).orEmpty().split(' ')
            if ("ab-available-hour" !in classes) return@mapNotNull null   // 날짜 머리 버튼(ab-available-day)
            val value = VALUE.find(attrs)?.groupValues ?: throw DecoderCrawlException("회차 value 가 없다: ${theme.name} $date")
            if (value[1] != date.toString()) throw DecoderCrawlException("회차 날짜가 다르다: ${theme.name} 요청=$date 회차=${value[1]}")
            val staff = STAFF.find(attrs)?.groupValues?.get(1)
            if (staff != theme.staffId) throw DecoderCrawlException("다른 방 응답이다: ${theme.name} 기대 staff=${theme.staffId} 응답=$staff")
            // 불가는 disabled 속성과 booked 클래스가 함께 붙는다. 둘 중 하나라도 있으면 불가로 친다
            Slot(LocalTime.parse(value[2]), available = "disabled" !in attrs && "booked" !in classes)
        }.toList()
        // 오늘 지난 회차는 목록에서 빠진다(다른 매장과 반대). 하루가 다 지나 0개가 되는 건 정상이다
        if (slots.isEmpty() && date != today) throw DecoderCrawlException("회차가 0개 — 마크업이 바뀌었다: ${theme.name} $date")
        return slots.sortedBy { it.time }
    }

    /** `[2026, 9, 22]` → 2026-10-22. 월이 0부터다 */
    private fun day(node: JsonNode): LocalDate? {
        val parts = node.toList().map { it.asInt(-1) }
        if (parts.size != 3 || parts.any { it < 0 }) return null
        return runCatching { LocalDate.of(parts[0], parts[1] + 1, parts[2]) }.getOrNull()
    }

    companion object {
        private val FRESH: Duration = Duration.ofSeconds(60)
        /** 세션을 얼마나 들고 쓰나. 근거 없는 어림이다 — 끊기면 has_slots:false 로 드러나 버리고 새로 받는다 */
        private val SESSION: Duration = Duration.ofMinutes(20)

        private val SESSION_COOKIE = Regex("""PHPSESSID=[^;]+""")
        private val FORM_ID = Regex("""data-form_id="([0-9a-f]+)"""")
        private val SERVICE_ID = Regex("""service_id"\s*:\s*"?([0-9]+)""")
        private val BUTTON = Regex("""<button([^>]*)>""")
        private val CLASS = Regex("""class="([^"]*)"""")
        private val VALUE = Regex("""value="([0-9-]{10}) ([0-9]{2}:[0-9]{2}):[0-9]{2}"""")
        private val STAFF = Regex("""data-staff_id="([0-9]+)"""")
    }
}

/** 테마 × 날짜 = 작업 하나. 실제 요청은 세션 · 한 달치 캐시 때문에 그보다 적다 — 위 예외 설명 */
// ⛔ 수집 목록에서 뺐다 (2026-10-22 14:49 · 배포 첫 바퀴) — 운영 서버(오라클 · 일본 IP)에 매장 호스팅이 봇 확인 화면(cupid.js · 403)을 보냈다.
//    사무실 PC 에서는 진짜 페이지였다. 확인 화면을 풀어 지나가면 우회라 하지 않는다 (CLAUDE.md 수집 윤리 · 매장 정보.md 장애 기록).
//    설정 · 파서 · 픽스처 테스트는 남긴다 — 풀리면 아래에 @Component 한 줄을 되살린다.
class DecoderAdapter(private val crawler: DecoderCrawler) : StoreAdapter {

    override val host = Decoder.HOST
    override val brand = Decoder.BRAND
    override val branches = listOf(Decoder.storeRef)

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(Decoder.OPEN_DAYS).flatMap { date ->
            Decoder.THEMES.map { theme ->
                // themeExternalId 를 채워야 감시 빠른 확인(D27)이 이 테마 요청을 고른다
                FetchUnit("${Decoder.BRAND} ${theme.name} $date", Decoder.KEY, date, theme.serviceId) { crawler.fetch(theme, date) }
            }
        }
}
