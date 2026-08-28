package com.my_dream.server.crawler.jigubyeol

import com.my_dream.server.crawler.HostRateLimiter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate

@Component
class JigubyeolClient(private val rateLimiter: HostRateLimiter) {

    private val client = RestClient.create(BASE_URL)

    /** 지점 하루치. 그 지점 테마가 전부 온다 — 플레이33·래빗홀과 같은 관례다. */
    fun fetchReservationPage(branch: JigubyeolBranch, date: LocalDate): String =
        rateLimiter.throttled(branch.host) {
            client.get()
                .uri { uri -> uri.path("/reservation").queryParam("branch", branch.id).queryParam("date", date).build() }
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값이 대신 나간다
                .header("User-Agent", "")
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    companion object {
        private const val BASE_URL = "https://" + JigubyeolBranch.HOST
    }
}
