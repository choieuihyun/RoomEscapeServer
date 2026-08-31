package com.my_dream.server.crawler.bitphobia

import com.my_dream.server.crawler.HostRateLimiter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate

/** `robots.txt` 는 `Allow: /` 이고 보호 장치도 없다 (2026-08-28·31 확인). */
@Component
class BitphobiaClient(private val rateLimiter: HostRateLimiter) {

    private val client = RestClient.create(BASE_URL)

    /** 지점 하루치. 그 지점 테마가 전부 온다. */
    fun fetchReservationPage(branch: BitphobiaBranch, date: LocalDate): String =
        rateLimiter.throttled(branch.host) {
            client.get()
                .uri { uri ->
                    uri.path(PATH)
                        .queryParam("go", "rev.main")
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
        private const val BASE_URL = "https://" + BitphobiaBranch.HOST
        private const val PATH = "/layout/res/home.php"
    }
}
