package com.my_dream.server.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.SignedJWT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 카카오 로그인 뒤에 화면에 돌려줄 **Firebase 커스텀 토큰**을 우리가 직접 서명한다 (아키텍처 D29).
 *
 * 진짜 서비스 계정 키 대신 **테스트가 만든 RSA 키**로 가짜 키 파일을 만든다 —
 * 확인할 것은 "구글이 정한 모양대로 서명하는가" 지 구글 서버가 아니다.
 * 모양은 Firebase 문서 "Create custom tokens using a third-party JWT library" 기준이다.
 */
class FirebaseCustomTokensTest {

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private fun serviceAccountFile(dir: Path): File {
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
        val json = ObjectMapper().writeValueAsString(
            mapOf(
                "type" to "service_account",
                "project_id" to "test-project",
                "private_key_id" to "test-key-id",
                "private_key" to pem,
                "client_email" to "firebase-adminsdk-test@test-project.iam.gserviceaccount.com",
                "client_id" to "1234567890",
                "token_uri" to "https://oauth2.googleapis.com/token",
            ),
        )
        return dir.resolve("sa.json").toFile().apply { writeText(json) }
    }

    @Test
    fun `서비스 계정 키로 서명하고 구글이 정한 클레임을 싣는다`(@TempDir dir: Path) {
        val tokens = ServiceAccountCustomTokens(serviceAccountFile(dir).path, clock)

        val jwt = SignedJWT.parse(tokens.mint("kakao:42"))

        assertEquals(JWSAlgorithm.RS256, jwt.header.algorithm)
        assertTrue(jwt.verify(RSASSAVerifier(keys.public as RSAPublicKey)), "우리 키의 서명이어야 한다")
        val claims = jwt.jwtClaimsSet
        val email = "firebase-adminsdk-test@test-project.iam.gserviceaccount.com"
        assertEquals(email, claims.issuer)
        assertEquals(email, claims.subject)
        assertEquals(listOf(ServiceAccountCustomTokens.AUDIENCE), claims.audience)
        assertEquals("kakao:42", claims.getStringClaim("uid"))
        assertEquals(now, claims.issueTime.toInstant())
        // 구글이 받아 주는 최대가 1시간이다. 넘기면 signInWithCustomToken 이 거절한다
        assertEquals(3600, claims.expirationTime.toInstant().epochSecond - claims.issueTime.toInstant().epochSecond)
    }

    @Test
    fun `키가 없으면 기동은 되지만 발급은 카카오 불가로 끝난다`() {
        // 로컬·테스트에는 키가 없다. 그렇다고 서버가 안 뜨면 카카오와 무관한 개발까지 막힌다
        val tokens = ServiceAccountCustomTokens("", clock)
        assertFailsWith<KakaoUnavailable> { tokens.mint("kakao:42") }
    }

    @Test
    fun `빈 파일도 키 없음이다`(@TempDir dir: Path) {
        // 운영 compose 는 키를 안 줬을 때 /dev/null 을 마운트한다 — 크기 0 인 "파일" 이 온다
        val empty = dir.resolve("empty.json").toFile().apply { writeText("") }
        val tokens = ServiceAccountCustomTokens(empty.path, clock)
        assertFailsWith<KakaoUnavailable> { tokens.mint("kakao:42") }
    }

    @Test
    fun `내용이 있는데 키가 아니면 기동을 거부한다`(@TempDir dir: Path) {
        // 잘못된 키로 떠 있으면 첫 카카오 로그인 때에야 조용히 깨진다 (D16 "없으면 기동을 거부한다" 와 같은 결)
        val broken = dir.resolve("broken.json").toFile().apply { writeText("{ \"type\": \"service_account\" }") }
        assertFailsWith<IllegalStateException> { ServiceAccountCustomTokens(broken.path, clock) }
    }
}
