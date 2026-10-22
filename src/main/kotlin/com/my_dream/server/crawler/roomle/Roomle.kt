package com.my_dream.server.crawler.roomle

import com.my_dream.server.crawler.CrawlHttp
import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.Slot
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.StoreRef
import com.my_dream.server.crawler.ThemeSchedule
import com.my_dream.server.crawler.openWithin
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import java.time.LocalDate
import java.time.LocalTime

/**
 * **룸엘이스케이프** 홍대 1호점 (`www.roomlescape.com` · 자체 솔루션 · 신비웹 아님 · 2026-10-22 조사).
 *
 * ```
 * POST /rev.theme.time.php   themeSeq=T&reservationDate=YYYY-MM-DD   → 테마 하나 하루치 (조각)
 *   <a href="javascript:selectTime('5', '2026-10-22', '78', '12:10')"><li class='possible' data-timeseq="78">12:10</li></a>   ← 가능
 *   <li class='impossible' data-timeseq="76">09:20</li>                                                                    ← 불가
 * ```
 *
 * **회차 요청에 지점 파라미터가 없다** — `themeSeq` 가 지점을 가리지 않는 전역 번호다. 그래서 테마를 설정에 박는다.
 * 테마 목록(`POST /rev.theme.list.php showroomSeq=2`)은 매 바퀴 묻지 않는다 — 두 개뿐이고, 바뀌면 회차 요청이 빈 응답으로 끊긴다.
 * 같은 사이트의 신림 지점들은 테마마다 `data-otherurl` 이 네이버예약이다 — 홍대 1호점만 자체 예약이라 이 지점만 넣었다.
 *
 * 창은 오늘~+6. 서버가 직접 검사한다 — 창 밖 · 어제는 `<script>alert('조회할 수 없는 날입니다.');</script>` 다.
 */
object Roomle {
    const val BRAND = "룸엘이스케이프"
    const val HOST = "www.roomlescape.com"
    const val BRANCH_NAME = "홍대 1호점"
    const val KEY = "roomle-hongdae1"
    const val OPEN_DAYS = 7

    /** `themeSeq` → 이름. 사이트 이름은 `[홍대1호점]베니` 처럼 지점이 앞에 붙어 있다 — 지점 이름과 겹쳐서 뗐다 */
    val THEMES = linkedMapOf("5" to "베니", "6" to "퇴근길")

    val storeRef = StoreRef(key = KEY, brand = BRAND, branchName = BRANCH_NAME)
}

class RoomleCrawlException(message: String) : RuntimeException(message)

@Component
class RoomleCrawler(private val rateLimiter: HostRateLimiter) {

    private val client by lazy { CrawlHttp.client("https://${Roomle.HOST}") }

    fun fetch(themeSeq: String, date: LocalDate): DaySchedule = parse(themeSeq, date, request(themeSeq, date))

    private fun request(themeSeq: String, date: LocalDate): String =
        rateLimiter.throttled(Roomle.HOST) {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("themeSeq", themeSeq)
                add("reservationDate", date.toString())
            }
            client.post()
                .uri("/rev.theme.time.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    /** 테스트가 픽스처로 부른다 */
    fun parse(themeSeq: String, date: LocalDate, html: String): DaySchedule {
        val name = Roomle.THEMES[themeSeq] ?: throw RoomleCrawlException("설정에 없는 테마: $themeSeq")
        // 없는 테마도 200 · 0바이트다 — 빈 응답은 "회차 없음" 이 아니라 실패다
        if (html.isBlank()) throw RoomleCrawlException("빈 응답 — 테마가 없어졌거나 요청 모양이 바뀌었다: $name $date")
        if ("조회할 수 없는 날" in html) throw RoomleCrawlException("창 밖 날짜(매장이 거절): $name $date")

        val slots = SLOT.findAll(html).map { m ->
            val (linkTheme, linkDate) = m.groupValues[1] to m.groupValues[2]
            val possible = m.groupValues[3] == "possible"
            // 링크(selectTime)가 있는 것이 가능 — 클래스와 링크가 같이 다녀야 한다. 어긋나면 마크업이 바뀐 것이다
            if (possible != linkTheme.isNotEmpty()) throw RoomleCrawlException("클래스와 링크가 어긋났다: $name $date ${m.value.take(120)}")
            if (possible && (linkTheme != themeSeq || linkDate != date.toString())) {
                throw RoomleCrawlException("회차 링크의 테마·날짜가 다르다: 요청=$themeSeq $date 링크=$linkTheme $linkDate")
            }
            val time = runCatching { LocalTime.parse(m.groupValues[4].padStart(5, '0')) }.getOrNull()
                ?: throw RoomleCrawlException("시각을 못 읽었다: $name $date '${m.groupValues[4]}'")
            Slot(time, available = possible)
        }.toList()
        if (slots.isEmpty()) throw RoomleCrawlException("회차가 하나도 없다 — 마크업이 바뀌었다: $name $date")

        val theme = ThemeSchedule(
            externalId = themeSeq, themeName = name, posterUrl = null, genre = null, capacity = null,
            runningMinutes = null, horrorLevel = null, difficulty = null, slots = slots.sortedBy { it.time },
        )
        return DaySchedule(Roomle.storeRef, date, null, Roomle.OPEN_DAYS, listOf(theme))
    }

    companion object {
        /** 링크(선택)가 바로 앞에 붙은 li 하나. 링크가 없으면 1·2번 묶음이 빈 문자열이다 */
        private val SLOT = Regex(
            """(?:<a href="javascript:selectTime\('([0-9]+)',\s*'([0-9-]{10})'[^"]*">\s*)?<li class='(possible|impossible)'[^>]*>\s*([0-9]{1,2}:[0-9]{2})\s*</li>""",
        )
    }
}

/** 테마 × 날짜 = 요청 하나 (D15) — 지점 요청이 없는 사이트다 */
@Component
class RoomleAdapter(private val crawler: RoomleCrawler) : StoreAdapter {

    override val host = Roomle.HOST
    override val brand = Roomle.BRAND
    override val branches = listOf(Roomle.storeRef)

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        dates.openWithin(Roomle.OPEN_DAYS).flatMap { date ->
            Roomle.THEMES.map { (seq, name) ->
                // themeExternalId 를 채워야 감시 빠른 확인(D27)이 이 테마 요청을 고른다 — 크롤러가 넣는 externalId 와 같은 값
                FetchUnit("${Roomle.BRAND} ${Roomle.BRANCH_NAME} $name $date", Roomle.KEY, date, seq) { crawler.fetch(seq, date) }
            }
        }
}
