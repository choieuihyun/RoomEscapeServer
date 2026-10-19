package com.my_dream.server.api

import com.my_dream.server.auth.KakaoAuthService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 카카오가 돌려준 `?code=` 와, 그 로그인 창을 열 때 쓴 주소 (쿼리·해시 없이) */
data class KakaoCodeRequest(val code: String = "", val redirectUri: String = "")

data class CustomTokenDto(val customToken: String)

data class LinkedDto(val linked: Boolean)

/**
 * 카카오 로그인 · 연결 (아키텍처 D29). 계약은 Floduler `작업명세서.md` §4.6.
 *
 * `/api/auth/kakao` 는 **로그인 없이** 부른다 — 로그인하려고 부르는 경로다 (`ApiSecurityConfig`).
 * `/api/auth/kakao/link` 는 로그인한 계정에 붙이는 것이라 토큰이 필요하다.
 */
@RestController
@RequestMapping("/api/auth/kakao")
class KakaoAuthController(private val kakao: KakaoAuthService) {

    @PostMapping
    fun login(@RequestBody request: KakaoCodeRequest): CustomTokenDto =
        CustomTokenDto(kakao.login(request.code, request.redirectUri))

    @PostMapping("/link")
    fun link(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: KakaoCodeRequest): LinkedDto {
        kakao.link(jwt.uid(), request.code, request.redirectUri)
        return LinkedDto(linked = true)
    }
}
