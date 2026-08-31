package com.my_dream.server.crawler.zeroworld

import com.my_dream.server.crawler.HostRateLimiter
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.time.LocalDate

/**
 * 이 매장만 **POST 다.** 화면이 AJAX 로 조각 HTML 을 받아 붙이는 구조라,
 * 우리도 그 조각을 그대로 받는다 — 페이지를 통째로 받는 것보다 가볍다.
 *
 * `act` 는 다섯 개지만 수집에 쓰는 건 셋이다.
 * ```
 * calendar          예약 창 확인용 (수집 중에는 안 쓴다. 조사할 때만)
 * theme_list        지점의 테마 목록 + 메타데이터
 * theme_time_list   테마 하나의 회차
 * ```
 *
 * ⚠️ **`theme_time_list` 만 `s_subj` 를 안 받는다.** 사이트 자바스크립트가 그렇게 짜여 있다 —
 * 빠뜨린 게 아니라 그쪽 설계다. `zizum_num` 만으로 지점이 정해지니 필요가 없는 것으로 보인다.
 * 넣어도 무시될 것 같지만 **화면이 보내는 그대로 보낸다.**
 */
@Component
class ZeroworldClient(private val rateLimiter: HostRateLimiter) {

    private val client = RestClient.create(BASE_URL)

    /** 지점의 테마 목록. `rev_days` 를 받지만 **응답은 날짜와 무관하다**(2026-08-31 확인) */
    fun themeList(branch: ZeroworldBranch, date: LocalDate): String =
        post(
            branch.host,
            "act" to "theme_list",
            "zizum_num" to branch.zizumNum.toString(),
            "rev_days" to date.toString(),
            "theme_num" to "",
            "s_subj" to branch.subject,
        )

    /** 테마 하나의 하루치 회차. **여기가 테마 수만큼 곱해지는 자리다** */
    fun themeTimeList(branch: ZeroworldBranch, themeNum: String, date: LocalDate): String =
        post(
            branch.host,
            "act" to "theme_time_list",
            "zizum_num" to branch.zizumNum.toString(),
            "rev_days" to date.toString(),
            "theme_num" to themeNum,
        )

    private fun post(host: String, vararg form: Pair<String, String>): String =
        rateLimiter.throttled(host) {
            val body = LinkedMultiValueMap<String, String>().apply { form.forEach { (k, v) -> add(k, v) } }
            client.post()
                .uri(PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값이 대신 나간다
                .header("User-Agent", "")
                .body(body)
                .retrieve()
                .body(String::class.java)
                .orEmpty()
        }

    companion object {
        private const val BASE_URL = "https://" + ZeroworldBranch.HOST
        private const val PATH = "/core/res/rev.make.sel.php"
    }
}
