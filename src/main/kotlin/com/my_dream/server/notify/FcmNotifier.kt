package com.my_dream.server.notify

import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 액세스 토큰을 얻는 자리. **인터페이스로 떼어 둔 이유는 테스트다.**
 *
 * 붙박이로 [GoogleCredentials] 를 들고 있으면 [FcmNotifier] 를 확인하려면
 * 진짜 서비스 계정 키가 있어야 한다. 우리가 검증하려는 건 "죽은 토큰을 지우는가" 이지
 * "구글 OAuth 가 도는가" 가 아니다 — 그건 구글이 이미 검증했다.
 */
fun interface FcmAccessToken {
    fun value(): String
}

/**
 * 서비스 계정 키로 액세스 토큰을 받아 온다.
 *
 * **비밀값이라 파일로 주입한다.** 환경변수에 JSON 통째로 넣는 방법도 있지만
 * 개인키에 줄바꿈이 들어 있어 `.env` 한 줄에 넣으면 거의 항상 깨진다.
 * 도커에는 읽기 전용으로 마운트한다 (`docker-compose.prod.yml`).
 *
 * 토큰은 1시간짜리다. [GoogleCredentials.refreshIfExpired] 가 만료 직전에만 실제로 갱신하므로
 * 발송마다 불러도 네트워크에 나가지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "notify", name = ["channel"], havingValue = "fcm")
class GoogleServiceAccountToken(
    @param:Value("\${notify.fcm.credentials-file}") private val credentialsFile: String,
) : FcmAccessToken {

    /**
     * **기동할 때 읽는다. 첫 알림 때가 아니라.**
     *
     * 늦게 읽으면 키가 잘못돼도 서버는 멀쩡히 떠 있고, 몇 주 뒤 진짜 취소표가 났을 때
     * 그제야 조용히 안 간다. 이 프로젝트가 계속 피하는 실패 방향이라 여기서도 같게 한다 —
     * `ddl-auto=validate` 가 스키마 불일치로 기동을 막는 것과 같은 이유다.
     *
     * 파일을 읽고 파싱하는 것뿐이라 네트워크에 나가지 않는다. 실제 토큰 발급은 첫 발송 때다.
     */
    private val credentials: GoogleCredentials = run {
        require(credentialsFile.isNotBlank()) {
            "notify.channel=fcm 인데 서비스 계정 키 경로가 비어 있다 — .env 에 FIREBASE_CREDENTIALS_PATH 를 넣을 것"
        }
        val file = File(credentialsFile)
        require(file.isFile) {
            "서비스 계정 키가 파일이 아니다: $credentialsFile " +
                "(도커가 없는 경로를 마운트하면 빈 디렉터리를 만든다. 그 디렉터리를 지우고 진짜 키를 올릴 것)"
        }
        file.inputStream().use { GoogleCredentials.fromStream(it).createScoped(SCOPE) }
    }

    /**
     * 수집은 사이트별로 동시에 돈다. 갱신이 겹치면 토큰을 두 번 받아 오거나 내부 상태가 엉킨다 —
     * 여기서 줄을 세우는 비용은 1시간에 한 번뿐이다.
     */
    @Synchronized
    override fun value(): String {
        credentials.refreshIfExpired()
        // 갱신에 성공했는데 토큰이 없을 수는 없다. 그래도 null 이면 조용히 빈 문자열을 보내
        // 401 로 실패하는 것보다 여기서 터지는 쪽이 원인을 찾기 쉽다
        return requireNotNull(credentials.accessToken) { "액세스 토큰이 비어 있다" }.tokenValue
    }

    companion object {
        /** FCM 발송 하나만. 넓은 `cloud-platform` 을 쓰면 키가 새어 나갔을 때 피해가 커진다 */
        private val SCOPE = listOf("https://www.googleapis.com/auth/firebase.messaging")
    }
}

/**
 * 진짜 푸시. `notify.channel=fcm` 일 때만 뜬다.
 *
 * **HTTP v1 API 를 직접 부른다** — `firebase-admin` SDK 대신 (아키텍처 D16).
 * 옛날 자료에 나오는 `https://fcm.googleapis.com/fcm/send` + 서버 키 방식은 폐기됐다.
 *
 * 한 사용자에게 기기가 여럿일 수 있고, v1 에는 여러 토큰을 한 번에 보내는 방법이 없다
 * (일괄 발송은 2024 년에 없어졌다). 그래서 **토큰마다 한 번씩** 보낸다.
 */
@Component
@ConditionalOnProperty(prefix = "notify", name = ["channel"], havingValue = "fcm")
class FcmNotifier(
    private val devices: DeviceTokens,
    private val accessToken: FcmAccessToken,
    builder: RestClient.Builder,
    @param:Value("\${auth.firebase.project-id}") private val projectId: String,
    @param:Value("\${notify.fcm.link}") private val link: String,
    @Value("\${notify.fcm.base-url:https://fcm.googleapis.com}") baseUrl: String,
) : Notifier {

    private val log = LoggerFactory.getLogger(javaClass)
    private val json = ObjectMapper()

    // 테스트가 주소를 바꿔 낄 수 있게 주입받은 builder 로 만든다.
    // `RestClient.create(고정주소)` 로 박으면 진짜 구글 서버 없이는 아무것도 확인할 수 없다
    private val client = builder.clone().baseUrl(baseUrl).build()

    /**
     * 알림 아이콘 — 앱 주소 옆의 `icon-192.png`. SDK 가 `notification` 으로 띄운 알림에는 이게 없으면 아이콘이 빈다.
     * (전에는 앱의 서비스 워커가 아이콘을 붙여 **한 번 더** 띄우고 있었다 — 알림이 두 번 뜨던 원인. Floduler 가 2026-10-19 에 고쳤다)
     * 주소를 따로 설정으로 두지 않고 [link] 에서 만든다 — 앱 주소가 바뀌면 아이콘도 같이 따라간다
     */
    private val icon = link.substringBefore('#').let { if (it.endsWith("/")) it else "$it/" } + "icon-192.png"

    override fun send(notification: Notification): Delivery = deliverAll(notification.userId, message(notification), link).delivery

    /** 동호회 알림 (D34). 발송 고리는 빈자리 알림과 같은 [deliverAll] 이다 */
    override fun push(push: Push): PushResult = deliverAll(push.userId, MessageText(push.title, push.body), link + push.linkFragment, push.tag)

    /**
     * 이 사람의 기기 전부에 하나씩. 빈자리 알림과 동호회 알림이 **같은 고리**를 쓴다 —
     * 죽은 토큰 정리 · 세 갈래 결과(D17)가 두 벌로 갈라지지 않게 (D34)
     */
    private fun deliverAll(userId: String, text: MessageText, link: String, tag: String? = null): PushResult {
        val tokens = devices.of(userId)
        if (tokens.isEmpty()) {
            // 실패가 아니다. 감시만 걸고 알림 권한은 안 준 상태다
            log.debug("보낼 기기가 없다 — 사용자 {}", userId)
            return PushResult(0, 0, 0)
        }

        var delivered = 0
        var retryable = 0

        for (token in tokens) {
            when (deliver(token, text, link, tag)) {
                Outcome.OK -> delivered++
                Outcome.RETRY -> retryable++
                // 지운 건 재시도 대상이 아니다. 다음 바퀴에는 목록에서 아예 빠진다
                Outcome.DEAD -> devices.forget(token)
            }
        }
        // 전부 죽어서 다 지웠으면 delivered·retryable 이 0 → NO_ADDRESS. 다시 시도해 봐야 보낼 곳이 없다
        return PushResult(tokens.size, delivered, retryable)
    }

    private fun deliver(token: String, text: MessageText, link: String, tag: String?): Outcome {
        val bearer = runCatching { accessToken.value() }.getOrElse {
            // 키 파일이 잘못됐거나 구글이 안 붙는 상황. 토큰 잘못이 아니므로 **지우지 않는다**
            log.error("FCM 액세스 토큰을 못 받았다 — {}", it.message)
            return Outcome.RETRY
        }

        return client.post()
            .uri("/v1/projects/{project}/messages:send", projectId)
            .header("Authorization", "Bearer $bearer")
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload(token, text, link, tag))
            .exchange { _, response ->
                if (response.statusCode.is2xxSuccessful) {
                    Outcome.OK
                } else {
                    classify(response.statusCode, response.bodyTo(String::class.java).orEmpty(), token)
                }
            }
    }

    /**
     * 실패를 **버릴 토큰**과 **다시 시도할 것**으로 가른다. 이 구분이 틀리면 둘 다 나쁘다 —
     * 살아 있는 토큰을 지우면 그 사람은 영영 알림을 못 받고,
     * 죽은 토큰을 남기면 발송할 때마다 시체에 요청이 한 번씩 더 나간다.
     *
     * `UNREGISTERED` 는 사용자가 알림을 껐거나 브라우저 데이터를 지운 것이다 — 확실히 죽었다.
     * `INVALID_ARGUMENT` 는 **우리 요청이 틀렸을 때도** 나온다. 우리 버그 때문에 남의 토큰을
     * 지우면 안 되므로 지우지 않고 크게 로그만 남긴다.
     */
    private fun classify(status: HttpStatusCode, body: String, token: String): Outcome {
        val errorCode = runCatching {
            val error = json.readTree(body).path("error")
            error.path("details")
                .firstOrNull { it.path("errorCode").isString }
                ?.path("errorCode")?.stringValue()
                ?: error.path("status").stringValue()
        }.getOrNull()

        return when {
            errorCode == "UNREGISTERED" || status.value() == 404 -> {
                log.info("기기가 등록 해제됨 — 토큰을 지운다 ({})", token.take(12))
                Outcome.DEAD
            }
            // 401/403 은 우리 자격증명 문제다. 토큰과 무관하니 지우지 않는다
            status.value() == 401 || status.value() == 403 -> {
                log.error("FCM 인증 거부 ({}) — 서비스 계정 키를 확인할 것: {}", status.value(), body.take(300))
                Outcome.RETRY
            }
            status.value() == 400 -> {
                log.error("FCM 이 요청을 거부했다 ({}) — 우리 쪽 버그일 수 있어 토큰은 남긴다: {}", errorCode, body.take(300))
                Outcome.RETRY
            }
            else -> {
                log.warn("FCM 발송 실패 ({} {}) — 다음 바퀴에 다시 시도한다", status.value(), errorCode)
                Outcome.RETRY
            }
        }
    }

    /** 화면에 보일 두 줄. 만드는 자리를 한 곳으로 모아 두려고 타입으로 둔다 */
    private data class MessageText(val title: String, val body: String)

    private fun message(n: Notification): MessageText {
        val day = n.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
        return MessageText(
            title = "🎟️ 자리 났어요 — ${n.themeName}",
            body = "${n.branchName} · ${n.date.format(DAY)}($day) ${n.time.format(TIME)}",
        )
    }

    /**
     * `notification` 으로 보내면 브라우저가 알아서 띄운다 — `data` 만 보내면 서비스 워커가
     * 직접 그려야 하고, 워커가 잠들어 있으면 아무것도 안 뜬다.
     */
    private fun payload(token: String, text: MessageText, link: String, tag: String?): String =
        json.writeValueAsString(
            mapOf(
                "message" to mapOf(
                    "token" to token,
                    "notification" to mapOf("title" to text.title, "body" to text.body),
                    // 알림을 누르면 Floduler 로 간다. 없으면 눌러도 아무 일도 안 일어난다
                    "webpush" to mapOf(
                        "fcm_options" to mapOf("link" to link),
                        // tag 가 같으면 브라우저가 앞 알림을 바꿔 끼운다. renotify 가 없으면 바꿔 끼울 때 소리·진동이 안 난다
                        "notification" to mapOf("icon" to icon, "badge" to icon) +
                            (tag?.let { mapOf("tag" to it, "renotify" to true) } ?: emptyMap()),
                    ),
                    // 같은 주소를 data 에도 싣는다 — 앱의 서비스 워커가 알림을 직접 다룰 때 읽는 자리다
                    // (`fcmOptions` 는 SDK 버전마다 꺼내는 이름이 달라 믿기 어렵다는 Floduler 쪽 요청, 2026-10-19)
                    // tag 도 싣는다 — 앱이 열려 있을 때 서비스 워커·화면이 직접 띄우는 알림도 같이 겹치게
                    "data" to mapOf("link" to link) + (tag?.let { mapOf("tag" to it) } ?: emptyMap()),
                ),
            ),
        )

    private enum class Outcome { OK, RETRY, DEAD }

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("M/d")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    }
}
