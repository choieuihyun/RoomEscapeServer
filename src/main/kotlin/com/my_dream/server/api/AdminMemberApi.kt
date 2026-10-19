package com.my_dream.server.api

import com.my_dream.server.club.MemberDto
import com.my_dream.server.club.MembershipService
import com.my_dream.server.domain.MemberRole
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class RoleRequest(val role: MemberRole)

/**
 * 멤버 관리 — 관리자만. **관리자인지는 서비스가 DB 로 다시 본다** — 여기서는 로그인만 확인된 상태다.
 *
 * 전부 POST 다(PUT·PATCH 안 씀). CORS 허용 메서드가 GET·POST·DELETE 뿐이라 늘리지 않으려고 계약을 그렇게 짰다.
 * [uid] 에는 `kakao:42` 처럼 `:` 가 들어간다. 경로 변수 그대로 받는다.
 */
@RestController
@RequestMapping("/api/admin/members")
class AdminMemberController(private val membership: MembershipService) {

    @GetMapping
    fun list(@AuthenticationPrincipal jwt: Jwt, @RequestParam(defaultValue = "ALL") status: String): List<MemberDto> =
        membership.list(jwt.uid(), status)

    @PostMapping("/{uid}/approve")
    fun approve(@AuthenticationPrincipal jwt: Jwt, @PathVariable uid: String): MemberDto =
        membership.approve(jwt.uid(), uid)

    @PostMapping("/{uid}/reject")
    fun reject(@AuthenticationPrincipal jwt: Jwt, @PathVariable uid: String): MemberDto =
        membership.reject(jwt.uid(), uid)

    @PostMapping("/{uid}/remove")
    fun remove(@AuthenticationPrincipal jwt: Jwt, @PathVariable uid: String): MemberDto =
        membership.remove(jwt.uid(), uid)

    @PostMapping("/{uid}/role")
    fun role(@AuthenticationPrincipal jwt: Jwt, @PathVariable uid: String, @RequestBody request: RoleRequest): MemberDto =
        membership.setRole(jwt.uid(), uid, request.role)
}
