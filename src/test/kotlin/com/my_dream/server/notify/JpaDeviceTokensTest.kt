package com.my_dream.server.notify

import com.my_dream.server.domain.DeviceTokenRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 기기 등록의 **자연키 upsert**.
 *
 * 브라우저는 앱을 열 때마다 같은 토큰을 다시 올린다. 여기가 틀리면 행이 쌓이고,
 * 그러면 취소표 한 번에 **푸시가 여러 번 간다** — 쿨다운(D11)이 막으려던 바로 그것이
 * 한 단계 아래에서 다시 새는 것이다.
 */
@DataJpaTest
class JpaDeviceTokensTest @Autowired constructor(
    private val repo: DeviceTokenRepository,
) {

    private val devices = { JpaDeviceTokens(repo) }

    @Test
    fun `같은 토큰을 다시 올려도 행이 늘지 않는다`() {
        devices().register("나", "토큰A", "web")
        devices().register("나", "토큰A", "web")

        assertEquals(1, repo.count().toInt())
        assertEquals(listOf("토큰A"), devices().of("나"))
    }

    @Test
    fun `다시 올리면 갱신 시각이 올라간다`() {
        devices().register("나", "토큰A", "web")
        val first = requireNotNull(repo.findByToken("토큰A")).updatedAt

        devices().register("나", "토큰A", "web")

        val second = requireNotNull(repo.findByToken("토큰A")).updatedAt
        // 언제 마지막으로 살아 있었는지가 남아야 나중에 오래된 토큰을 정리할 수 있다
        assertTrue(second >= first)
    }

    @Test
    fun `같은 기기로 다른 계정에 로그인하면 주인이 바뀐다`() {
        devices().register("나", "토큰A", "web")

        devices().register("너", "토큰A", "web")

        // 행이 둘이 되면 **내 감시가 울릴 때 네 폰으로도 간다.** 이게 이 자연키의 존재 이유다
        assertEquals(1, repo.count().toInt())
        assertEquals(listOf("토큰A"), devices().of("너"))
        assertTrue(devices().of("나").isEmpty())
    }

    @Test
    fun `한 사람이 기기를 여럿 쓸 수 있다`() {
        devices().register("나", "폰", "web")
        devices().register("나", "노트북", "web")

        assertEquals(setOf("폰", "노트북"), devices().of("나").toSet())
    }

    @Test
    fun `죽은 토큰을 지우면 목록에서 빠진다`() {
        devices().register("나", "폰", "web")
        devices().register("나", "노트북", "web")

        devices().forget("폰")

        assertEquals(listOf("노트북"), devices().of("나"))
    }

    @Test
    fun `없는 토큰을 지워도 터지지 않는다`() {
        // FCM 이 UNREGISTERED 를 두 번 답하는 경우가 있다. 두 번째에 예외가 나면
        // 그 바퀴의 나머지 사람 알림까지 같이 죽는다
        devices().forget("없는토큰")

        assertEquals(0, repo.count().toInt())
    }

    @Test
    fun `기기를 등록한 적 없는 사람은 빈 목록이다`() {
        // 빈 목록은 실패가 아니라 NO_ADDRESS 다 (D17)
        assertTrue(devices().of("아무도").isEmpty())
    }
}
