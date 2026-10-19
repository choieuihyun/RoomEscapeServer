package com.my_dream.server.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration

/** 카카오가 이 인가 코드를 모른다 — 이미 썼거나(새로고침·뒤로 가기) 만료됐다. **사용자가 다시 누르면 풀린다** */
class KakaoCodeInvalid(message: String) : RuntimeException(message)

/** 카카오에 못 닿거나, 우리 쪽 설정(키·시크릿·서명 키)이 틀렸다. **사용자가 다시 눌러도 안 풀릴 수 있다** */
class KakaoUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 카카오 인가 코드 → 카카오 회원번호.
 *
 * 화면이 카카오 로그인 창에 다녀오면 `?code=…` 를 들고 온다. 그 코드를 우리가 카카오에 직접 확인해야
 * **"이 사람이 정말 카카오 회원번호 42 다"** 를 믿을 수 있다 — 화면이 회원번호를 보내 주면 아무나 남의 번호를 적는다.
 *
 * 쓰는 것은 회원번호(`id`) 하나뿐이다. 닉네임·이메일은 **동의 항목이 필요하고, 바뀐다.**
 * 회원번호는 앱마다 고정이라 키로 쓸 수 있다.
 *
 * 실패를 두 갈래로 나누는 이유는 화면 문구가 달라서다 — [KakaoCodeInvalid] 는 "다시 눌러 주세요" 가 맞지만,
 * 키가 틀린 것을 그렇게 말하면 **사용자가 영영 다시 누르게 된다.** FCM 응답을 가르는 것(D17)과 같은 결이다.
 */
@Component
class KakaoClient(
    builder: RestClient.Builder,
    @param:Value("\${kakao.auth-base-url:https://kauth.kakao.com}") private val authBase: String,
    @param:Value("\${kakao.api-base-url:https://kapi.kakao.com}") private val apiBase: String,
    /** 카카오 앱의 **REST API 키**. OAuth 의 `client_id` 다 — 비밀이 아니다(화면 주소에도 실린다) */
    @param:Value("\${kakao.rest-api-key:}") private val restKey: String,
    /**
     * REST API 키의 클라이언트 시크릿(카카오 로그인용). **이건 비밀이다** — 서버 `.env` 에만 둔다.
     * 2025-12 콘솔 개편 후 기본으로 켜져 있어 사실상 필수다. 그래도 비어 있으면 **안 보낸다** —
     * 콘솔에서 끈 앱에 빈 값을 보내면 그것대로 거절될 수 있다 (배포.md "⑫")
     */
    @param:Value("\${kakao.client-secret:}") private val secret: String,
    /**
     * 카카오가 대답을 안 할 때 얼마나 기다리나. 0 이면 요청 팩토리를 안 바꾼다(테스트용 —
     * 바꾸면 테스트가 꽂아 둔 가짜 서버가 빠진다).
     *
     * **기본값(무제한)으로 두면 안 된다.** 카카오가 멈추면 로그인 요청이 톰캣 스레드를 붙잡고 안 놓아,
     * 수집·조회 API 까지 같이 느려진다
     */
    @Value("\${kakao.timeout-ms:5000}") timeoutMs: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val json = ObjectMapper()

    private val client: RestClient = builder.clone().apply {
        if (timeoutMs > 0) {
            val timeout = Duration.ofMillis(timeoutMs)
            val http = HttpClient.newBuilder().connectTimeout(timeout).build()
            it.requestFactory(JdkClientHttpRequestFactory(http).apply { setReadTimeout(timeout) })
        }
    }.build()

    /**
     * @throws KakaoCodeInvalid 코드를 카카오가 모른다
     * @throws KakaoUnavailable 카카오에 못 닿았거나 우리 설정이 틀렸다
     */
    fun userIdForCode(code: String, redirectUri: String): Long {
        if (restKey.isBlank()) throw KakaoUnavailable("카카오 REST API 키가 없다 (KAKAO_REST_API_KEY)")
        val accessToken = exchange(code, redirectUri)
        return me(accessToken)
    }

    private fun exchange(code: String, redirectUri: String): String {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", restKey)
            // 로그인 창을 열 때 쓴 주소와 **글자 그대로** 같아야 한다. 다르면 카카오가 거절한다
            add("redirect_uri", redirectUri)
            add("code", code)
            if (secret.isNotBlank()) add("client_secret", secret)
        }
        val body = call("토큰") {
            client.post().uri("$authBase/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange { _, res -> res.statusCode to res.bodyTo(String::class.java).orEmpty() }
        }
        return body.path("access_token").textOrNull()
            ?: throw KakaoUnavailable("카카오 토큰 응답에 access_token 이 없다")
    }

    private fun me(accessToken: String): Long {
        val body = call("사용자 정보") {
            client.get().uri("$apiBase/v2/user/me")
                .header("Authorization", "Bearer $accessToken")
                .exchange { _, res -> res.statusCode to res.bodyTo(String::class.java).orEmpty() }
        }
        val id = body.path("id")
        if (!id.isIntegralNumber) throw KakaoUnavailable("카카오 사용자 정보에 id 가 없다")
        return id.longValue()
    }

    /** 요청 하나를 보내고 결과를 [KakaoCodeInvalid] / [KakaoUnavailable] / 정상 본문으로 가른다 */
    private fun call(what: String, send: () -> Pair<HttpStatusCode, String>): JsonNode {
        val (status, raw) = try {
            send()
        } catch (e: RestClientException) {
            // 연결 실패·시간 초과. 카카오 쪽 문제거나 네트워크다
            log.warn("카카오 {} 요청 실패 — {}", what, e.message)
            throw KakaoUnavailable("카카오에 닿지 못했다", e)
        }
        val body = runCatching { json.readTree(raw) }.getOrNull()
        if (status.is2xxSuccessful && body != null) return body

        val error = body?.path("error")?.textOrNull()
        val errorCode = body?.path("error_code")?.textOrNull()
        // `invalid_grant` 만 "코드가 틀렸다" 로 본다. 이미 쓴 코드·만료된 코드가 여기다 (KOE320).
        // **4xx 를 전부 코드 탓으로 돌리지 않는다** — 키·시크릿이 틀린 것(invalid_client · KOE010)도 4xx 다
        if (status.is4xxClientError && error == "invalid_grant") {
            log.info("카카오 인가 코드 거절 ({}) — 이미 썼거나 만료됐다", errorCode)
            throw KakaoCodeInvalid("카카오가 인가 코드를 거절했다 ($errorCode)")
        }
        if (status.is4xxClientError) {
            log.error("카카오 {} 요청을 카카오가 거절했다 ({} {} {}) — 앱 키·시크릿·리다이렉트 주소 설정을 볼 것: {}",
                what, status.value(), error, errorCode, raw.take(300))
        } else {
            log.warn("카카오 {} 응답 이상 ({}) — 잠시 뒤 다시", what, status.value())
        }
        throw KakaoUnavailable("카카오 $what 실패 (${status.value()} $errorCode)")
    }
}

/**
 * Jackson 3 의 `stringValue()` 는 값이 없거나 문자열이 아니면 **예외를 던진다** (2.x 의 `asText()` 처럼 빈 문자열을 주지 않는다).
 * 5xx 의 빈 본문에서 그 예외가 나면 [KakaoUnavailable] 대신 엉뚱한 500 이 된다 — 테스트가 그렇게 잡았다
 */
private fun JsonNode.textOrNull(): String? = if (isString) stringValue() else null
