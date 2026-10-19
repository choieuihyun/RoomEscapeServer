package com.my_dream.server.auth

import com.google.auth.oauth2.ServiceAccountCredentials
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.File
import java.time.Clock
import java.util.Date

/**
 * "이 uid 로 로그인시켜 달라" 는 **Firebase 커스텀 토큰**을 만든다.
 *
 * 카카오는 Firebase 가 직접 모르는 로그인이다. 서버가 카카오 회원번호를 확인한 뒤 이걸 만들어 주면,
 * 화면이 `signInWithCustomToken` 으로 Firebase 에 들어가 **보통의 ID 토큰**을 받는다.
 * 그 뒤로는 구글·아이디 로그인과 똑같다 — 서버의 토큰 검증(`ApiSecurityConfig`)은 손댈 게 없다.
 *
 * **인터페이스로 두는 이유는 테스트다.** 카카오 로그인 흐름을 확인하려고 진짜 키를 만들 수는 없다.
 */
fun interface FirebaseCustomTokens {
    /** @throws KakaoUnavailable 서명 키가 없을 때 — 카카오 로그인만 안 되고 나머지는 돈다 */
    fun mint(uid: String): String
}

/**
 * 서비스 계정 키로 **직접** 서명한다 — `firebase-admin` 없이 (아키텍처 D29, D16 유지).
 *
 * Admin SDK 의 `createCustomToken` 이 하는 일은 JWT 하나에 서명하는 것뿐이다. 그 하나 때문에
 * gRPC·Firestore 까지 끌고 오지 않는다. 서명은 이미 클래스패스에 있는 Nimbus(스프링 시큐리티가 토큰
 * 검증에 쓰는 것)로 하고, 키는 FCM 에 쓰는 **같은 서비스 계정 파일**에서 읽는다.
 *
 * 키 파일 상태별로 다르게 군다. **실패 방향을 고른 것이다:**
 * - 경로가 비었거나 파일이 0바이트 → 기동은 한다, 발급만 [KakaoUnavailable].
 *   로컬·테스트에는 키가 없고, 운영 compose 는 키를 안 줬을 때 `/dev/null` 을 마운트한다.
 *   이때 서버까지 안 뜨면 카카오와 무관한 수집·알림이 같이 죽는다
 * - 내용이 있는데 읽을 수 없다 → **기동을 거부한다.** 잘못된 키로 떠 있으면 첫 카카오 로그인 때에야
 *   조용히 깨진다. FCM 키(D16 "없으면 기동을 거부한다")와 같은 이유다
 */
@Component
class ServiceAccountCustomTokens(
    credentialsFile: String,
    private val clock: Clock,
) : FirebaseCustomTokens {

    /** 스프링이 부르는 쪽. 시계는 실제 시계다 — 테스트만 고정 시계를 넘긴다 */
    @Autowired
    constructor(
        @Value("\${notify.fcm.credentials-file:}") credentialsFile: String,
    ) : this(credentialsFile, Clock.systemUTC())

    private val log = LoggerFactory.getLogger(javaClass)

    private val credentials: ServiceAccountCredentials? = load(credentialsFile)

    private val signer = credentials?.let { RSASSASigner(it.privateKey) }

    private fun load(path: String): ServiceAccountCredentials? {
        val file = File(path)
        if (path.isBlank() || !file.isFile || file.length() == 0L) {
            log.warn("Firebase 서비스 계정 키가 없다 — 카카오 로그인은 '잠시 불가' 로 답한다 ({})", path.ifBlank { "경로 없음" })
            return null
        }
        val loaded = runCatching { file.inputStream().use { ServiceAccountCredentials.fromStream(it) } }
            .getOrElse { throw IllegalStateException("서비스 계정 키를 읽을 수 없다: $path — ${it.message}", it) }
        check(loaded.privateKey != null && !loaded.clientEmail.isNullOrBlank()) {
            "서비스 계정 키에 private_key 나 client_email 이 없다: $path"
        }
        return loaded
    }

    override fun mint(uid: String): String {
        val credentials = credentials ?: throw KakaoUnavailable("커스텀 토큰 서명 키가 없다")
        // Firebase 가 정한 한도. 넘기면 signInWithCustomToken 이 거절하고 원인이 화면 쪽에서만 보인다
        require(uid.isNotEmpty() && uid.length <= 128) { "Firebase uid 는 1~128자다: ${uid.length}자" }

        val now = clock.instant()
        // 모양은 Firebase 문서 "Create custom tokens using a third-party JWT library" 를 그대로 따른다
        val claims = JWTClaimsSet.Builder()
            .issuer(credentials.clientEmail)
            .subject(credentials.clientEmail)
            .audience(AUDIENCE)
            .issueTime(Date.from(now))
            // 최대가 1시간이다. 이 토큰은 화면이 받자마자 한 번 쓰고 버린다 — 길 이유가 없다
            .expirationTime(Date.from(now.plusSeconds(LIFETIME_SECONDS)))
            .claim("uid", uid)
            .build()
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims)
        jwt.sign(requireNotNull(signer))
        return jwt.serialize()
    }

    companion object {
        const val AUDIENCE = "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit"
        const val LIFETIME_SECONDS = 3600L
    }
}
