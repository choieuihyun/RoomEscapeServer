package com.my_dream.server.notify

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.assertTrue

/**
 * **`notify.channel=fcm` 으로 스프링이 실제로 조립되는가.**
 *
 * ⚠️ **2026-08-31 에 진짜 사고를 겪고 나서 만들었다.**
 * [FcmNotifierTest] 11건이 전부 통과하고 있었는데, 서버에 키를 올리고
 * `NOTIFY_CHANNEL=fcm` 으로 처음 띄운 순간 기동이 막혔다:
 *
 * ```
 * Parameter 2 of constructor in FcmNotifier required a bean of type
 * 'org.springframework.web.client.RestClient$Builder' that could not be found.
 * ```
 *
 * **왜 안 잡혔나.** `FcmNotifierTest` 는 `RestClient.builder()` 를 **손으로 만들어 넘긴다.**
 * 그래야 `MockRestServiceServer` 를 끼울 수 있어서인데, 그 대가로 **스프링 배선을
 * 한 번도 안 거친다.** 발송 로직은 다 맞는데 조립이 안 되는 상태를 아무도 못 봤다.
 *
 * **단위 테스트가 통과한다고 뜨는 것은 아니다.** 그 간극을 메우는 게 이 파일의 전부다.
 */
@SpringBootTest
class FcmWiringTest @Autowired constructor(
    private val context: ApplicationContext,
) {

    @Test
    fun `fcm 채널로 컨텍스트가 뜬다`() {
        // 여기까지 왔다는 것 자체가 검증이다 — 빈 하나라도 못 만들면 컨텍스트가 안 뜬다
        assertTrue(context.getBean(Notifier::class.java) is FcmNotifier, "FcmNotifier 가 아니다")
    }

    @Test
    fun `RestClient Builder 빈이 있다`() {
        // 없어서 터졌던 바로 그 빈. Spring Boot 4 는 HTTP 클라이언트 자동설정이
        // 별도 모듈이라 starter-webmvc 만으로는 안 생긴다 — HttpClientConfig 가 만든다
        assertTrue(context.getBeanNamesForType(RestClient.Builder::class.java).isNotEmpty())
    }

    @Test
    fun `서비스 계정 키를 기동할 때 읽는다`() {
        // 늦게 읽으면 키가 잘못돼도 서버는 멀쩡히 떠 있고, 몇 주 뒤 진짜 취소표가 났을 때
        // 그제야 조용히 안 간다. 그래서 생성자에서 읽는다 — 이 빈이 있다는 건 이미 읽혔다는 뜻이다
        assertTrue(context.getBeanNamesForType(FcmAccessToken::class.java).isNotEmpty())
    }

    companion object {

        /**
         * 가짜 서비스 계정 키를 **테스트가 돌 때 만든다. 저장소에 두지 않는다.**
         *
         * 처음엔 픽스처 파일로 커밋하려다 **GitHub 푸시 보호에 막혔다** —
         * 내용이 폐기용 RSA 키여도 `"type": "service_account"` 모양이면 스캐너는 진짜로 본다.
         * **그게 맞는 판단이다.** 파일만 봐서는 사람도 구분 못 하고,
         * 한번 예외로 통과시키면 다음에 진짜 키가 섞여도 같은 방식으로 넘어간다.
         *
         * 그래서 매번 새로 만든다. 구글에 대고 쓸 수 없고, 여기서 확인하려는 것도
         * "키가 유효한가" 가 아니라 **"조립되는가"** 다.
         */
        @JvmStatic
        @DynamicPropertySource
        fun fcmProperties(registry: DynamicPropertyRegistry) {
            val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                .generateKeyPair().private.encoded
                .let { Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it) }
            val file = createTempFile("fcm-test-", ".json")
            file.writeText(
                """
                {
                  "type": "service_account",
                  "project_id": "test-project",
                  "private_key_id": "0000000000000000000000000000000000000000",
                  "private_key": "-----BEGIN PRIVATE KEY-----\n${key.replace("\n", "\\n")}\n-----END PRIVATE KEY-----\n",
                  "client_email": "fake-tests@test-project.iam.gserviceaccount.com",
                  "client_id": "000000000000000000000",
                  "token_uri": "https://oauth2.googleapis.com/token"
                }
                """.trimIndent(),
            )
            file.toFile().deleteOnExit()

            registry.add("notify.channel") { "fcm" }
            registry.add("notify.fcm.credentials-file") { file.toAbsolutePath().toString() }
            // ⚠️ `src/test/resources/application.properties` 가 운영용 파일을 **가린다**(같은 이름).
            // 운영에 있는 설정도 여기서는 없는 값이라 직접 넣어야 한다
            registry.add("notify.fcm.link") { "https://example.test/" }
        }
    }
}
