package com.my_dream.server.api

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Floduler 는 다른 출처(GitHub Pages)에서 돌기 때문에, 서버가 명시적으로 허용하지 않으면
 * 브라우저가 응답을 막는다. 서버 잘못이 아니라 브라우저의 기본 동작이다.
 *
 * `/api` 경로에만 연다. 디버그 엔드포인트는 열지 않는다.
 * 자격증명(쿠키)은 보내지 않으므로 `allowCredentials` 도 켜지 않는다.
 */
@Configuration
class ApiCorsConfig(
    @param:Value("\${api.cors.allowed-origins:http://localhost:5173}") private val allowedOrigins: String,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOrigins(*allowedOrigins.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toTypedArray())
            .allowedMethods("GET")
    }
}
