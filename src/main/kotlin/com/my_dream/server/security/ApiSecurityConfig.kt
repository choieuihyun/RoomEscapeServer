package com.my_dream.server.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.web.SecurityFilterChain

/**
 * 누가 신청했는지를 **Floduler 의 로그인으로** 판단한다 (아키텍처 D12).
 *
 * Floduler 는 이미 Firebase Auth 를 쓴다. 브라우저가 로그인하면 받는 ID 토큰(JWT)을
 * `Authorization: Bearer …` 로 보내고, 서버는 구글 공개키로 서명을 검증한다.
 * **비밀번호는 우리 쪽을 지나가지 않는다** — 직접 만들면 반드시 틀리는 부분이라 남에게 맡긴다.
 *
 * 검증 항목:
 * - 서명 — 구글 공개키(JWK)로
 * - `iss` — `https://securetoken.google.com/<프로젝트>`
 * - `aud` — 우리 프로젝트. **기본 검증기가 안 보는 항목이라 직접 붙인다.**
 *           빠뜨리면 남의 Firebase 프로젝트에서 발급된 토큰도 통과한다
 * - `exp` — 만료
 *
 * `sub` 클레임이 Firebase uid 이고, 그게 `watch.user_id` 가 된다.
 */
@Configuration
class ApiSecurityConfig(
    @param:Value("\${auth.firebase.project-id}") private val projectId: String,
) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // 이름이 `corsConfigurationSource` 인 빈을 알아서 쓴다. 타입으로 주입하면
            // MVC 내부 빈(`mvcHandlerMappingIntrospector`)도 같은 타입이라 둘 중 뭘 쓸지 못 정한다
            .cors { }
            // 브라우저가 자동으로 붙이는 자격증명(쿠키)이 없으면 CSRF 도 성립하지 않는다.
            // 토큰은 자바스크립트가 헤더에 직접 넣는 값이라 남의 사이트가 대신 붙일 수 없다
            .csrf { it.disable() }
            // 세션을 만들지 않는다. 매 요청이 토큰만으로 완결된다
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                // 프리플라이트에는 토큰이 실리지 않는다. 막으면 본 요청까지 못 간다
                it.requestMatchers(HttpMethod.OPTIONS, "/api/**").permitAll()
                // 감시 등록·해제와 기기 등록만 로그인이 필요하다.
                // **`anyRequest().permitAll()` 이 아래 있으므로 여기 적지 않으면 그냥 열린다** —
                // 새 인증 경로를 만들면 반드시 이 줄에 같이 적는다
                it.requestMatchers("/api/watches", "/api/watches/**").authenticated()
                it.requestMatchers("/api/devices", "/api/devices/**").authenticated()
                // 나머지는 지금까지와 같다. 조회 API 는 Floduler 계약상 공개고,
                // 디버그는 이미 두 겹으로 막혀 있다 (기본 꺼짐 + Caddy 가 라우팅 안 함)
                it.anyRequest().permitAll()
            }
            .oauth2ResourceServer { rs -> rs.jwt { } }
        return http.build()
    }

    /**
     * `jwkSetUri` 로 만든다. `issuerUri` 를 쓰면 스프링이 **기동 시점에** 디스커버리 문서를
     * 받으러 나가서, 네트워크가 없으면 앱이 아예 안 뜨고 테스트도 같이 죽는다.
     * 이쪽은 첫 토큰이 올 때까지 미룬다.
     */
    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build().apply {
            setJwtValidator(
                DelegatingOAuth2TokenValidator(
                    JwtValidators.createDefault(),
                    JwtIssuerValidator("$ISSUER_PREFIX$projectId"),
                    AudienceValidator(projectId),
                ),
            )
        }

    /** `aud` 가 우리 프로젝트인지. 기본 검증기는 이걸 보지 않는다 */
    private class AudienceValidator(private val projectId: String) : OAuth2TokenValidator<Jwt> {
        override fun validate(token: Jwt): OAuth2TokenValidatorResult =
            if (token.audience?.contains(projectId) == true) {
                OAuth2TokenValidatorResult.success()
            } else {
                OAuth2TokenValidatorResult.failure(
                    OAuth2Error("invalid_token", "이 서버의 토큰이 아니다 (aud=${token.audience})", null),
                )
            }
    }

    companion object {
        const val JWK_SET_URI = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com"
        const val ISSUER_PREFIX = "https://securetoken.google.com/"
    }
}
