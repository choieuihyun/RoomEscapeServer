package com.my_dream.server.auth

import com.my_dream.server.club.ClubError
import com.my_dream.server.domain.KakaoLink
import com.my_dream.server.domain.KakaoLinkRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * 카카오로 들어오기 · 기존 계정에 카카오 붙이기 (아키텍처 D29).
 *
 * **어느 uid 로 들어오게 하나**가 이 클래스의 전부다.
 * - `kakao_link` 에 있으면 그 uid — 기존 아이디 계정에 연결해 둔 경우다
 * - 없으면 `kakao:<회원번호>` 를 새로 만들고 **그 자리를 바로 기록한다.** 기록하지 않으면, 나중에 누가
 *   이 카카오를 다른 계정에 "연결" 했을 때 `kakao:42` 로 쌓아 둔 일정·알림이 주인 없이 남는다
 *
 * `redirectUri` 를 허용 목록과 **글자 그대로** 비교한다. 카카오도 콘솔에 등록된 주소와 글자 그대로 비교하는데,
 * 우리가 너그러우면 카카오에서 `KOE006` 으로 깨져 원인이 흐려진다. 그리고 남의 사이트가 우리 앱 키로 코드를 받아
 * 우리 서버에서 토큰으로 바꾸는 길을 **카카오에 묻기도 전에** 막는다.
 */
@Service
class KakaoAuthService(
    private val kakao: KakaoClient,
    private val tokens: FirebaseCustomTokens,
    private val links: KakaoLinkRepository,
    transactionManager: PlatformTransactionManager,
    @Value("\${kakao.allowed-redirect-uris:https://choieuihyun.github.io/RoomEscapeScheduler/,http://localhost:5173/}")
    allowedRedirectUris: String,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(transactionManager)

    // 목록의 공백은 설정 실수일 뿐이라 잘라 둔다. 비교 자체는 글자 그대로다
    private val allowed: Set<String> = allowedRedirectUris.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** @return Firebase 커스텀 토큰. 화면이 `signInWithCustomToken` 에 그대로 넣는다 */
    fun login(code: String, redirectUri: String): String {
        val kakaoId = verify(code, redirectUri)
        // 이미 있는 연결은 그 uid 로. 없으면 kakao:<id> — **토큰을 먼저 만들고 나서** 연결을 기록한다.
        // 순서가 반대면 서명 키가 없을 때 행만 남아, 키를 넣은 뒤 "연결" 하려던 기존 계정이 kakao:42 에 자리를 뺏긴다
        val known = links.findByKakaoId(kakaoId)?.userId
        val uid = known ?: "kakao:$kakaoId"
        val token = mint(uid)
        if (known != null) return token

        return try {
            tx.executeWithoutResult { links.saveAndFlush(KakaoLink(kakaoId, uid, Instant.now())) }
            log.info("카카오로 처음 들어왔다 — {}", uid)
            token
        } catch (e: DataIntegrityViolationException) {
            // 같은 카카오가 동시에 두 번 들어왔거나, 그 사이에 누가 이 카카오를 자기 계정에 연결했다.
            // **기록된 쪽이 맞다** — 그 uid 로 다시 만든다
            val winner = links.findByKakaoId(kakaoId)?.userId ?: throw e
            if (winner == uid) token else mint(winner)
        }
    }

    /**
     * 이미 로그인한 [uid] 에 이 카카오를 붙인다. 이미 이 uid 에 붙어 있으면 그대로 성공.
     * **카카오 하나는 계정 하나에만, 계정 하나에는 카카오 하나만** — 어느 쪽이 걸려도 [ClubError.kakaoAlreadyLinked].
     */
    fun link(uid: String, code: String, redirectUri: String) {
        val kakaoId = verify(code, redirectUri)
        val existing = links.findByKakaoId(kakaoId)
        if (existing != null) {
            if (existing.userId == uid) return
            throw ClubError.kakaoAlreadyLinked()
        }
        if (links.existsByUserId(uid)) throw ClubError.kakaoAlreadyLinked()

        try {
            tx.executeWithoutResult { links.saveAndFlush(KakaoLink(kakaoId, uid, Instant.now())) }
            log.info("카카오 연결 — {}", uid)
        } catch (e: DataIntegrityViolationException) {
            // 두 탭에서 동시에 눌렀다. 위 검사를 둘 다 지났고 UNIQUE 가 하나를 막았다 (V6)
            if (links.findByKakaoId(kakaoId)?.userId == uid) return
            throw ClubError.kakaoAlreadyLinked()
        }
    }

    private fun verify(code: String, redirectUri: String): Long {
        if (redirectUri !in allowed) {
            log.warn("허용 안 된 카카오 redirectUri — {}", redirectUri.take(200))
            throw ClubError.redirectNotAllowed()
        }
        // 빈 코드는 카카오에 물어봐야 같은 답이다. 요청 하나를 아낀다
        if (code.isBlank()) throw ClubError.kakaoCodeInvalid()
        return try {
            kakao.userIdForCode(code, redirectUri)
        } catch (e: KakaoCodeInvalid) {
            throw ClubError.kakaoCodeInvalid()
        } catch (e: KakaoUnavailable) {
            throw ClubError.kakaoUnavailable()
        }
    }

    private fun mint(uid: String): String =
        try {
            tokens.mint(uid)
        } catch (e: KakaoUnavailable) {
            log.error("커스텀 토큰을 못 만들었다 — {}", e.message)
            throw ClubError.kakaoUnavailable()
        }
}
