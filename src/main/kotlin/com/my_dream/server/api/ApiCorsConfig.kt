package com.my_dream.server.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Floduler 는 다른 출처(GitHub Pages)에서 돌기 때문에, 서버가 명시적으로 허용하지 않으면
 * 브라우저가 응답을 막는다. 서버 잘못이 아니라 브라우저의 기본 동작이다.
 *
 * `/api` 경로에만 연다. 디버그 엔드포인트는 열지 않는다.
 *
 * **`CorsConfigurationSource` 빈이어야 한다.** 예전에는 `WebMvcConfigurer.addCorsMappings`
 * 였는데, 시큐리티 필터는 MVC 보다 **먼저** 돌아서 그 설정을 보지 못한다. 그대로 뒀다면
 * 프리플라이트(OPTIONS)가 MVC 에 닿기 전에 막혀서 "CORS 를 분명히 열었는데 안 된다" 가 됐을 것이다.
 */
@Configuration
class ApiCorsConfig(
    @param:Value("\${api.cors.allowed-origins:http://localhost:5173}") private val allowedOrigins: String,
) {

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val config = CorsConfiguration().apply {
            allowedOrigins = this@ApiCorsConfig.allowedOrigins
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // 감시 등록/해제는 POST · DELETE 다. 조회만 있던 시절엔 GET 하나로 충분했다
            allowedMethods = listOf("GET", "POST", "DELETE")
            // `Authorization` 은 브라우저 기본 허용 헤더가 아니라서 여기 없으면 프리플라이트가 막힌다.
            // Floduler 가 Firebase ID 토큰을 이 헤더로 보낸다
            allowedHeaders = listOf("Authorization", "Content-Type", "Accept")
            // 쿠키를 쓰지 않는다. 토큰은 헤더로 오므로 자격증명 모드가 필요 없다
            allowCredentials = false
            maxAge = 1800
        }
        return UrlBasedCorsConfigurationSource().apply { registerCorsConfiguration("/api/**", config) }
    }
}
