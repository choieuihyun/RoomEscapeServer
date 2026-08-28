package com.my_dream.server.notify

import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.http.HttpMethod
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FCM 응답을 어떻게 해석하는지. **진짜 구글 서버 없이 확인한다** —
 * 픽스처를 두는 이유는 크롤 어댑터와 같다: 응답 모양이 바뀌면 배포된 서버가
 * 조용히 오작동하기 전에 여기가 먼저 깨져야 한다.
 *
 * ⚠️ **픽스처는 구글 문서 기준의 추정이다. 실측이 아니다.**
 * 서비스 계정 키가 생기면 일부러 틀린 토큰으로 한 번 쏴 보고, 실제 응답으로 바꿔 넣는다.
 * 그때까지 [FcmNotifier.classify] 는 `errorCode` 와 HTTP 상태 **둘 다** 본다 —
 * 한쪽 모양이 예상과 달라도 나머지로 판정되게 하려는 것이다.
 */
class FcmNotifierTest {

    /** DB 없이 토큰만 들고 있는 가짜. 여기서 확인할 것은 "죽은 토큰을 지우는가" 지 JPA 가 아니다 */
    private class FakeDevices(vararg tokens: String) : DeviceTokens {
        val stored = tokens.toMutableList()
        override fun of(userId: String) = stored.toList()
        override fun register(userId: String, token: String, platform: String?) {
            stored += token
        }
        override fun forget(token: String) {
            stored -= token
        }
    }

    private val notification = Notification(
        userId = "나",
        branchName = "대전점",
        themeName = "우울해서 빵 샀어",
        date = LocalDate.of(2026, 8, 29),
        time = LocalTime.of(16, 0),
    )

    private fun fixture(name: String) =
        requireNotNull(javaClass.classLoader.getResourceAsStream(name)) { "픽스처 없음: $name" }
            .bufferedReader().readText()

    private fun setup(devices: DeviceTokens, token: () -> String = { "액세스토큰" }): Pair<FcmNotifier, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        val notifier = FcmNotifier(
            devices = devices,
            accessToken = FcmAccessToken { token() },
            builder = builder,
            projectId = "roomescapescheduler",
            link = "https://example.test/",
            baseUrl = "https://fcm.test",
        )
        return notifier to server
    }

    @Test
    fun `보내면 전달됐다고 답한다`() {
        val devices = FakeDevices("토큰A")
        val (notifier, server) = setup(devices)
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer 액세스토큰"))
            .andExpect(jsonPath("$.message.token").value("토큰A"))
            .andRespond(withSuccess(fixture("fcm-ok.json"), MediaType.APPLICATION_JSON))

        assertEquals(Delivery.DELIVERED, notifier.send(notification))
        server.verify()
    }

    @Test
    fun `알림 문구에 테마와 지점과 시각이 들어간다`() {
        val (notifier, server) = setup(FakeDevices("토큰A"))
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andExpect(jsonPath("$.message.notification.title").value("🎟️ 자리 났어요 — 우울해서 빵 샀어"))
            .andExpect(jsonPath("$.message.notification.body").value("대전점 · 8/29(토) 16:00"))
            // 누르면 Floduler 로 가야 한다. 없으면 눌러도 아무 일이 없다
            .andExpect(jsonPath("$.message.webpush.fcm_options.link").value("https://example.test/"))
            .andRespond(withSuccess(fixture("fcm-ok.json"), MediaType.APPLICATION_JSON))

        notifier.send(notification)
        server.verify()
    }

    @Test
    fun `등록 해제된 토큰은 지운다`() {
        val devices = FakeDevices("죽은토큰")
        val (notifier, server) = setup(devices)
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fixture("fcm-error-unregistered.json")),
            )

        // 남은 토큰이 없으니 실패가 아니라 "보낼 곳이 없다" 다. 실패로 세면 다음 바퀴마다 또 시도한다
        assertEquals(Delivery.NO_ADDRESS, notifier.send(notification))
        assertTrue(devices.stored.isEmpty())
    }

    @Test
    fun `기기가 둘인데 하나가 죽었으면 나머지로 전달된다`() {
        val devices = FakeDevices("죽은토큰", "산토큰")
        val (notifier, server) = setup(devices)
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fixture("fcm-error-unregistered.json")),
            )
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andRespond(withSuccess(fixture("fcm-ok.json"), MediaType.APPLICATION_JSON))

        assertEquals(Delivery.DELIVERED, notifier.send(notification))
        assertEquals(listOf("산토큰"), devices.stored)
    }

    @Test
    fun `인증이 거부되면 토큰을 지우지 않고 다시 시도한다`() {
        val devices = FakeDevices("토큰A")
        val (notifier, server) = setup(devices)
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andRespond(
                withStatus(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fixture("fcm-error-unauthenticated.json")),
            )

        // 우리 서비스 계정 키 문제다. 사용자의 토큰은 멀쩡한데 여기서 지우면 그 사람은 영영 못 받는다
        assertEquals(Delivery.FAILED, notifier.send(notification))
        assertEquals(listOf("토큰A"), devices.stored)
    }

    @Test
    fun `서비스가 일시적으로 죽으면 다시 시도한다`() {
        val devices = FakeDevices("토큰A")
        val (notifier, server) = setup(devices)
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andRespond(
                withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fixture("fcm-error-unavailable.json")),
            )

        assertEquals(Delivery.FAILED, notifier.send(notification))
        assertEquals(listOf("토큰A"), devices.stored)
    }

    @Test
    fun `요청이 잘못됐다는 응답에는 토큰을 지우지 않는다`() {
        val devices = FakeDevices("토큰A")
        val (notifier, server) = setup(devices)
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fixture("fcm-error-invalid-argument.json")),
            )

        // INVALID_ARGUMENT 는 **우리 요청이 틀렸을 때도** 나온다.
        // 우리 버그로 남의 토큰을 지우면 조용히 알림이 끊긴다
        assertEquals(Delivery.FAILED, notifier.send(notification))
        assertEquals(listOf("토큰A"), devices.stored)
    }

    @Test
    fun `상태 코드가 404가 아니어도 UNREGISTERED 면 지운다`() {
        val devices = FakeDevices("죽은토큰")
        val (notifier, server) = setup(devices)
        // 앞 테스트는 404 라서 상태 코드만으로도 통과한다 — 그러면 본문 파싱이 깨져도 안 잡힌다.
        // 구글이 상태 코드를 바꾸는 쪽에 대비해 errorCode 경로를 따로 고정한다
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andRespond(
                withStatus(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(fixture("fcm-error-unregistered.json")),
            )

        assertEquals(Delivery.NO_ADDRESS, notifier.send(notification))
        assertTrue(devices.stored.isEmpty())
    }

    @Test
    fun `본문이 JSON 이 아니어도 터지지 않는다`() {
        val devices = FakeDevices("토큰A")
        val (notifier, server) = setup(devices)
        // 프록시가 끼어들어 HTML 오류 페이지를 돌려주는 일이 있다. 파싱 실패로 발송 전체가
        // 예외로 끝나면 그 바퀴의 다른 사람 알림까지 같이 죽는다
        server.expect(requestTo("https://fcm.test/v1/projects/roomescapescheduler/messages:send"))
            .andRespond(withStatus(HttpStatus.BAD_GATEWAY).body("<html>502 Bad Gateway</html>"))

        assertEquals(Delivery.FAILED, notifier.send(notification))
        assertEquals(listOf("토큰A"), devices.stored)
    }

    @Test
    fun `등록한 기기가 없으면 요청을 아예 안 보낸다`() {
        val (notifier, server) = setup(FakeDevices())

        assertEquals(Delivery.NO_ADDRESS, notifier.send(notification))
        // 보낼 곳이 없는데 요청을 보내면 매 전이마다 확실히 실패할 요청이 한 건씩 늘어난다
        server.verify()
    }

    @Test
    fun `액세스 토큰을 못 받으면 실패로 두고 토큰은 남긴다`() {
        val devices = FakeDevices("토큰A")
        val (notifier, _) = setup(devices) { throw IllegalStateException("키 파일이 깨졌다") }

        assertEquals(Delivery.FAILED, notifier.send(notification))
        assertEquals(listOf("토큰A"), devices.stored)
    }
}
