package com.my_dream.server.notify

import com.my_dream.server.domain.DeviceToken
import com.my_dream.server.domain.DeviceTokenRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * "이 사용자에게 보낼 주소가 어디인가".
 *
 * **인터페이스로 두는 이유는 DB 때문이 아니라 테스트 때문이다.** 발송기가 저장소를 직접 알면
 * FCM 응답 처리를 확인하려고 Postgres 를 띄워야 한다. 우리가 검증하고 싶은 건
 * "죽은 토큰이 왔을 때 지우는가" 지 "JPA 가 도는가" 가 아니다.
 */
interface DeviceTokens {
    /** 이 사용자가 알림을 받기로 한 기기 전부. 없으면 빈 목록 — 그건 실패가 아니다 */
    fun of(userId: String): List<String>

    /** 등록 또는 갱신. 같은 토큰이 이미 있으면 **주인을 이 사용자로 바꾼다** */
    fun register(userId: String, token: String, platform: String?)

    /** 더는 살아 있지 않은 토큰을 버린다. FCM 이 `UNREGISTERED` 로 답했을 때 */
    fun forget(token: String)
}

@Service
class JpaDeviceTokens(private val repo: DeviceTokenRepository) : DeviceTokens {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    override fun of(userId: String): List<String> = repo.findByUserId(userId).map { it.token }

    /**
     * **자연키 upsert.** 브라우저는 같은 토큰을 계속 다시 올려보낸다 (앱을 열 때마다).
     * 그때마다 행을 새로 만들면 한 기기에 알림이 열 번 간다.
     *
     * 주인이 다르면 갈아 끼운다 — 같은 브라우저에서 다른 계정으로 로그인한 경우고,
     * 이전 주인 앞으로 남겨 두면 **남의 폰으로 알림이 간다.**
     */
    @Transactional
    override fun register(userId: String, token: String, platform: String?) {
        val existing = repo.findByToken(token)
        if (existing == null) {
            repo.save(DeviceToken(userId, token, platform, Instant.now()))
            return
        }
        if (existing.userId != userId) {
            log.info("기기 토큰의 주인이 바뀜 — {} → {}", existing.userId, userId)
            existing.userId = userId
        }
        existing.updatedAt = Instant.now()
    }

    @Transactional
    override fun forget(token: String) {
        repo.findByToken(token)?.let {
            repo.delete(it)
            log.info("죽은 기기 토큰 삭제 — 사용자 {}", it.userId)
        }
    }
}
