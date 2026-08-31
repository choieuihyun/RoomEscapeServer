package com.my_dream.server.crawler.keyescape

import com.my_dream.server.crawler.HostRateLimiter
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate

class KeyescapeCrawlException(message: String) : RuntimeException(message)

/**
 * 엔드포인트 하나(`run_proc.php`)에 `t` 로 무엇을 물을지 정한다.
 *
 * **속도 제한을 여기서 건다** — 요청이 실제로 나가는 자리다 (아키텍처 D15).
 * 키이스케이프는 한 지점을 긁는 데 요청이 여러 번 필요해서, 위쪽에서 조절하면
 * 조용히 여러 배가 나간다.
 */
@Component
class KeyescapeClient(private val rateLimiter: HostRateLimiter) {

    private val client = RestClient.create(BASE_URL)

    /**
     * **사이트가 JSON 을 `text/html` 로 보낸다.** 그대로 두면 스프링이
     * "이 타입을 읽을 변환기가 없다" 며 거절한다. 문자열로 받아서 직접 읽는다.
     */
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()

    fun themeList(branch: KeyescapeBranch): List<KeyescapeThemeRow> =
        post("get_theme_info_list", mapOf("zizum_num" to branch.zizumNum.toString()),
            object : ParameterizedTypeReference<KeyescapeEnvelope<List<KeyescapeThemeRow>>>() {}) ?: emptyList()

    fun themeDetail(infoNum: Int): KeyescapeThemeDetail? =
        post("get_theme_date", mapOf("num" to infoNum.toString()),
            object : ParameterizedTypeReference<KeyescapeEnvelope<KeyescapeThemeDetail>>() {})

    fun slots(branch: KeyescapeBranch, themeNum: Int, date: LocalDate): List<KeyescapeSlotRow> =
        post(
            "get_theme_time",
            mapOf(
                "date" to date.toString(),
                "zizumNum" to branch.zizumNum.toString(),
                "themeNum" to themeNum.toString(),
            ),
            object : ParameterizedTypeReference<KeyescapeEnvelope<List<KeyescapeSlotRow>>>() {},
        ) ?: emptyList()

    private fun <T> post(t: String, params: Map<String, String>, type: ParameterizedTypeReference<KeyescapeEnvelope<T>>): T? {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("t", t)
            params.forEach { (k, v) -> add(k, v) }
        }
        val json = rateLimiter.throttled(KeyescapeBranch.HOST) {
            client.post()
                .uri("/controller/run_proc.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                // 빈 값으로 덮어쓴다. 헤더를 빼면 자바 기본값이 대신 나간다
                .header("User-Agent", "")
                .body(form)
                .retrieve()
                .body(String::class.java)
        } ?: throw KeyescapeCrawlException("$t 응답 없음")

        val body = runCatching { mapper.readValue<KeyescapeEnvelope<T>>(json, mapper.constructType(type.type)) }
            .getOrElse { throw KeyescapeCrawlException("$t 응답을 못 읽었다: ${it.message}") }
        if (!body.status) {
            // **"안 여는 날" 과 "못 받았다" 는 다른 말이다.**
            // 사이트가 이유를 밝힌 경우(아직 오픈 안 한 날짜 등)는 회차가 0개인 것이지 실패가 아니다.
            // 나머지 거절은 예외로 끊는다 — 빈 리스트로 뭉개면 저장 단계가 멀쩡한 데이터를 지운다.
            // 문구가 바뀌면 다시 예외가 된다. 조용히 비우는 것보다 시끄럽게 깨지는 쪽이 낫다
            if (isNotOpen(body.msg)) return null
            throw KeyescapeCrawlException("$t 거절됨: ${body.msg}")
        }
        return body.data
    }

    companion object {
        private const val BASE_URL = "https://" + KeyescapeBranch.HOST

        /**
         * 거절이지만 **정상인** 응답. 그 날짜에 회차가 없다는 뜻이다.
         *
         * 에버랜드는 2026-08-27 기준 7일 내내 이 응답이었다 — 지점이 아직 안 열었거나
         * 예약 오픈 주기가 다른 것으로 보인다. 이걸 실패로 세면 한 바퀴마다 실패가 9건씩 쌓여서
         * **진짜 고장이 묻힌다.**
         */
        private val NOT_OPEN = listOf(
            "예약 가능 한 날짜가 아닙니다",
            "예약가능한 날짜가 아닙니다",
            // 2026-08-27 에버랜드 오늘 날짜에서 새로 나온 문구.
            // **모르는 거절을 예외로 끊어 뒀기 때문에 드러났다** — 조용히 비웠다면 못 봤을 것이다.
            // 목록은 앞으로도 늘어난다. 그게 이 설계가 의도한 방향이다
            "예약가능한 시간이 없습니다",
            // 2026-08-31 부산점 월요일에 나왔다. **정기 휴무일이라 매주 같은 요일에 반복된다** —
            // 그날 5테마가 전부 실패로 잡혀 한 바퀴 실패가 5건씩 쌓였다.
            //
            // ⚠️ **`예약오픈시간 : 11:30` 과는 다르다. 그건 일부러 안 넣었다.**
            //    지점휴일        그날 회차가 없다는 **답**이다        → 0개로 본다
            //    예약오픈시간     아직 대답할 시각이 아니라는 **거절**  → 모르는 상태다. 실패로 둔다
            // 둘 다 "거절" 로 오지만 하나는 결론이고 하나는 보류다. 섞으면 "오픈 전" 과
            // "진짜 못 받았다" 가 같은 값이 된다 — 이 프로젝트가 계속 경계하는 그것이다
            "지점휴일",
        )

        /** 문구가 바뀌면 여기가 먼저 깨지도록 테스트가 실제 응답을 물고 있다 */
        fun isNotOpen(msg: String?): Boolean = NOT_OPEN.any { msg?.contains(it) == true }
    }
}
