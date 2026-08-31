package com.my_dream.server.crawler.pointnine

import com.my_dream.server.crawler.HostRateLimiter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate

/**
 * ⚠️ **이 매장에는 방화벽 제품(nesolution)이 붙어 있다.**
 *
 * `robots.txt` 를 부르면 방화벽이 `alert('Firewall Alert(404)')` 를 만들어 돌려준다 —
 * robots.txt 가 없는 것이지 우리가 막힌 게 아니고, 예약 페이지는 빈 UA 로 그냥 200 이다.
 *
 * **그래도 다른 매장보다 조심한다.** 방화벽이 있다는 건 비정상 트래픽을 보고 있다는 뜻이다.
 * 지점이 3곳이라 한 바퀴 요청이 21건으로 늘었지만 **속도를 올리지 않는다.**
 * 차단당하면 요청을 조절하는 게 아니라 **매장을 대상에서 뺀다** (CLAUDE.md 수집 윤리).
 */
@Component
class PointNineClient(private val rateLimiter: HostRateLimiter) {

    private val client = RestClient.create(BASE_URL)

    /** 지점 하루치. 그 지점 테마가 전부 온다. */
    fun fetchReservationPage(branch: PointNineBranch, date: LocalDate): String =
        rateLimiter.throttled(branch.host) {
            client.get()
                .uri { uri ->
                    uri.path(PATH)
                        .queryParam("go", "rev.make")
                        .queryParam("s_zizum", branch.id)
                        .queryParam("rev_days", date)
                        .build()
                }
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값이 대신 나간다
                .header("User-Agent", "")
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    companion object {
        private const val BASE_URL = "https://" + PointNineBranch.HOST
        private const val PATH = "/layout/res/home.php"
    }
}
