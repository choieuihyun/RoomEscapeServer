package com.my_dream.server.crawler.diaegg

import com.my_dream.server.crawler.HostRateLimiter
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate

/**
 * `robots.txt` 는 `User-agent: *` / `Allow:/` — 전면 허용이다 (2026-09-04 확인).
 * 보호장치는 없다. `PHPSESSID` 쿠키를 주지만 **없어도 응답이 같아서** 들고 다니지 않는다 —
 * 안 써도 되는 상태를 굳이 만들면 나중에 "쿠키 때문인가" 를 의심하게 된다.
 */
@Component
class DiaeggClient(private val rateLimiter: HostRateLimiter) {

    private val client = RestClient.create(BASE_URL)

    /** 지점 하루치. 그 지점 테마가 전부 온다 — `s_theme_num` 은 화면 필터일 뿐이라 안 넘긴다. */
    fun fetchReservationPage(branch: DiaeggBranch, date: LocalDate): String =
        rateLimiter.throttled(branch.host) {
            client.get()
                .uri { uri ->
                    uri.path(PATH)
                        .queryParam("go", "rev.make")
                        .queryParam("s_zizum", branch.id)
                        .queryParam("rev_days", date)
                        .build()
                }
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값(Java-http-client/…)이 대신 나간다
                .header("User-Agent", "")
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    companion object {
        private const val BASE_URL = "https://" + DiaeggBranch.HOST
        private const val PATH = "/layout/res/home.php"
    }
}
