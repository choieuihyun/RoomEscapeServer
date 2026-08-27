package com.my_dream.server.crawler.play33

import com.my_dream.server.crawler.HostRateLimiter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate

@Component
class Play33Client(private val rateLimiter: HostRateLimiter) {

    private val client = RestClient.create(BASE_URL)

    /**
     * 지점 하나의 특정 날짜 예약 페이지 HTML.
     * `theme` 파라미터는 화면 필터일 뿐이라 넘기지 않는다 — 생략하면 그 지점 전체 테마가 함께 온다.
     * 쿠키·토큰 없이 GET 만으로 응답한다.
     */
    /**
     * **속도 제한을 여기서 건다.** 수집기가 아니라 요청이 실제로 나가는 자리다 —
     * 위쪽에서 조절하면 어댑터가 한 작업 안에서 두 번 요청하는 순간 조용히 두 배가 나간다
     * (아키텍처 D15).
     */
    fun fetchReservationPage(branch: Play33Branch, date: LocalDate): String =
        rateLimiter.throttled(branch.host) { request(branch, date) }

    private fun request(branch: Play33Branch, date: LocalDate): String =
        client.get()
            .uri { uri ->
                uri.path("/reservation")
                    .queryParam("branch", branch.id)
                    .queryParam("date", date)
                    .build()
            }
            // 빈 값으로 덮어쓴다. 헤더를 아예 안 보내면 자바가 `Java-http-client/21.0.11` 을
            // 대신 보내서 JDK 버전까지 나간다 — 빈 값이 실제로 제일 덜 남기는 쪽이다.
            .header("User-Agent", "")
            .retrieve()
            .body(String::class.java)
            .orEmpty()

    companion object {
        // 지점 enum 과 같은 값을 본다. 따로 적어 두면 한쪽만 바뀌어도 아무도 모른다
        private const val BASE_URL = "https://" + Play33Branch.HOST
    }
}
