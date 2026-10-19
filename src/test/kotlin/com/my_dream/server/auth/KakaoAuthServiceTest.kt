package com.my_dream.server.auth

import com.my_dream.server.club.ClubError
import com.my_dream.server.domain.KakaoLink
import com.my_dream.server.domain.KakaoLinkRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.http.HttpStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.client.RestClient
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * 카카오 로그인 · 연결 — **누구의 uid 로 들어오게 하나.**
 *
 * 카카오 클라이언트와 서명기는 가짜다. 여기서 볼 것은 "회원번호 42 가 어느 uid 가 되나" 와
 * "남의 카카오를 내 계정에 못 붙이나" 지, 카카오·구글 서버가 아니다.
 */
@DataJpaTest
class KakaoAuthServiceTest @Autowired constructor(
    private val links: KakaoLinkRepository,
    private val txManager: PlatformTransactionManager,
) {

    private val app = "https://app.test/R/"

    /** 회원번호를 정해 둔 카카오. [fail] 이 있으면 그걸 던진다 */
    private class FakeKakao(var id: Long = 42, var fail: RuntimeException? = null) :
        KakaoClient(RestClient.builder(), "", "", "key", "", 0) {
        val calls = mutableListOf<Pair<String, String>>()
        override fun userIdForCode(code: String, redirectUri: String): Long {
            calls += code to redirectUri
            fail?.let { throw it }
            return id
        }
    }

    private class FakeTokens : FirebaseCustomTokens {
        val minted = mutableListOf<String>()
        override fun mint(uid: String): String = "token-for-$uid".also { minted += uid }
    }

    private val kakao = FakeKakao()
    private val tokens = FakeTokens()
    private fun service() = KakaoAuthService(kakao, tokens, links, txManager, "$app, http://localhost:5173/")

    private fun assertClubError(code: String, status: HttpStatus, block: () -> Unit) {
        val e = assertFailsWith<ClubError> { block() }
        assertEquals(code, e.code)
        assertEquals(status, e.status)
    }

    @Test
    fun `처음 들어온 카카오는 kakao 콜론 id uid 를 받는다`() {
        assertEquals("token-for-kakao:42", service().login("CODE", app))
        assertEquals(listOf("kakao:42"), tokens.minted)
        assertEquals("kakao:42", links.findByKakaoId(42)!!.userId)
    }

    @Test
    fun `두 번째부터는 같은 uid 로 들어온다`() {
        service().login("CODE1", app)
        service().login("CODE2", app)
        assertEquals(listOf("kakao:42", "kakao:42"), tokens.minted)
        assertEquals(1, links.count())
    }

    @Test
    fun `연결된 카카오는 기존 uid 로 들어온다`() {
        links.save(KakaoLink(42, "legacy-uid", Instant.now()))
        service().login("CODE", app)
        assertEquals(listOf("legacy-uid"), tokens.minted)
    }

    @Test
    fun `허용 안 된 redirectUri 는 카카오에 묻기도 전에 거절`() {
        // 남의 사이트가 우리 앱 키로 코드를 받아 우리 서버에서 토큰으로 바꾸는 길을 막는다
        assertClubError("REDIRECT_NOT_ALLOWED", HttpStatus.BAD_REQUEST) { service().login("CODE", "https://evil.test/") }
        assertClubError("REDIRECT_NOT_ALLOWED", HttpStatus.BAD_REQUEST) { service().link("me", "CODE", "https://evil.test/") }
        assertEquals(emptyList(), kakao.calls)
    }

    @Test
    fun `주소는 글자 그대로 같아야 한다`() {
        // 끝의 / 하나 차이도 카카오는 다른 주소로 본다. 우리가 너그러우면 카카오에서 KOE006 으로 깨져 원인이 흐려진다
        assertClubError("REDIRECT_NOT_ALLOWED", HttpStatus.BAD_REQUEST) { service().login("CODE", "https://app.test/R") }
        // 목록의 공백은 설정 실수일 뿐이다 — 잘라서 비교한다
        service().login("CODE", "http://localhost:5173/")
    }

    @Test
    fun `만료된 코드`() {
        kakao.fail = KakaoCodeInvalid("KOE320")
        assertClubError("KAKAO_CODE_INVALID", HttpStatus.BAD_REQUEST) { service().login("CODE", app) }
    }

    @Test
    fun `카카오가 안 되면 503`() {
        kakao.fail = KakaoUnavailable("down")
        assertClubError("KAKAO_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE) { service().login("CODE", app) }
    }

    @Test
    fun `서명 키가 없으면 503 이고 연결은 남지 않는다`() {
        // 키 없이 행만 만들어 두면, 키를 넣은 뒤 "연결" 을 하려는 기존 계정이 이미 kakao:42 에 뺏겨 있다
        val noKey = KakaoAuthService(kakao, { throw KakaoUnavailable("no key") }, links, txManager, app)
        assertClubError("KAKAO_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE) { noKey.login("CODE", app) }
        assertNull(links.findByKakaoId(42))
    }

    // --- 연결 ---

    @Test
    fun `기존 계정에 카카오를 연결한다`() {
        service().link("legacy-uid", "CODE", app)
        assertEquals("legacy-uid", links.findByKakaoId(42)!!.userId)
        // 다음부터 카카오로 들어오면 기존 uid 다
        service().login("CODE2", app)
        assertEquals(listOf("legacy-uid"), tokens.minted)
    }

    @Test
    fun `다른 계정에 연결된 카카오는 연결 못 한다`() {
        links.save(KakaoLink(42, "other", Instant.now()))
        assertClubError("KAKAO_ALREADY_LINKED", HttpStatus.CONFLICT) { service().link("me", "CODE", app) }
        assertEquals("other", links.findByKakaoId(42)!!.userId, "기존 연결은 그대로")
    }

    @Test
    fun `이미 다른 카카오가 붙은 계정에는 또 못 붙인다`() {
        links.save(KakaoLink(7, "me", Instant.now()))
        assertClubError("KAKAO_ALREADY_LINKED", HttpStatus.CONFLICT) { service().link("me", "CODE", app) }
        assertNull(links.findByKakaoId(42))
    }

    @Test
    fun `같은 계정에 다시 연결은 성공`() {
        service().link("me", "CODE", app)
        service().link("me", "CODE2", app)
        assertEquals(1, links.count())
    }
}
