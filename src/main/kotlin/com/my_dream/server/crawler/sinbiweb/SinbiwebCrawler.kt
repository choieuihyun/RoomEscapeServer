package com.my_dream.server.crawler.sinbiweb

import com.my_dream.server.crawler.CrawlHttp
import com.my_dream.server.crawler.DaySchedule
import com.my_dream.server.crawler.FetchUnit
import com.my_dream.server.crawler.HostRateLimiter
import com.my_dream.server.crawler.StoreAdapter
import com.my_dream.server.crawler.openWithin
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

class SinbiwebCrawlException(message: String) : RuntimeException(message)

/**
 * 요청 + 검증. **검증은 여기 한 곳에만 있다** — 매장이 늘어도 빠뜨릴 자리가 없다 (아키텍처 D2 · D41).
 */
@Component
class SinbiwebCrawler(
    private val rateLimiter: HostRateLimiter,
    private val parser: SinbiwebParser,
) {

    /** 매장(호스트)마다 하나. 타임아웃이 붙은 공용 공장에서 만든다 (D39) */
    private val clients = ConcurrentHashMap<String, RestClient>()

    fun fetch(site: SinbiwebSite, branch: SinbiwebBranch, date: LocalDate): DaySchedule =
        validate(site, branch, date, request(site, branch, date))

    private fun request(site: SinbiwebSite, branch: SinbiwebBranch, date: LocalDate): String =
        rateLimiter.throttled(site.host) {
            clients.computeIfAbsent(site.baseUrl) { CrawlHttp.client(it) }.get()
                .uri { uri ->
                    uri.path("/layout/res/home.php")
                        .queryParam("go", "rev.make")
                        .queryParam("rev_days", date)
                        // s_theme_num 은 화면 필터일 뿐이라 안 넘긴다 — 생략하면 지점 테마가 전부 온다
                        .apply { branch.zizum?.let { queryParam("s_zizum", it) } }
                        .build()
                }
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값(Java-http-client/…)이 대신 나간다
                .header("User-Agent", "")
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    /**
     * 받은 페이지가 **물어본 그 지점 · 그 날짜**인지 본다. 하나라도 어긋나면 저장하지 않는다.
     *
     * 테마 0개는 둘로 갈린다 (2026-10-21 돌로 실측):
     * ```
     * 범위 밖 날짜     200 · 테마 0 · "예약가능일이 아닙니다"   → 정상. 빈 하루로 넘긴다
     * 없는 지점(s_zizum=2) 200 · 테마 0 · 문구 없음 · hidden 은 '2' 로 돌아온다 → 예외
     * ```
     * 둘째는 hidden 되읽기까지 통과한다 — **보낸 값을 그대로 돌려줄 뿐이라서다.** 문구 유무가 유일한 구분이다.
     * 조용히 빈 지점으로 두면 영원히 회차가 0개인 지점이 화면에 남는다.
     */
    fun validate(site: SinbiwebSite, branch: SinbiwebBranch, date: LocalDate, html: String): DaySchedule {
        val parsed = parser.parse(html, site)

        val rendered = parsed.page.renderedDate
            ?: throw SinbiwebCrawlException("예약 페이지가 아닌 응답: ${site.brand} ${branch.branchName} $date")
        if (rendered != date) {
            throw SinbiwebCrawlException("요청한 날짜와 렌더된 날짜가 다름: ${site.brand} ${branch.branchName} 요청=$date 응답=$rendered")
        }

        if (site.branchReadback != BranchReadback.NONE && parsed.renderedZizum != branch.zizum) {
            // 황금열쇠는 s_zizum 을 빼면 대구 본점이 답한다. 이게 없으면 강남 두 지점이 대구로 덮인다
            throw SinbiwebCrawlException(
                "요청한 지점과 응답 지점이 다름: ${site.brand} 요청=${branch.branchName}(${branch.zizum}) 응답=${parsed.renderedZizum}",
            )
        }

        if (parsed.page.themes.isEmpty() && !parsed.saysNotOpen) {
            throw SinbiwebCrawlException(
                "테마가 0개인데 '범위 밖' 표시도 없다 — 없는 지점이거나 마크업이 바뀌었다: ${site.brand} ${branch.branchName} $date",
            )
        }

        return DaySchedule(
            store = branch.toStoreRef(site),
            date = date,
            reservationRangeDays = parsed.page.reservationRangeDays,
            openDays = site.openDays,
            themes = parsed.page.themes,
        )
    }
}

/**
 * 신비웹 매장 하나 = 어댑터 하나. 지점 하나를 물으면 그 지점 테마가 전부 온다 — **작업 하나 = 요청 하나**다 (D15).
 *
 * 빈은 [SinbiwebAdapterRegistrar] 가 [SinbiwebSites.ALL] 을 보고 만든다. 손으로 `@Bean` 을 달지 않는다.
 */
class SinbiwebAdapter(private val site: SinbiwebSite, private val crawler: SinbiwebCrawler) : StoreAdapter {

    override val host = site.host
    override val brand = site.brand
    override val branches = site.branches.map { it.toStoreRef(site) }

    override fun plan(dates: List<LocalDate>): List<FetchUnit> =
        site.branches.flatMap { branch ->
            dates.openWithin(site.openDays).map { date ->
                FetchUnit("${site.brand} ${branch.branchName} $date", branch.key, date) { crawler.fetch(site, branch, date) }
            }
        }
}
