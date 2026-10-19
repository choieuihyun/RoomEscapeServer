package com.my_dream.server.api

import com.my_dream.server.club.MeDto
import com.my_dream.server.club.MembershipService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class JoinRequest(val nickname: String = "")

/**
 * 내 동호회 상태와 가입 신청. 계약은 Floduler `작업명세서.md` §4.6.
 *
 * 화면은 로그인하자마자 `GET /api/me` 로 상태를 받는다. `NONE` 이면 닉네임을 받아 `join` 한다.
 * 이 경로가 404 면 서버가 아직 동호회를 모르는 것이라, 화면은 동호회 기능만 숨긴다.
 */
@RestController
@RequestMapping("/api/me")
class MeController(private val membership: MembershipService) {

    @GetMapping
    fun me(@AuthenticationPrincipal jwt: Jwt): MeDto = membership.me(jwt.uid())

    @PostMapping("/join")
    fun join(@AuthenticationPrincipal jwt: Jwt, @RequestBody request: JoinRequest): MeDto =
        membership.join(jwt.uid(), request.nickname)
}
