package com.my_dream.server.auth

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 카카오 인가 코드 → 회원번호. **진짜 카카오 없이** 응답 해석만 본다.
 *
 * 응답 모양은 카카오 개발자 문서(REST API · 카카오 로그인 · 토큰 받기 / 사용자 정보 가져오기) 기준이다.
 * ⚠️ **실측이 아니다.** 앱 키가 생기면 일부러 쓴 코드로 한 번 보내 보고, 실제 응답으로 바꿔 넣는다.
 */
class KakaoClientTest {

    private fun setup(secret: String = "비밀", restKey: String = "rest-key"): Pair<KakaoClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val client = KakaoClient(
            builder = builder,
            authBase = "https://kauth.test",
            apiBase = "https://kapi.test",
            restKey = restKey,
            secret = secret,
            // 0 이면 요청 팩토리를 안 갈아 끼운다 — 갈아 끼우면 위에서 꽂은 가짜 서버가 빠진다
            timeoutMs = 0,
        )
        return client to server
    }

    private fun MockRestServiceServer.expectToken() =
        expect(requestTo("https://kauth.test/oauth/token")).andExpect(method(HttpMethod.POST))

    private val tokenOk = """{"token_type":"bearer","access_token":"AT","expires_in":21599,"refresh_token":"RT"}"""

    @Test
    fun `코드를 토큰으로 바꾸고 회원번호를 돌려준다`() {
        val (client, server) = setup()
        server.expectToken()
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
            .andExpect(content().formDataContains(
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to "rest-key",
                    "redirect_uri" to "https://app.test/R/",
                    "code" to "CODE",
                    "client_secret" to "비밀",
                ),
            ))
            .andRespond(withSuccess(tokenOk, MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://kapi.test/v2/user/me"))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer AT"))
            .andRespond(withSuccess("""{"id":42,"connected_at":"2026-10-19T03:00:00Z"}""", MediaType.APPLICATION_JSON))

        assertEquals(42L, client.userIdForCode("CODE", "https://app.test/R/"))
        server.verify()
    }

    @Test
    fun `클라이언트 시크릿을 안 쓰면 보내지 않는다`() {
        // 카카오 콘솔에서 시크릿을 "사용 안 함" 으로 두면 빈 값을 보내는 것도 거절될 수 있다
        val (client, server) = setup(secret = "")
        server.expectToken()
            .andExpect { req ->
                val body = (req as org.springframework.mock.http.client.MockClientHttpRequest).bodyAsString
                check("client_secret" !in body) { "시크릿이 없는데 실렸다: $body" }
            }
            .andRespond(withSuccess(tokenOk, MediaType.APPLICATION_JSON))
        server.expect(requestTo("https://kapi.test/v2/user/me"))
            .andRespond(withSuccess("""{"id":7}""", MediaType.APPLICATION_JSON))

        assertEquals(7L, client.userIdForCode("CODE", "https://app.test/R/"))
    }

    @Test
    fun `이미 쓴 코드는 코드 잘못이다`() {
        // 새로고침·뒤로 가기로 같은 코드가 다시 오면 카카오가 이렇게 답한다
        val (client, server) = setup()
        server.expectToken().andRespond(
            withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":"invalid_grant","error_description":"authorization code not found for code=CODE","error_code":"KOE320"}"""),
        )

        assertFailsWith<KakaoCodeInvalid> { client.userIdForCode("CODE", "https://app.test/R/") }
    }

    @Test
    fun `우리 설정이 틀린 4xx 는 코드 탓으로 돌리지 않는다`() {
        // 키·시크릿이 틀리면 사용자가 아무리 다시 눌러도 안 된다. "다시 눌러 주세요" 를 띄우면 거짓말이다
        val (client, server) = setup()
        server.expectToken().andRespond(
            withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                .body("""{"error":"invalid_client","error_description":"Bad client credentials","error_code":"KOE010"}"""),
        )

        assertFailsWith<KakaoUnavailable> { client.userIdForCode("CODE", "https://app.test/R/") }
    }

    @Test
    fun `카카오가 5xx 면 잠시 불가다`() {
        val (client, server) = setup()
        server.expectToken().andRespond(withServerError())

        assertFailsWith<KakaoUnavailable> { client.userIdForCode("CODE", "https://app.test/R/") }
    }

    @Test
    fun `앱 키가 없으면 카카오에 나가지도 않는다`() {
        val (client, server) = setup(restKey = "")

        assertFailsWith<KakaoUnavailable> { client.userIdForCode("CODE", "https://app.test/R/") }
        server.verify() // 기대한 요청이 없다 = 아무것도 안 나갔다
    }
}
