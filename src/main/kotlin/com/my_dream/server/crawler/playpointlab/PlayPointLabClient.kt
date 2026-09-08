package com.my_dream.server.crawler.playpointlab

import com.my_dream.server.crawler.HostRateLimiter
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.LocalDate

/**
 * `robots.txt` 는 `User-agent: *` / `Allow:/` — 전면 허용이다 (2026-09-04 확인). 보호장치 없음.
 *
 * **이 매장만 POST 다.** 나머지 일곱 곳은 전부 GET 이라 `RestClient.get()` 을 쓰는데,
 * 여기는 화면이 `$.ajax({type:"POST"})` 로 폼 인코딩을 보낸다. GET 으로 바꿔 보내면
 * **404 가 아니라 빈 조각이 돌아온다** — 조용히 "회차 0개" 가 되는 종류라 흉내를 정확히 낸다.
 */
@Component
class PlayPointLabClient(private val rateLimiter: HostRateLimiter) {

    private val client = RestClient.create(BASE_URL)

    /**
     * 지점 하루치. `room` 은 빈 값이면 그 지점 테마가 전부 온다.
     *
     * ⚠️ **[date] 는 반드시 창 안의 날짜여야 한다.** 사이트가 이상한 날짜를 거절하지 않고
     * 그럴듯한 응답을 준다 — 자세한 것은 [PlayPointLabCrawler] 주석 참고.
     */
    fun fetchBookingList(branch: PlayPointLabBranch, date: LocalDate): String =
        rateLimiter.throttled(branch.host) {
            val form = LinkedMultiValueMap<String, String>().apply {
                add("date", date.toString())
                add("store", branch.id.toString())
                add("room", "")
            }
            client.post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값(Java-http-client/…)이 대신 나간다
                .header("User-Agent", "")
                .body(form)
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    companion object {
        private const val BASE_URL = "https://" + PlayPointLabBranch.HOST
        private const val PATH = "/booking/booking_list_new"
    }
}
